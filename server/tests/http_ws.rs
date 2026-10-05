use anyhow::Result;
use futures_util::StreamExt;
use imu_nav_cell_server::{
    AppState, CellKey, CellStore, CellTower, Consensus, Policy, Radio, ServerConfig, create_admin,
    decode_towers, encode_towers, router,
};
use reqwest::StatusCode;
use std::net::{Ipv4Addr, SocketAddr};
use tempfile::NamedTempFile;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;

struct TestServer {
    base_url: String,
    task: tokio::task::JoinHandle<()>,
    database: NamedTempFile,
    token: String,
}

impl Drop for TestServer {
    fn drop(&mut self) {
        self.task.abort();
    }
}

async fn start_server() -> Result<TestServer> {
    start_server_with_policy(Policy::default()).await
}

async fn start_server_with_policy(policy: Policy) -> Result<TestServer> {
    let database = NamedTempFile::new()?;
    let store = CellStore::open(database.path())?;
    create_admin(&store, "admin@example.org", "correct horse battery staple")?;
    let state = AppState::new(
        store,
        ServerConfig {
            mail: None,
            policy,
            trust_proxy: false,
        },
    )?;
    let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    let address = listener.local_addr()?;
    let task = tokio::spawn(async move {
        axum::serve(
            listener,
            router(state).into_make_service_with_connect_info::<SocketAddr>(),
        )
        .await
        .expect("test server");
    });
    let base_url = format!("http://{address}");
    let login = reqwest::Client::new().post(format!("{base_url}/v1/auth/login"))
        .json(&serde_json::json!({"email":"admin@example.org","password":"correct horse battery staple"})).send().await?;
    let token = login.json::<serde_json::Value>().await?["access_token"]
        .as_str()
        .unwrap()
        .to_owned();
    Ok(TestServer {
        base_url,
        task,
        database,
        token,
    })
}

fn tower(lat: f64) -> CellTower {
    CellTower {
        key: CellKey {
            radio: Radio::Lte,
            mcc: 255,
            mnc: 1,
            area: 1864,
            cid: 42,
        },
        lat,
        lon: 30.5,
        range_m: 500.0,
        samples: 4,
    }
}

fn upload_body(tower: CellTower) -> Result<Vec<u8>> {
    Ok(encode_towers(&[Consensus {
        tower,
        devices: 2,
        seeded: false,
        updated_s: 1,
    }])?)
}

fn delete_form<'a>(token: &'a str, device: &'a str) -> [(&'static str, &'a str); 7] {
    [
        ("csrf", token),
        ("radio", "LTE"),
        ("mcc", "255"),
        ("mnc", "1"),
        ("area", "1864"),
        ("cid", "42"),
        ("device", device),
    ]
}

async fn assert_account_rejections(server: &TestServer, client: &reqwest::Client) -> Result<()> {
    assert_eq!(
        client
            .post(format!("{}/v1/auth/login", server.base_url))
            .json(&serde_json::json!({"email":"user@example.org","password":"wrong password"}))
            .send()
            .await?
            .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        client
            .post(format!(
                "{}/v1/auth/password-reset/request",
                server.base_url
            ))
            .json(&serde_json::json!({"email":"user@example.org"}))
            .send()
            .await?
            .status(),
        StatusCode::SERVICE_UNAVAILABLE
    );
    Ok(())
}

#[tokio::test]
async fn account_registration_refresh_and_logout_gate_uploads() -> Result<()> {
    let server = start_server().await?;
    let client = reqwest::Client::new();
    let register = || {
        client
            .post(format!("{}/v1/auth/register", server.base_url))
            .json(&serde_json::json!({"email":"user@example.org","password":"long safe password"}))
    };
    let response = register().send().await?;
    assert_eq!(response.status(), StatusCode::OK);
    let session: serde_json::Value = response.json().await?;
    let access = session["access_token"].as_str().unwrap();
    let refresh = session["refresh_token"].as_str().unwrap();
    assert_eq!(register().send().await?.status(), StatusCode::CONFLICT);
    assert_account_rejections(&server, &client).await?;
    assert_eq!(
        client
            .get(format!("{}/v1/auth/me", server.base_url))
            .bearer_auth(access)
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        client
            .get(format!("{}/v1/towers", server.base_url))
            .bearer_auth(access)
            .send()
            .await?
            .status(),
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        client
            .post(format!("{}/v1/cells", server.base_url))
            .bearer_auth("secret")
            .header("x-device-id", "device-aaaa")
            .body(upload_body(tower(50.4))?)
            .send()
            .await?
            .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        client
            .post(format!("{}/v1/cells", server.base_url))
            .bearer_auth(access)
            .header("x-device-id", "device-aaaa")
            .body(upload_body(tower(50.4))?)
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    let rotated: serde_json::Value = client
        .post(format!("{}/v1/auth/refresh", server.base_url))
        .json(&serde_json::json!({"refresh_token":refresh}))
        .send()
        .await?
        .json()
        .await?;
    assert_eq!(
        client
            .post(format!("{}/v1/auth/refresh", server.base_url))
            .json(&serde_json::json!({"refresh_token":refresh}))
            .send()
            .await?
            .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        client
            .post(format!("{}/v1/auth/logout", server.base_url))
            .json(&serde_json::json!({"refresh_token":rotated["refresh_token"]}))
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        client
            .get(format!("{}/v1/auth/me", server.base_url))
            .bearer_auth(rotated["access_token"].as_str().unwrap())
            .send()
            .await?
            .status(),
        StatusCode::UNAUTHORIZED
    );
    Ok(())
}

#[tokio::test]
// This end-to-end flow keeps browser session, ownership, consensus, and logout checks together.
#[allow(clippy::too_many_lines)]
async fn browser_account_pages_show_and_delete_only_owned_contributions() -> Result<()> {
    let server = start_server().await?;
    let browser = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()?;
    assert_eq!(
        browser
            .get(format!("{}/login", server.base_url))
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        browser
            .get(format!("{}/signup", server.base_url))
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        browser
            .get(format!("{}/account", server.base_url))
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );

    let first_signup = browser
        .post(format!("{}/signup", server.base_url))
        .form(&[
            ("email", "first@example.org"),
            ("password", "first long password"),
        ])
        .send()
        .await?;
    assert_eq!(first_signup.status(), StatusCode::SEE_OTHER);
    let first_cookie = first_signup.headers()["set-cookie"]
        .to_str()?
        .split(';')
        .next()
        .unwrap()
        .to_owned();
    assert!(
        first_signup.headers()["set-cookie"]
            .to_str()?
            .contains("HttpOnly; SameSite=Strict")
    );
    let second_signup = browser
        .post(format!("{}/signup", server.base_url))
        .form(&[
            ("email", "second@example.org"),
            ("password", "second long password"),
        ])
        .send()
        .await?;
    assert_eq!(second_signup.status(), StatusCode::SEE_OTHER);
    let second_cookie = second_signup.headers()["set-cookie"]
        .to_str()?
        .split(';')
        .next()
        .unwrap()
        .to_owned();
    let first_login: serde_json::Value = browser
        .post(format!("{}/v1/auth/login", server.base_url))
        .json(&serde_json::json!({"email":"first@example.org","password":"first long password"}))
        .send()
        .await?
        .json()
        .await?;
    let second_login: serde_json::Value = browser
        .post(format!("{}/v1/auth/login", server.base_url))
        .json(&serde_json::json!({"email":"second@example.org","password":"second long password"}))
        .send()
        .await?
        .json()
        .await?;
    let first_device = format!("account:{}:device-first", first_login["account"]["id"]);
    let second_device = format!("account:{}:device-second", second_login["account"]["id"]);
    for (session, device, latitude) in [
        (&first_login, "device-first", 50.400),
        (&second_login, "device-second", 50.401),
    ] {
        let upload = browser
            .post(format!("{}/v1/cells", server.base_url))
            .bearer_auth(session["access_token"].as_str().unwrap())
            .header("x-device-id", device)
            .body(upload_body(tower(latitude))?)
            .send()
            .await?;
        assert_eq!(upload.status(), StatusCode::OK);
    }
    let panel = browser
        .get(format!("{}/account", server.base_url))
        .header("cookie", &first_cookie)
        .send()
        .await?;
    assert_eq!(panel.status(), StatusCode::OK);
    assert_eq!(panel.headers()["cache-control"], "no-store");
    let body = panel.text().await?;
    assert!(body.contains(&first_device));
    assert!(!body.contains(&second_device));
    let csrf = body
        .split("name=\"csrf\" value=\"")
        .nth(1)
        .unwrap()
        .split('"')
        .next()
        .unwrap()
        .to_owned();
    assert_eq!(
        browser
            .post(format!("{}/account/contributions/delete", server.base_url))
            .header("cookie", &first_cookie)
            .form(&delete_form("wrong", &first_device))
            .send()
            .await?
            .status(),
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        browser
            .post(format!("{}/account/contributions/delete", server.base_url))
            .header("cookie", &first_cookie)
            .form(&delete_form(&csrf, &second_device))
            .send()
            .await?
            .status(),
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        browser
            .post(format!("{}/account/contributions/delete", server.base_url))
            .header("cookie", &first_cookie)
            .form(&delete_form(&csrf, &first_device))
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    let store = CellStore::open(server.database.path())?;
    assert_eq!(store.consensus(&tower(50.4).key)?.unwrap().devices, 1);
    assert!(
        browser
            .get(format!("{}/account", server.base_url))
            .header("cookie", &second_cookie)
            .send()
            .await?
            .text()
            .await?
            .contains(&second_device)
    );

    assert_eq!(
        browser
            .post(format!("{}/v1/cells", server.base_url))
            .bearer_auth(first_login["access_token"].as_str().unwrap())
            .header("x-device-id", "device-first")
            .body(upload_body(tower(50.400))?)
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        browser
            .post(format!(
                "{}/account/contributions/delete-all",
                server.base_url
            ))
            .header("cookie", &first_cookie)
            .form(&[("csrf", csrf.as_str())])
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    assert_eq!(store.consensus(&tower(50.4).key)?.unwrap().devices, 1);
    let first_panel = browser
        .get(format!("{}/account", server.base_url))
        .header("cookie", &first_cookie)
        .send()
        .await?
        .text()
        .await?;
    assert!(first_panel.contains("No observations have been uploaded"));
    assert_eq!(
        browser
            .post(format!("{}/account/logout", server.base_url))
            .header("cookie", &first_cookie)
            .form(&[("csrf", csrf.as_str())])
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    assert_eq!(
        browser
            .get(format!("{}/account", server.base_url))
            .header("cookie", &first_cookie)
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    Ok(())
}

#[tokio::test]
async fn browser_forms_validate_credentials_and_sign_in() -> Result<()> {
    let server = start_server().await?;
    let browser = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()?;
    let signup = format!("{}/signup", server.base_url);
    let login = format!("{}/login", server.base_url);
    assert_eq!(
        browser
            .post(&signup)
            .form(&[("email", "invalid"), ("password", "long password")])
            .send()
            .await?
            .status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        browser
            .post(&signup)
            .form(&[("email", "user@example.org"), ("password", "short")])
            .send()
            .await?
            .status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        browser
            .post(&signup)
            .form(&[("email", "user@example.org"), ("password", "long password")])
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    assert_eq!(
        browser
            .post(&signup)
            .form(&[("email", "user@example.org"), ("password", "long password")])
            .send()
            .await?
            .status(),
        StatusCode::CONFLICT
    );
    assert_eq!(
        browser
            .post(&login)
            .form(&[("email", "invalid"), ("password", "long password")])
            .send()
            .await?
            .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        browser
            .post(&login)
            .form(&[
                ("email", "user@example.org"),
                ("password", "wrong password")
            ])
            .send()
            .await?
            .status(),
        StatusCode::UNAUTHORIZED
    );
    let signed_in = browser
        .post(&login)
        .form(&[("email", "USER@example.org"), ("password", "long password")])
        .send()
        .await?;
    assert_eq!(signed_in.status(), StatusCode::SEE_OTHER);
    let cookie = signed_in.headers()["set-cookie"]
        .to_str()?
        .split(';')
        .next()
        .unwrap();
    let account = browser
        .get(format!("{}/account", server.base_url))
        .header("cookie", cookie)
        .send()
        .await?;
    assert_eq!(account.status(), StatusCode::OK);
    assert!(account.text().await?.contains("user@example.org"));
    Ok(())
}

#[tokio::test]
async fn android_protocol_and_websocket_events_stay_compatible() -> Result<()> {
    let server = start_server().await?;
    let client = reqwest::Client::new();
    let ws_url = server.base_url.replace("http://", "ws://") + "/v1/events";
    let mut request = ws_url.into_client_request()?;
    request.headers_mut().insert(
        "authorization",
        HeaderValue::from_str(&format!("Bearer {}", server.token))?,
    );
    let (mut websocket, _) = tokio_tungstenite::connect_async(request).await?;
    let ready = websocket.next().await.expect("ready event")?;
    assert!(ready.to_text()?.contains("\"type\":\"ready\""));

    for (upload_index, (device, lat)) in
        [("device-aaaa-1111", 50.400), ("device-bbbb-2222", 50.402)]
            .into_iter()
            .enumerate()
    {
        let response = client
            .post(format!("{}/v1/cells", server.base_url))
            .bearer_auth(&server.token)
            .header("x-device-id", device)
            .header("content-encoding", "gzip")
            .body(upload_body(tower(lat))?)
            .send()
            .await?;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.json::<serde_json::Value>().await?["accepted"], 1);
        if upload_index == 0 {
            let response = client
                .get(format!(
                    "{}/v1/cells.csv.gz?mcc=255&since=0",
                    server.base_url
                ))
                .send()
                .await?;
            assert!(decode_towers(&response.bytes().await?, 10)?.is_empty());

            let response = client
                .get(format!("{}/v1/towers?mcc=255", server.base_url))
                .bearer_auth(&server.token)
                .send()
                .await?;
            assert_eq!(
                response.json::<serde_json::Value>().await?["towers"]
                    .as_array()
                    .map(Vec::len),
                Some(1),
                "management includes pending consensus"
            );
        }
    }

    let event = websocket.next().await.expect("tower event")?;
    assert!(event.to_text()?.contains("\"type\":\"tower_upserted\""));
    let response = client
        .get(format!(
            "{}/v1/cells.csv.gz?mcc=255&since=0",
            server.base_url
        ))
        .send()
        .await?;
    assert_eq!(response.status(), StatusCode::OK);
    let towers = decode_towers(&response.bytes().await?, 10)?;
    assert_eq!(towers.len(), 1);
    assert!((towers[0].lat - 50.401).abs() < 0.000_001);

    websocket.close(None).await?;
    Ok(())
}

#[tokio::test]
async fn management_api_is_authenticated_and_broadcasts_deletes() -> Result<()> {
    let server = start_server().await?;
    let client = reqwest::Client::new();
    let path = "/v1/towers/LTE/255/1/1864/99";
    assert_eq!(
        client
            .get(format!("{}{}", server.base_url, path))
            .send()
            .await?
            .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        client
            .get(format!("{}/v1/towers", server.base_url))
            .send()
            .await?
            .status(),
        StatusCode::UNAUTHORIZED
    );

    let response = client
        .put(format!("{}{}", server.base_url, path))
        .bearer_auth(&server.token)
        .json(&serde_json::json!({"lat": 50.45, "lon": 30.52, "range_m": 700.0, "samples": 20}))
        .send()
        .await?;
    assert_eq!(response.status(), StatusCode::OK);
    let response = client
        .get(format!("{}{}", server.base_url, path))
        .bearer_auth(&server.token)
        .send()
        .await?;
    assert_eq!(response.status(), StatusCode::OK);
    let response = client
        .get(format!("{}/v1/towers", server.base_url))
        .bearer_auth(&server.token)
        .send()
        .await?;
    assert_eq!(
        response.json::<serde_json::Value>().await?["towers"]
            .as_array()
            .map(Vec::len),
        Some(1)
    );

    let response = client
        .delete(format!("{}{}", server.base_url, path))
        .bearer_auth(&server.token)
        .send()
        .await?;
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    let response = client
        .get(format!("{}/v1/towers", server.base_url))
        .bearer_auth(&server.token)
        .send()
        .await?;
    assert_eq!(
        response.json::<serde_json::Value>().await?["towers"]
            .as_array()
            .map(Vec::len),
        Some(0)
    );
    Ok(())
}

#[tokio::test]
async fn read_only_admin_pages_use_browser_auth_and_render_towers() -> Result<()> {
    let server = start_server().await?;
    let client = reqwest::Client::new();

    let unauthorized = client
        .get(format!("{}/admin", server.base_url))
        .send()
        .await?;
    assert_eq!(unauthorized.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(
        unauthorized
            .headers()
            .get("www-authenticate")
            .and_then(|value| value.to_str().ok()),
        Some("Basic realm=\"IMU Nav admin\", charset=\"UTF-8\"")
    );

    let path = "/v1/towers/LTE/255/1/1864/99";
    let response = client
        .put(format!("{}{}", server.base_url, path))
        .bearer_auth(&server.token)
        .json(&serde_json::json!({"lat": 50.45, "lon": 30.52, "range_m": 700.0, "samples": 20}))
        .send()
        .await?;
    assert_eq!(response.status(), StatusCode::OK);

    let dashboard = client
        .get(format!("{}/admin?mcc=255&limit=10", server.base_url))
        .basic_auth("admin@example.org", Some("correct horse battery staple"))
        .send()
        .await?;
    assert_eq!(dashboard.status(), StatusCode::OK);
    assert_eq!(
        dashboard
            .headers()
            .get("content-security-policy")
            .and_then(|value| value.to_str().ok()),
        Some(
            "default-src 'none'; style-src https://cdn.jsdelivr.net; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"
        )
    );
    let dashboard_html = dashboard.text().await?;
    assert!(dashboard_html.contains("Management dashboard"));
    assert!(dashboard_html.contains("bootstrap@5.3.8"));
    assert!(dashboard_html.contains("/admin/towers/LTE/255/1/1864/99"));
    assert!(dashboard_html.contains("Read only"));
    assert!(!dashboard_html.contains("Delete"));

    let detail = client
        .get(format!(
            "{}/admin/towers/LTE/255/1/1864/99",
            server.base_url
        ))
        .basic_auth("admin@example.org", Some("correct horse battery staple"))
        .send()
        .await?;
    assert_eq!(detail.status(), StatusCode::OK);
    let detail_html = detail.text().await?;
    assert!(detail_html.contains("Tower LTE 255-1 / 1864 / 99"));
    assert!(detail_html.contains("50.4500000"));
    assert!(!detail_html.contains("Delete"));

    let invalid_filter = client
        .get(format!("{}/admin?mcc=invalid", server.base_url))
        .basic_auth("admin@example.org", Some("correct horse battery staple"))
        .send()
        .await?;
    assert_eq!(invalid_filter.status(), StatusCode::BAD_REQUEST);
    Ok(())
}

#[tokio::test]
async fn invalid_filters_keys_and_oversized_uploads_are_rejected() -> Result<()> {
    let policy = Policy {
        max_rows_per_upload: 1,
        ..Policy::default()
    };
    let server = start_server_with_policy(policy).await?;
    let client = reqwest::Client::new();

    let response = client
        .get(format!("{}/v1/towers?mcc=not-a-number", server.base_url))
        .bearer_auth(&server.token)
        .send()
        .await?;
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);

    let response = client
        .put(format!("{}/v1/towers/LTE/-1/1/1864/99", server.base_url))
        .bearer_auth(&server.token)
        .json(&serde_json::json!({"lat": 50.45, "lon": 30.52, "range_m": 700.0, "samples": 20}))
        .send()
        .await?;
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);

    let mut second_tower = tower(50.4);
    second_tower.key.cid = 43;
    let body = encode_towers(&[
        Consensus {
            tower: tower(50.4),
            devices: 1,
            seeded: false,
            updated_s: 1,
        },
        Consensus {
            tower: second_tower,
            devices: 1,
            seeded: false,
            updated_s: 1,
        },
    ])?;
    let response = client
        .post(format!("{}/v1/cells", server.base_url))
        .bearer_auth(&server.token)
        .header("x-device-id", "device-aaaa-1111")
        .body(body)
        .send()
        .await?;
    assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
    Ok(())
}

#[tokio::test]
async fn repeated_device_uploads_are_rate_limited() -> Result<()> {
    let policy = Policy {
        max_uploads_per_hour_per_device: 2,
        ..Policy::default()
    };
    let server = start_server_with_policy(policy).await?;
    let client = reqwest::Client::new();
    let body = upload_body(tower(50.4))?;

    for expected in [
        StatusCode::OK,
        StatusCode::OK,
        StatusCode::TOO_MANY_REQUESTS,
    ] {
        let response = client
            .post(format!("{}/v1/cells", server.base_url))
            .bearer_auth(&server.token)
            .header("x-device-id", "device-aaaa-1111")
            .body(body.clone())
            .send()
            .await?;
        assert_eq!(response.status(), expected);
    }
    Ok(())
}

#[tokio::test]
async fn health_bad_uploads_and_management_validation() -> Result<()> {
    let server = start_server().await?;
    let client = reqwest::Client::new();
    assert_eq!(
        client
            .get(format!("{}/health", server.base_url))
            .send()
            .await?
            .text()
            .await?,
        "ok 0\n"
    );
    let response = client
        .post(format!("{}/v1/cells", server.base_url))
        .bearer_auth(&server.token)
        .body(vec![0x1f, 0x8b, 0])
        .send()
        .await?;
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    assert_eq!(
        response.json::<serde_json::Value>().await?["message"],
        "BAD_BODY"
    );
    for path in [
        "/v1/towers?mcc=0",
        "/v1/towers?mcc=1000",
        "/v1/towers?mcc=bad",
        "/v1/towers/invalid/255/1/1/1",
        "/v1/towers/LTE/255/-1/1/1",
    ] {
        assert_eq!(
            client
                .get(format!("{}{path}", server.base_url))
                .bearer_auth(&server.token)
                .send()
                .await?
                .status(),
            StatusCode::BAD_REQUEST
        );
    }
    let url = format!("{}/v1/towers/LTE/255/1/1/1", server.base_url);
    assert_eq!(
        client
            .get(&url)
            .bearer_auth(&server.token)
            .send()
            .await?
            .status(),
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        client
            .delete(&url)
            .bearer_auth(&server.token)
            .send()
            .await?
            .status(),
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        client
            .put(&url)
            .bearer_auth(&server.token)
            .json(&serde_json::json!({"lat": 500, "lon": 30, "range_m": 100, "samples": 1}))
            .send()
            .await?
            .status(),
        StatusCode::BAD_REQUEST
    );
    Ok(())
}
