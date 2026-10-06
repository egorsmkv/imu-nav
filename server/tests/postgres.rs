//! PostgreSQL parity and cutover tests run when `TEST_POSTGRES_URL` points at a disposable database.

use anyhow::Result;
use imu_nav_cell_server::{
    AppState, CellKey, CellStore, CellTower, Consensus, Policy, PrivacyNotice, Radio, ServerConfig,
    create_admin, encode_towers, router,
};
use postgres_native_tls::MakeTlsConnector;
use reqwest::StatusCode;
use std::io::Read;
use std::net::{Ipv4Addr, SocketAddr};
use std::time::{SystemTime, UNIX_EPOCH};

fn isolated_url() -> Result<Option<String>> {
    let Ok(base) = std::env::var("TEST_POSTGRES_URL") else {
        return Ok(None);
    };
    let unique = SystemTime::now().duration_since(UNIX_EPOCH)?.as_nanos();
    let schema = format!("imu_nav_test_{}_{}", std::process::id(), unique);
    let tls = MakeTlsConnector::new(native_tls::TlsConnector::builder().build()?);
    let mut client = postgres::Client::connect(&base, tls)?;
    client.batch_execute(&format!("CREATE SCHEMA {schema}"))?;
    let separator = if base.contains('?') { '&' } else { '?' };
    Ok(Some(format!(
        "{base}{separator}options=-csearch_path%3D{schema}"
    )))
}

fn postgres_client(url: &str) -> Result<postgres::Client> {
    let tls = MakeTlsConnector::new(native_tls::TlsConnector::builder().build()?);
    Ok(postgres::Client::connect(url, tls)?)
}

fn populate_migration_source(path: &std::path::Path) -> Result<()> {
    let source = rusqlite::Connection::open(path)?;
    source.execute_batch(
        "INSERT INTO users(id,email,password_hash,admin) VALUES (2,'driver@example.org','hash',0);
         INSERT INTO privacy_consents(id,account_id,purpose,notice_version,granted,at_s) VALUES (1,2,'tower_upload','v1',1,100);
         INSERT INTO air_alert_preferences(account_id,enabled,updated_s) VALUES (2,1,100);
         INSERT INTO trip_archive(account_id,id,start_ms,bytes,summary,document) VALUES (2,'migration-trip',100,2,'{}','{}');
         INSERT INTO account_sync_state(account_id,generation) VALUES (2,3);
         INSERT INTO account_sync_entries(account_id,kind,key,revision,value_json) VALUES (2,'setting','voice',2,'false');
         INSERT INTO tower_moderation(radio,mcc,mnc,area,cid,quarantined) VALUES ('LTE',255,1,100,202,1);
         INSERT INTO tower_removals(radio,mcc,mnc,area,cid,updated_s) VALUES ('LTE',255,1,100,201,101);
         INSERT INTO server_settings(key,value) VALUES ('debug_upload_enabled','1');
         INSERT INTO admin_audit(id,actor_id,action,target,at_s) VALUES (11,1,'test','2',100);
         INSERT INTO admin_jobs(id,kind,status,processed,started_s) VALUES (7,'import','complete',1,100);
         INSERT INTO admin_import_rejections(job_id,row_number,reason,input) VALUES (7,2,'bad','row');
         INSERT INTO account_deleted_keys(account_id,radio,mcc,mnc,area,cid,device) VALUES (2,'LTE',255,1,100,200,'*');
         INSERT INTO email_verifications(token_hash,user_id,email,expires_s) VALUES ('verify',2,'driver@example.org',1000);
         INSERT INTO auth_tokens(token_hash,user_id,session_id,kind,expires_s) VALUES ('admin-token',1,'session-a','web',1000);
         INSERT INTO auth_tokens(token_hash,user_id,session_id,kind,expires_s) VALUES ('user-token',2,'session-b','web_impersonated',1000);
         INSERT INTO web_impersonations(token_hash,admin_token_hash,actor_id,target_id) VALUES ('user-token','admin-token',1,2);
         INSERT INTO password_resets(token_hash,user_id,expires_s) VALUES ('reset',2,1000);
         INSERT INTO debug_sessions(id,account_id,client_id,context_json,created_s,updated_s) VALUES ('trip',2,'client','{}',100,100);
         INSERT INTO debug_batches(session_id,seq,digest,bytes) VALUES ('trip',0,'digest',1);
         INSERT INTO debug_entries(session_id,seq,item,kind,elapsed_ms,line) VALUES ('trip',0,0,'event',1,'X,1');",
    )?;
    Ok(())
}

fn assert_migrated_tables(source_path: &std::path::Path, url: &str) -> Result<()> {
    let sqlite = rusqlite::Connection::open(source_path)?;
    let mut postgres = postgres_client(url)?;
    for table in [
        "users",
        "contributions",
        "consensus",
        "tower_moderation",
        "tower_removals",
        "server_settings",
        "admin_audit",
        "admin_jobs",
        "admin_import_rejections",
        "account_deleted_keys",
        "email_verifications",
        "auth_tokens",
        "web_impersonations",
        "password_resets",
        "privacy_consents",
        "air_alert_preferences",
        "trip_archive",
        "account_sync_identity",
        "account_sync_state",
        "account_sync_entries",
        "debug_sessions",
        "debug_batches",
        "debug_entries",
    ] {
        let sql = format!("SELECT COUNT(*) FROM {table}");
        let expected: i64 = sqlite.query_row(&sql, [], |row| row.get(0))?;
        let actual: i64 = postgres.query_one(&sql, &[])?.get(0);
        assert_eq!(actual, expected, "{table}");
    }
    for (table, expected) in [
        ("users", 3),
        ("admin_jobs", 8),
        ("admin_audit", 12),
        ("privacy_consents", 2),
    ] {
        let sql = match table {
            "users" => "INSERT INTO users(email,password_hash) VALUES ('later@example.org','hash') RETURNING id".to_owned(),
            "admin_jobs" => "INSERT INTO admin_jobs(kind,status,started_s) VALUES ('test','complete',1) RETURNING id".to_owned(),
            "admin_audit" => "INSERT INTO admin_audit(actor_id,action,target,at_s) VALUES (1,'test','next',1) RETURNING id".to_owned(),
            _ => "INSERT INTO privacy_consents(account_id,purpose,notice_version,granted,at_s) VALUES (2,'diagnostics','v1',1,1) RETURNING id".to_owned(),
        };
        let id: i64 = postgres.query_one(&sql, &[])?.get(0);
        assert_eq!(id, expected, "{table} sequence");
    }
    Ok(())
}

#[test]
fn imports_full_sqlite_state_and_continues_writing() -> Result<()> {
    let Some(url) = isolated_url()? else {
        return Ok(());
    };
    let source = tempfile::NamedTempFile::new()?;
    let sqlite = CellStore::open(source.path())?;
    create_admin(&sqlite, "admin@example.org", "correct horse battery staple")?;
    let policy = Policy::default();
    let tower = CellTower {
        key: CellKey {
            radio: Radio::Lte,
            mcc: 255,
            mnc: 1,
            area: 100,
            cid: 200,
        },
        lat: 50.45,
        lon: 30.52,
        range_m: 500.0,
        samples: 3,
    };
    sqlite.seed(std::slice::from_ref(&tower), 100, &policy)?;
    populate_migration_source(source.path())?;
    let postgres = CellStore::open_postgres(&url)?;
    postgres.migrate_from_sqlite(source.path())?;
    assert_eq!(postgres.counts(&policy)?, sqlite.counts(&policy)?);
    assert_eq!(postgres.query(None, 0, None, &policy)?.len(), 1);
    assert_migrated_tables(source.path(), &url)?;
    assert!(postgres.migrate_from_sqlite(source.path()).is_err());
    let mut second = tower;
    second.key.cid = 203;
    postgres.seed(&[second], 101, &policy)?;
    assert_eq!(postgres.query(None, 0, None, &policy)?.len(), 2);
    Ok(())
}

#[test]
fn failed_migration_rolls_back_all_tables() -> Result<()> {
    let Some(url) = isolated_url()? else {
        return Ok(());
    };
    let source = tempfile::NamedTempFile::new()?;
    let sqlite = CellStore::open(source.path())?;
    create_admin(&sqlite, "admin@example.org", "correct horse battery staple")?;
    let source_connection = rusqlite::Connection::open(source.path())?;
    source_connection.pragma_update(None, "foreign_keys", "OFF")?;
    source_connection.execute("INSERT INTO auth_tokens(token_hash,user_id,session_id,kind,expires_s) VALUES ('orphan',999,'session','web',1000)", [])?;
    let postgres = CellStore::open_postgres(&url)?;
    assert!(postgres.migrate_from_sqlite(source.path()).is_err());
    let mut client = postgres_client(&url)?;
    let users: i64 = client.query_one("SELECT COUNT(*) FROM users", &[])?.get(0);
    let tokens: i64 = client
        .query_one("SELECT COUNT(*) FROM auth_tokens", &[])?
        .get(0);
    assert_eq!((users, tokens), (0, 0));
    Ok(())
}

#[tokio::test]
async fn api_auth_upload_and_private_diagnostics_use_postgres() -> Result<()> {
    let _ = tracing_subscriber::fmt()
        .with_env_filter("imu_nav_cell_server=debug")
        .try_init();
    let Some((state, url)) =
        tokio::task::spawn_blocking(|| -> Result<Option<(AppState, String)>> {
            let Some(url) = isolated_url()? else {
                return Ok(None);
            };
            let store = CellStore::open_postgres(&url)?;
            create_admin(&store, "admin@example.org", "correct horse battery staple")?;
            let state = AppState::new(
                store.clone(),
                ServerConfig {
                    trip_archive: imu_nav_cell_server::TripArchiveLimits::default(),
                    mail: None,
                    policy: Policy::default(),
                    trust_proxy: false,
                    secure_cookies: false,
                    privacy: Some(PrivacyNotice {
                        version: "v1".into(),
                        controller: "Example".into(),
                        contact: "contact@example.org".into(),
                        rights_contact: "rights@example.org".into(),
                        region: "EU".into(),
                        recipients: "Host".into(),
                        transfers: "None".into(),
                        account_basis: "Contract".into(),
                        security_basis: "Legitimate interests".into(),
                        notice_en: "Example notice".into(),
                        notice_uk: "Повідомлення".into(),
                        notice_ru: "Уведомление".into(),
                        tower_retention_months: 12,
                        diagnostics_retention_days: 30,
                    }),
                },
            )?;
            Ok(Some((state, url)))
        })
        .await??
    else {
        return Ok(());
    };
    let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    let base = format!("http://{}", listener.local_addr()?);
    let task = tokio::spawn(async move {
        axum::serve(
            listener,
            router(state).into_make_service_with_connect_info::<SocketAddr>(),
        )
        .await
        .expect("test server");
    });
    exercise_api(&base, &url).await?;
    exercise_browser(&base).await?;
    exercise_admin_mutations(&base).await?;
    exercise_paged_public_exports(&base, &url).await?;
    exercise_account_sync(&base).await?;
    exercise_privacy_withdrawal(&base, &url).await?;
    task.abort();
    Ok(())
}

async fn exercise_privacy_withdrawal(base: &str, url: &str) -> Result<()> {
    let client = reqwest::Client::new();
    let login: serde_json::Value = client.post(format!("{base}/v1/auth/login"))
        .json(&serde_json::json!({"email":"driver@example.org","password":"correct horse battery staple"}))
        .send().await?.error_for_status()?.json().await?;
    let token = login["access_token"].as_str().expect("access token");
    let tower = CellTower {
        key: CellKey {
            radio: Radio::Lte,
            mcc: 255,
            mnc: 1,
            area: 100,
            cid: 909,
        },
        lat: 50.45,
        lon: 30.52,
        range_m: 500.0,
        samples: 3,
    };
    let body = encode_towers(&[Consensus {
        tower,
        devices: 2,
        seeded: false,
        updated_s: 1,
    }])?;
    assert_eq!(
        client
            .put(format!("{base}/v1/privacy/consents/tower_upload"))
            .bearer_auth(token)
            .json(&serde_json::json!({"notice_version":"v1"}))
            .send()
            .await?
            .status(),
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        client
            .post(format!("{base}/v1/cells"))
            .bearer_auth(token)
            .header("x-device-id", "pg-privacy-phone")
            .body(body)
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        client
            .delete(format!("{base}/v1/privacy/consents/tower_upload"))
            .bearer_auth(token)
            .send()
            .await?
            .status(),
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        client
            .delete(format!("{base}/v1/privacy/consents/diagnostics"))
            .bearer_auth(token)
            .send()
            .await?
            .status(),
        StatusCode::NO_CONTENT
    );
    let url = url.to_owned();
    let (remaining, sessions) = tokio::task::spawn_blocking(move || -> Result<(i64, i64)> {
        let mut database = postgres_client(&url)?;
        let remaining = database
            .query_one(
                "SELECT COUNT(*) FROM contributions WHERE cid=909 AND device LIKE 'account:%'",
                &[],
            )?
            .get(0);
        let sessions = database
            .query_one(
                "SELECT COUNT(*) FROM debug_sessions WHERE account_id=2",
                &[],
            )?
            .get(0);
        Ok((remaining, sessions))
    })
    .await??;
    assert_eq!((remaining, sessions), (0, 0));
    Ok(())
}

async fn exercise_api(base: &str, url: &str) -> Result<()> {
    let client = reqwest::Client::new();
    let register = client.post(format!("{base}/v1/auth/register"))
        .json(&serde_json::json!({"email":"driver@example.org","password":"correct horse battery staple"}))
        .send().await?;
    assert_eq!(register.status(), StatusCode::OK);
    let session: serde_json::Value = register.json().await?;
    let access = session["access_token"].as_str().expect("access token");
    assert_eq!(
        client
            .get(format!("{base}/v1/auth/me"))
            .bearer_auth(access)
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    let tower = CellTower {
        key: CellKey {
            radio: Radio::Lte,
            mcc: 255,
            mnc: 1,
            area: 100,
            cid: 200,
        },
        lat: 50.45,
        lon: 30.52,
        range_m: 500.0,
        samples: 3,
    };
    let body = encode_towers(&[Consensus {
        tower,
        devices: 2,
        seeded: false,
        updated_s: 1,
    }])?;
    let missing_consent = client
        .post(format!("{base}/v1/cells"))
        .bearer_auth(access)
        .header("x-device-id", "device-aaaa-1111")
        .body(body.clone())
        .send()
        .await?;
    assert_eq!(missing_consent.status(), StatusCode::FORBIDDEN);
    assert_eq!(
        missing_consent.json::<serde_json::Value>().await?["message"],
        "CONSENT_REQUIRED"
    );
    assert_eq!(
        client
            .put(format!("{base}/v1/privacy/consents/tower_upload"))
            .bearer_auth(access)
            .json(&serde_json::json!({"notice_version":"v1"}))
            .send()
            .await?
            .status(),
        StatusCode::NO_CONTENT
    );
    for device in ["device-aaaa-1111", "device-bbbb-2222"] {
        let uploaded = client
            .post(format!("{base}/v1/cells"))
            .bearer_auth(access)
            .header("x-device-id", device)
            .body(body.clone())
            .send()
            .await?;
        assert_eq!(
            uploaded.status(),
            StatusCode::OK,
            "{}",
            uploaded.text().await?
        );
    }
    let admin: serde_json::Value = client.post(format!("{base}/v1/auth/login"))
        .json(&serde_json::json!({"email":"admin@example.org","password":"correct horse battery staple"}))
        .send().await?.error_for_status()?.json().await?;
    let admin_token = admin["access_token"].as_str().expect("admin token");
    assert_eq!(
        client
            .get(format!("{base}/v1/towers"))
            .bearer_auth(admin_token)
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    exercise_diagnostics(&client, base, access, url).await?;
    Ok(())
}

async fn exercise_diagnostics(
    client: &reqwest::Client,
    base: &str,
    access: &str,
    url: &str,
) -> Result<()> {
    let url = url.to_owned();
    tokio::task::spawn_blocking(move || -> Result<()> {
        let tls = MakeTlsConnector::new(native_tls::TlsConnector::builder().build()?);
        let mut connection = postgres::Client::connect(&url, tls)?;
        connection.execute(
            "INSERT INTO server_settings(key,value) VALUES ('debug_upload_enabled','1')",
            &[],
        )?;
        Ok(())
    })
    .await??;
    assert_eq!(
        client
            .put(format!("{base}/v1/privacy/consents/diagnostics"))
            .bearer_auth(access)
            .json(&serde_json::json!({"notice_version":"v1"}))
            .send()
            .await?
            .status(),
        StatusCode::NO_CONTENT
    );
    let diagnostic = client
        .post(format!("{base}/v1/debug/sessions"))
        .bearer_auth(access)
        .json(&serde_json::json!({"client_id":"pg-test","context":{"app_version":"test"}}))
        .send()
        .await?;
    assert_eq!(diagnostic.status(), StatusCode::OK);
    let created: serde_json::Value = diagnostic.json().await?;
    let session_id = created["id"].as_str().expect("debug session id");
    let batch = client
        .put(format!("{base}/v1/debug/sessions/{session_id}/batches/0"))
        .bearer_auth(access)
        .json(&serde_json::json!({"entries":[{"kind":"event","elapsed_ms":1,"line":"X,1"}]}))
        .send()
        .await?;
    assert_eq!(batch.status(), StatusCode::OK);
    let finished = client
        .post(format!("{base}/v1/debug/sessions/{session_id}/finish"))
        .bearer_auth(access)
        .json(&serde_json::json!({"next_seq":1}))
        .send()
        .await?;
    assert_eq!(finished.status(), StatusCode::OK);
    Ok(())
}

async fn exercise_browser(base: &str) -> Result<()> {
    let browser = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()?;
    let user_login = browser
        .post(format!("{base}/login"))
        .form(&[
            ("email", "driver@example.org"),
            ("password", "correct horse battery staple"),
        ])
        .send()
        .await?;
    assert_eq!(user_login.status(), StatusCode::SEE_OTHER);
    let user_cookie = user_login.headers()["set-cookie"]
        .to_str()?
        .split(';')
        .next()
        .expect("cookie")
        .to_owned();
    let panel = browser
        .get(format!("{base}/account"))
        .header("cookie", &user_cookie)
        .send()
        .await?;
    assert_eq!(panel.status(), StatusCode::OK, "{}", panel.text().await?);
    let html = browser
        .get(format!("{base}/account"))
        .header("cookie", &user_cookie)
        .send()
        .await?
        .text()
        .await?;
    let csrf = html
        .split("name=\"csrf\" value=\"")
        .nth(1)
        .expect("csrf")
        .split('"')
        .next()
        .expect("csrf value");
    let deleted = browser
        .post(format!("{base}/account/contributions/delete"))
        .header("cookie", &user_cookie)
        .form(&[
            ("csrf", csrf),
            ("radio", "LTE"),
            ("mcc", "255"),
            ("mnc", "1"),
            ("area", "100"),
            ("cid", "200"),
            ("device", "account:2:device-aaaa-1111"),
        ])
        .send()
        .await?;
    assert_eq!(
        deleted.status(),
        StatusCode::SEE_OTHER,
        "{}",
        deleted.text().await?
    );
    let all_deleted = browser
        .post(format!("{base}/account/contributions/delete-all"))
        .header("cookie", &user_cookie)
        .form(&[("csrf", csrf), ("confirm", "DELETE")])
        .send()
        .await?;
    assert_eq!(all_deleted.status(), StatusCode::SEE_OTHER);
    let admin_login = browser
        .post(format!("{base}/login"))
        .form(&[
            ("email", "admin@example.org"),
            ("password", "correct horse battery staple"),
        ])
        .send()
        .await?;
    let admin_cookie = admin_login.headers()["set-cookie"]
        .to_str()?
        .split(';')
        .next()
        .expect("cookie");
    let dashboard = browser
        .get(format!("{base}/admin"))
        .header("cookie", admin_cookie)
        .send()
        .await?;
    assert_eq!(
        dashboard.status(),
        StatusCode::OK,
        "{}",
        dashboard.text().await?
    );
    Ok(())
}

async fn exercise_admin_mutations(base: &str) -> Result<()> {
    let client = reqwest::Client::new();
    let admin: serde_json::Value = client.post(format!("{base}/v1/auth/login"))
        .json(&serde_json::json!({"email":"admin@example.org","password":"correct horse battery staple"}))
        .send().await?.error_for_status()?.json().await?;
    let token = admin["access_token"].as_str().expect("admin token");
    let tower = format!("{base}/v1/towers/LTE/255/1/100/200");
    let corrected = client
        .put(&tower)
        .bearer_auth(token)
        .json(&serde_json::json!({"lat":50.45,"lon":30.52,"range_m":500.0,"samples":10}))
        .send()
        .await?;
    assert_eq!(corrected.status(), StatusCode::OK);
    let hidden = client
        .post(format!("{tower}/quarantine"))
        .bearer_auth(token)
        .json(&serde_json::json!({"quarantined":true}))
        .send()
        .await?;
    assert_eq!(hidden.status(), StatusCode::NO_CONTENT);
    let removed = client.delete(&tower).bearer_auth(token).send().await?;
    assert_eq!(removed.status(), StatusCode::NO_CONTENT);
    Ok(())
}

async fn exercise_paged_public_exports(base: &str, url: &str) -> Result<()> {
    let url = url.to_owned();
    tokio::task::spawn_blocking(move || -> Result<()> {
        let mut connection = postgres_client(&url)?;
        connection.batch_execute("INSERT INTO consensus(radio,mcc,mnc,area,cid,lat,lon,range_m,samples,devices,seeded,updated_s)
            SELECT 'LTE',255,1,100,1000 + id,50.45,30.52,500.0,3,2,1,100 FROM generate_series(1,600) AS id;
            INSERT INTO tower_removals(radio,mcc,mnc,area,cid,updated_s)
            SELECT 'LTE',255,1,100,1000 + id,100 FROM generate_series(1,600) AS id;")?;
        Ok(())
    }).await??;
    let client = reqwest::Client::new();
    let compressed = client
        .get(format!("{base}/v1/cells.csv.gz?mcc=255&since=0"))
        .send()
        .await?
        .error_for_status()?
        .bytes()
        .await?;
    let mut csv = String::new();
    flate2::read::GzDecoder::new(compressed.as_ref()).read_to_string(&mut csv)?;
    assert!(csv.lines().count() >= 601);
    let removals = client
        .get(format!("{base}/v1/cells/removals.csv?mcc=255&since=0"))
        .send()
        .await?
        .error_for_status()?
        .text()
        .await?;
    assert!(removals.lines().count() >= 601);
    Ok(())
}

async fn exercise_account_sync(base: &str) -> Result<()> {
    use serde_json::json;
    let client = reqwest::Client::new();
    let login: serde_json::Value = client
        .post(format!("{base}/v1/auth/login"))
        .json(&json!({"email":"driver@example.org","password":"correct horse battery staple"}))
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    let token = login["access_token"].as_str().expect("token");
    let consent = format!("{base}/v1/privacy/consents/account_sync");
    client
        .put(&consent)
        .bearer_auth(token)
        .json(&json!({"notice_version":"v1"}))
        .send()
        .await?
        .error_for_status()?;
    let endpoint = format!("{base}/v1/account-sync");
    let entry = json!({"kind":"setting","key":"language","revision":0,"value":"uk"});
    let update = json!({"version":1,"generation":0,"changes":[entry]});
    let saved: serde_json::Value = client
        .put(&endpoint)
        .bearer_auth(token)
        .json(&update)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    assert_eq!(saved["entries"][0]["value"], "uk");
    // One prepared upsert handles both a tombstone and JSON without retaining old parameters.
    let mixed = json!({"version":1,"generation":0,"changes":[
        {"kind":"setting","key":"language","revision":0,"value":"en"},
        {"kind":"bookmark","key":"deleted","revision":0,"value":null},
        {"kind":"bookmark","key":"place","revision":0,"value":{"type":"place","name":"Synthetic","endpoint":{"point":{"lat":50,"lon":30},"label":null}}}
    ]});
    for _ in 0..2 {
        let result: serde_json::Value = client
            .put(&endpoint)
            .bearer_auth(token)
            .json(&mixed)
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        assert_eq!(result["conflicts"], json!(["setting:language"]));
        assert_eq!(result["entries"].as_array().unwrap().len(), 3);
        assert!(
            result["entries"]
                .as_array()
                .unwrap()
                .iter()
                .all(|entry| entry["revision"] == 1)
        );
    }

    client
        .delete(&consent)
        .bearer_auth(token)
        .send()
        .await?
        .error_for_status()?;
    let cleared: serde_json::Value = client
        .get(&endpoint)
        .bearer_auth(token)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    assert_eq!(cleared["enabled"], false);
    assert_eq!(cleared["generation"], 1);
    assert_eq!(cleared["entries"], json!([]));
    client
        .put(&consent)
        .bearer_auth(token)
        .json(&json!({"notice_version":"v1"}))
        .send()
        .await?
        .error_for_status()?;
    assert_eq!(
        client
            .put(&endpoint)
            .bearer_auth(token)
            .json(&mixed)
            .send()
            .await?
            .status(),
        StatusCode::CONFLICT
    );

    Ok(())
}
