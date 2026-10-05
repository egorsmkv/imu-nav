use anyhow::Result;
use futures_util::StreamExt;
use imu_nav_cell_server::{
    AppState, CellKey, CellStore, CellTower, Consensus, MailConfig, Policy, Radio, ServerConfig,
    create_admin, decode_towers, encode_towers, router,
};
use reqwest::StatusCode;
use std::io::Read;
use std::net::{Ipv4Addr, SocketAddr};
use tempfile::NamedTempFile;
use tokio::io::AsyncWriteExt;
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
    start_server_with_mail(policy, None).await
}

async fn start_server_with_mail(policy: Policy, mail: Option<MailConfig>) -> Result<TestServer> {
    let database = NamedTempFile::new()?;
    let store = CellStore::open(database.path())?;
    create_admin(&store, "admin@example.org", "correct horse battery staple")?;
    let state = AppState::new(
        store,
        ServerConfig {
            mail,
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

#[tokio::test]
async fn browser_pages_serve_all_three_locales_under_their_csp() -> Result<()> {
    let server = start_server().await?;
    let client = reqwest::Client::new();
    let translations = client
        .get(format!("{}/web-locale.js", server.base_url))
        .send()
        .await?;
    assert_eq!(translations.status(), StatusCode::OK);
    assert!(
        translations.headers()["content-type"]
            .to_str()?
            .contains("text/javascript")
    );
    let translations = translations.text().await?;
    assert!(translations.contains("Українська"));
    assert!(translations.contains("Русский"));
    assert!(translations.contains("navigator.languages"));

    for path in [
        "/login",
        "/signup",
        "/forgot-password",
        "/data-usage",
        "/verify-email",
        "/reset-password",
    ] {
        let page = client
            .get(format!("{}{path}", server.base_url))
            .send()
            .await?;
        assert_eq!(page.status(), StatusCode::OK, "{path}");
        assert!(
            page.headers()["content-security-policy"]
                .to_str()?
                .contains("'self'"),
            "{path}"
        );
        assert!(
            page.text().await?.contains("src=\"/web-locale.js\""),
            "{path}"
        );
    }
    Ok(())
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
    let data_usage = browser
        .get(format!("{}/data-usage", server.base_url))
        .send()
        .await?;
    assert_eq!(data_usage.status(), StatusCode::OK);
    assert_eq!(data_usage.headers()["cache-control"], "no-store");
    let data_usage_html = data_usage.text().await?;
    assert!(data_usage_html.contains("<h1>Data Usage</h1>"));
    assert!(data_usage_html.contains("latest accepted observation for each device and tower"));
    assert!(
        data_usage_html
            .contains("Copies already downloaded or exported cannot be removed remotely")
    );
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
    assert!(body.contains("href=\"/data-usage\""));
    assert!(body.contains("type=\"datetime-local\" step=\"1\""));
    assert!(body.contains("Updated at or after (UTC)"));
    assert!(body.contains("<time datetime=\""));
    assert!(body.contains(" UTC</time>"));
    let future_filter = browser
        .get(format!(
            "{}/account?from_s=2099-01-01T00:00:00",
            server.base_url
        ))
        .header("cookie", &first_cookie)
        .send()
        .await?;
    assert_eq!(future_filter.status(), StatusCode::OK);
    let future_html = future_filter.text().await?;
    assert!(future_html.contains("0 matching observations"));
    assert!(future_html.contains("value=\"2099-01-01T00:00:00\""));
    let legacy_filter = browser
        .get(format!("{}/account?from_s=1", server.base_url))
        .header("cookie", &first_cookie)
        .send()
        .await?;
    assert_eq!(legacy_filter.status(), StatusCode::OK);
    let legacy_html = legacy_filter.text().await?;
    assert!(legacy_html.contains(&first_device));
    assert!(legacy_html.contains("value=\"1970-01-01T00:00:01\""));
    let invalid_filter = browser
        .get(format!(
            "{}/account?from_s=2024-02-30T00:00",
            server.base_url
        ))
        .header("cookie", &first_cookie)
        .send()
        .await?;
    assert_eq!(invalid_filter.status(), StatusCode::BAD_REQUEST);
    let export = browser
        .get(format!("{}/account/export?mcc=255", server.base_url))
        .header("cookie", &first_cookie)
        .send()
        .await?;
    assert_eq!(export.status(), StatusCode::OK);
    let mut csv = String::new();
    flate2::read::GzDecoder::new(export.bytes().await?.as_ref()).read_to_string(&mut csv)?;
    assert!(csv.contains(&first_device));
    assert!(!csv.contains(&second_device));
    let filtered_export = browser
        .get(format!(
            "{}/account/export?to_s=2000-01-01T00:00:00",
            server.base_url
        ))
        .header("cookie", &first_cookie)
        .send()
        .await?;
    assert_eq!(filtered_export.status(), StatusCode::OK);
    let mut filtered_csv = String::new();
    flate2::read::GzDecoder::new(filtered_export.bytes().await?.as_ref())
        .read_to_string(&mut filtered_csv)?;
    assert!(!filtered_csv.contains(&first_device));
    let extra_towers = (0..100)
        .map(|index| {
            let mut cell = tower(50.4);
            cell.key.cid = 1_000 + index;
            Consensus {
                tower: cell,
                devices: 2,
                seeded: false,
                updated_s: 1,
            }
        })
        .collect::<Vec<_>>();
    let extra_upload = browser
        .post(format!("{}/v1/cells", server.base_url))
        .bearer_auth(first_login["access_token"].as_str().unwrap())
        .header("x-device-id", "device-first")
        .body(encode_towers(&extra_towers)?)
        .send()
        .await?;
    assert_eq!(extra_upload.status(), StatusCode::OK);
    let first_page = browser
        .get(format!("{}/account?from_s=1", server.base_url))
        .header("cookie", &first_cookie)
        .send()
        .await?
        .text()
        .await?;
    assert!(first_page.contains("101 matching observations"));
    assert!(first_page.contains("Next page"));
    assert!(first_page.contains("name=\"from_s\" value=\"1970-01-01T00:00:01\""));
    let second_page = browser
        .get(format!(
            "{}/account?page=1&from_s=1970-01-01T00:00:01",
            server.base_url
        ))
        .header("cookie", &first_cookie)
        .send()
        .await?
        .text()
        .await?;
    assert!(second_page.contains("101 matching observations"));
    assert!(second_page.contains("Page 2"));
    assert!(second_page.contains("Previous page"));
    assert!(second_page.contains("name=\"from_s\" value=\"1970-01-01T00:00:01\""));
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

    let blocked_upload = browser
        .post(format!("{}/v1/cells", server.base_url))
        .bearer_auth(first_login["access_token"].as_str().unwrap())
        .header("x-device-id", "device-first")
        .body(upload_body(tower(50.400))?)
        .send()
        .await?;
    assert_eq!(blocked_upload.status(), StatusCode::OK);
    assert_eq!(
        blocked_upload.json::<serde_json::Value>().await?["rejected"],
        1
    );
    assert_eq!(
        browser
            .post(format!(
                "{}/account/contributions/delete-all",
                server.base_url
            ))
            .header("cookie", &first_cookie)
            .form(&[("csrf", csrf.as_str()), ("confirm", "DELETE")])
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    assert_eq!(store.consensus(&tower(50.4).key)?.unwrap().devices, 1);
    assert_eq!(
        browser
            .post(format!("{}/v1/cells", server.base_url))
            .bearer_auth(first_login["access_token"].as_str().unwrap())
            .header("x-device-id", "device-first")
            .body(upload_body(tower(50.400))?)
            .send()
            .await?
            .status(),
        StatusCode::FORBIDDEN
    );
    let first_panel = browser
        .get(format!("{}/account", server.base_url))
        .header("cookie", &first_cookie)
        .send()
        .await?
        .text()
        .await?;
    assert!(first_panel.contains("No observations match this page and filter"));
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
    assert_eq!(response.status(), StatusCode::CONFLICT);
    let response = client
        .post(format!("{}{}{}", server.base_url, path, "/quarantine"))
        .bearer_auth(&server.token)
        .json(&serde_json::json!({"quarantined":true}))
        .send()
        .await?;
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
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
async fn admin_pages_use_session_and_render_controls() -> Result<()> {
    let server = start_server().await?;
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()?;

    let unauthorized = client
        .get(format!("{}/admin", server.base_url))
        .send()
        .await?;
    assert_eq!(unauthorized.status(), StatusCode::SEE_OTHER);
    assert_eq!(
        unauthorized
            .headers()
            .get("location")
            .and_then(|value| value.to_str().ok()),
        Some("/login")
    );

    let login = client
        .post(format!("{}/login", server.base_url))
        .form(&[
            ("email", "admin@example.org"),
            ("password", "correct horse battery staple"),
        ])
        .send()
        .await?;
    assert_eq!(login.status(), StatusCode::SEE_OTHER);
    assert_eq!(login.headers()["location"], "/admin");
    let cookie = login.headers()["set-cookie"]
        .to_str()?
        .split(';')
        .next()
        .unwrap()
        .to_owned();

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
        .header("cookie", &cookie)
        .send()
        .await?;
    assert_eq!(dashboard.status(), StatusCode::OK);
    assert_eq!(
        dashboard
            .headers()
            .get("content-security-policy")
            .and_then(|value| value.to_str().ok()),
        Some(
            "default-src 'none'; style-src https://cdn.jsdelivr.net; script-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"
        )
    );
    let dashboard_html = dashboard.text().await?;
    assert!(dashboard_html.contains("Management dashboard"));
    assert!(dashboard_html.contains("href=\"/data-usage\""));
    assert!(dashboard_html.contains("bootstrap@5.3.8"));
    assert!(dashboard_html.contains("/admin/towers/LTE/255/1/1864/99"));
    assert!(dashboard_html.contains("Seed import and exports"));
    assert!(dashboard_html.contains("Save and recalculate"));
    assert!(dashboard_html.contains("name=\"status\""));
    assert!(dashboard_html.contains("Page 1 of matching towers"));
    assert!(dashboard_html.contains("Times are in UTC"));
    assert!(dashboard_html.contains("<time datetime=\""));
    let script = client
        .get(format!("{}/admin/admin.js", server.base_url))
        .header("cookie", &cookie)
        .send()
        .await?;
    assert_eq!(script.status(), StatusCode::OK);
    assert!(script.text().await?.contains("XMLHttpRequest"));
    let job = client
        .get(format!("{}/admin/jobs/status", server.base_url))
        .header("cookie", &cookie)
        .send()
        .await?;
    assert_eq!(job.status(), StatusCode::OK);
    assert!(job.json::<serde_json::Value>().await?["status"].is_string());
    let filtered = client
        .get(format!(
            "{}/admin?status=seeded&tower_page=2&limit=1",
            server.base_url
        ))
        .header("cookie", &cookie)
        .send()
        .await?;
    assert_eq!(filtered.status(), StatusCode::OK);
    assert!(filtered.text().await?.contains("Page 2"));

    let detail = client
        .get(format!(
            "{}/admin/towers/LTE/255/1/1864/99",
            server.base_url
        ))
        .header("cookie", &cookie)
        .send()
        .await?;
    assert_eq!(detail.status(), StatusCode::OK);
    let detail_html = detail.text().await?;
    assert!(detail_html.contains("Tower LTE 255-1 / 1864 / 99"));
    assert!(detail_html.contains("50.4500000"));
    assert!(detail_html.contains("Manual correction"));
    assert!(detail_html.contains("Quarantine tower"));
    assert!(detail_html.contains("<time datetime=\""));
    let observation_page = client
        .get(format!(
            "{}/admin/towers/LTE/255/1/1864/99?page=2",
            server.base_url
        ))
        .header("cookie", &cookie)
        .send()
        .await?;
    assert!(
        observation_page
            .text()
            .await?
            .contains("Observation page 2")
    );
    let filtered_observations = client
        .get(format!(
            "{}/admin/towers/LTE/255/1/1864/99?device=missing",
            server.base_url
        ))
        .header("cookie", &cookie)
        .send()
        .await?
        .text()
        .await?;
    assert!(!filtered_observations.contains("value=\"manual\""));

    let invalid_csrf = client
        .post(format!(
            "{}/admin/towers/LTE/255/1/1864/99/quarantine",
            server.base_url
        ))
        .header("cookie", &cookie)
        .form(&[("csrf", "invalid")])
        .send()
        .await?;
    assert_eq!(invalid_csrf.status(), StatusCode::FORBIDDEN);
    let csrf = dashboard_html
        .split("name=\"csrf\" value=\"")
        .nth(1)
        .unwrap()
        .split('"')
        .next()
        .unwrap();
    let correction = client
        .post(format!(
            "{}/admin/towers/LTE/255/1/1864/99/correct",
            server.base_url
        ))
        .header("cookie", &cookie)
        .form(&[
            ("csrf", csrf),
            ("lat", "50.46"),
            ("lon", "30.52"),
            ("range_m", "700"),
            ("samples", "20"),
        ])
        .send()
        .await?;
    assert_eq!(correction.status(), StatusCode::SEE_OTHER);
    let seed_store = CellStore::open(server.database.path())?;
    let mut seeded = tower(50.45);
    seeded.key.cid = 99;
    seed_store.seed(&[seeded], 10, &Policy::default())?;
    let observation = client
        .post(format!(
            "{}/admin/towers/LTE/255/1/1864/99/observations/delete",
            server.base_url
        ))
        .header("cookie", &cookie)
        .form(&[("csrf", csrf), ("device", "manual")])
        .send()
        .await?;
    assert_eq!(observation.status(), StatusCode::SEE_OTHER);
    let quarantine = client
        .post(format!(
            "{}/admin/towers/LTE/255/1/1864/99/quarantine",
            server.base_url
        ))
        .header("cookie", &cookie)
        .form(&[("csrf", csrf)])
        .send()
        .await?;
    assert_eq!(quarantine.status(), StatusCode::SEE_OTHER);
    let removals = client
        .get(format!(
            "{}/v1/cells/removals.csv?mcc=255&since=0",
            server.base_url
        ))
        .send()
        .await?;
    assert!(removals.headers().get("x-cell-sync-time").is_some());
    let removals = removals.text().await?;
    assert!(removals.contains("LTE,255,1,1864,99"));
    let hidden = client
        .get(format!("{}/v1/cells.csv.gz", server.base_url))
        .send()
        .await?;
    assert!(decode_towers(&hidden.bytes().await?, 10)?.is_empty());
    let restore = client
        .post(format!(
            "{}/admin/towers/LTE/255/1/1864/99/restore",
            server.base_url
        ))
        .header("cookie", &cookie)
        .form(&[("csrf", csrf)])
        .send()
        .await?;
    assert_eq!(restore.status(), StatusCode::SEE_OTHER);
    let removals = client
        .get(format!(
            "{}/v1/cells/removals.csv?mcc=255&since=0",
            server.base_url
        ))
        .send()
        .await?
        .text()
        .await?;
    assert!(!removals.contains("LTE,255,1,1864,99"));
    let visible = client
        .get(format!("{}/v1/cells.csv.gz", server.base_url))
        .send()
        .await?;
    assert_eq!(decode_towers(&visible.bytes().await?, 10)?.len(), 1);
    let again = client
        .post(format!(
            "{}/admin/towers/LTE/255/1/1864/99/quarantine",
            server.base_url
        ))
        .header("cookie", &cookie)
        .form(&[("csrf", csrf)])
        .send()
        .await?;
    assert_eq!(again.status(), StatusCode::SEE_OTHER);
    let premature = client
        .post(format!(
            "{}/admin/towers/LTE/255/1/1864/99/delete",
            server.base_url
        ))
        .header("cookie", &cookie)
        .form(&[("csrf", csrf), ("confirm", "wrong")])
        .send()
        .await?;
    assert_eq!(premature.status(), StatusCode::BAD_REQUEST);
    let deleted = client
        .post(format!(
            "{}/admin/towers/LTE/255/1/1864/99/delete",
            server.base_url
        ))
        .header("cookie", &cookie)
        .form(&[("csrf", csrf), ("confirm", "DELETE")])
        .send()
        .await?;
    assert_eq!(deleted.status(), StatusCode::SEE_OTHER);
    let self_suspend = client
        .post(format!("{}/admin/accounts/1/suspend", server.base_url))
        .header("cookie", &cookie)
        .form(&[("csrf", csrf)])
        .send()
        .await?;
    assert_eq!(self_suspend.status(), StatusCode::CONFLICT);
    let lookup = client
        .get(format!("{}/admin?key=LTE:255:1:1864:99", server.base_url))
        .header("cookie", &cookie)
        .send()
        .await?;
    assert_eq!(lookup.status(), StatusCode::SEE_OTHER);

    let invalid_filter = client
        .get(format!("{}/admin?mcc=invalid", server.base_url))
        .header("cookie", &cookie)
        .send()
        .await?;
    assert_eq!(invalid_filter.status(), StatusCode::BAD_REQUEST);
    let invalid_status = client
        .get(format!("{}/admin?status=unknown", server.base_url))
        .header("cookie", &cookie)
        .send()
        .await?;
    assert_eq!(invalid_status.status(), StatusCode::BAD_REQUEST);
    let account_page = client
        .get(format!(
            "{}/admin?account_page=2&audit_page=2",
            server.base_url
        ))
        .header("cookie", &cookie)
        .send()
        .await?;
    assert_eq!(account_page.status(), StatusCode::OK);
    assert!(account_page.text().await?.contains("Page 2"));
    let audit_filter = client
        .get(format!(
            "{}/admin?audit_action=quarantine_tower",
            server.base_url
        ))
        .header("cookie", &cookie)
        .send()
        .await?
        .text()
        .await?;
    assert!(audit_filter.contains("<td>quarantine_tower</td>"));
    assert!(audit_filter.contains("<time datetime=\""));
    Ok(())
}

#[tokio::test]
async fn admin_policy_import_export_and_account_actions_work() -> Result<()> {
    let server = start_server().await?;
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()?;
    let login = client
        .post(format!("{}/login", server.base_url))
        .form(&[
            ("email", "admin@example.org"),
            ("password", "correct horse battery staple"),
        ])
        .send()
        .await?;
    let cookie = login.headers()["set-cookie"]
        .to_str()?
        .split(';')
        .next()
        .unwrap()
        .to_owned();
    let dashboard = client
        .get(format!("{}/admin", server.base_url))
        .header("cookie", &cookie)
        .send()
        .await?
        .text()
        .await?;
    let csrf = dashboard
        .split("name=\"csrf\" value=\"")
        .nth(1)
        .unwrap()
        .split('"')
        .next()
        .unwrap()
        .to_owned();

    let policy = [
        ("csrf", csrf.as_str()),
        ("max_samples_per_device", "50"),
        ("min_devices", "3"),
        ("outlier_min_m", "1000"),
        ("max_jump_m", "5000"),
        ("max_range_m", "50000"),
        ("max_rows_per_upload", "20000"),
        ("max_uploads_per_hour_per_device", "30"),
        ("max_uploads_per_hour_per_ip", "120"),
        ("max_devices_per_ip_per_day", "5"),
        ("ukraine_only", "on"),
    ];
    let response = client
        .post(format!("{}/admin/policy", server.base_url))
        .header("cookie", &cookie)
        .form(&policy)
        .send()
        .await?;
    assert_eq!(response.status(), StatusCode::SEE_OTHER);
    let store = CellStore::open(server.database.path())?;
    for _ in 0..100 {
        if store.stored_policy()?.is_some() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    }
    assert_eq!(store.stored_policy()?.unwrap().min_devices, 3);
    let mut completed = false;
    for _ in 0..100 {
        let page = client
            .get(format!("{}/admin", server.base_url))
            .header("cookie", &cookie)
            .send()
            .await?
            .text()
            .await?;
        if page.contains("policy recalculation: complete") {
            completed = true;
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    }
    assert!(completed);
    let invalid = [
        ("csrf", csrf.as_str()),
        ("max_samples_per_device", "50"),
        ("min_devices", "0"),
        ("outlier_min_m", "1000"),
        ("max_jump_m", "5000"),
        ("max_range_m", "50000"),
        ("max_rows_per_upload", "20000"),
        ("max_uploads_per_hour_per_device", "30"),
        ("max_uploads_per_hour_per_ip", "120"),
        ("max_devices_per_ip_per_day", "5"),
    ];
    let rejected = client
        .post(format!("{}/admin/policy", server.base_url))
        .header("cookie", &cookie)
        .form(&invalid)
        .send()
        .await?;
    assert_eq!(rejected.status(), StatusCode::BAD_REQUEST);

    let boundary = "imu-nav-test-boundary";
    let body = format!(
        "--{boundary}\r\nContent-Disposition: form-data; name=\"csrf\"\r\n\r\n{csrf}\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"seeds.csv\"\r\nContent-Type: text/csv\r\n\r\nLTE,255,1,1864,99,,30.52,50.45,700,20\ninvalid,row\n\r\n--{boundary}--\r\n"
    );
    let response = client
        .post(format!("{}/admin/import", server.base_url))
        .header("cookie", &cookie)
        .header(
            "content-type",
            format!("multipart/form-data; boundary={boundary}"),
        )
        .body(body)
        .send()
        .await?;
    assert_eq!(response.status(), StatusCode::SEE_OTHER);
    let mut report_job = None;
    for _ in 0..100 {
        let status = client
            .get(format!("{}/admin/jobs/status", server.base_url))
            .header("cookie", &cookie)
            .send()
            .await?
            .json::<serde_json::Value>()
            .await?;
        if status["status"] == "complete" {
            assert_eq!(status["rejected"], 1);
            report_job = status["id"].as_i64();
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    }
    let report_job = report_job.expect("import finished");
    let report = client
        .get(format!(
            "{}/admin/jobs/{report_job}/rejections.csv",
            server.base_url
        ))
        .header("cookie", &cookie)
        .send()
        .await?;
    assert_eq!(report.status(), StatusCode::OK);
    assert!(report.text().await?.contains("Invalid OpenCellID row"));
    for _ in 0..100 {
        if store
            .consensus(&CellKey {
                radio: Radio::Lte,
                mcc: 255,
                mnc: 1,
                area: 1864,
                cid: 99,
            })?
            .is_some()
        {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    }
    let export = client
        .get(format!("{}/admin/export/consensus", server.base_url))
        .header("cookie", &cookie)
        .send()
        .await?;
    assert_eq!(export.status(), StatusCode::OK);
    let mut decoded = String::new();
    let bytes = export.bytes().await?;
    std::io::Read::read_to_string(
        &mut flate2::read::GzDecoder::new(bytes.as_ref()),
        &mut decoded,
    )?;
    assert!(decoded.contains("LTE,255,1,1864,99"));
    let observations = client
        .get(format!("{}/admin/export/observations", server.base_url))
        .header("cookie", &cookie)
        .send()
        .await?;
    assert_eq!(observations.status(), StatusCode::OK);
    decoded.clear();
    let bytes = observations.bytes().await?;
    std::io::Read::read_to_string(
        &mut flate2::read::GzDecoder::new(bytes.as_ref()),
        &mut decoded,
    )?;
    assert!(decoded.contains("seed"));
    let cancel = client
        .post(format!("{}/admin/jobs/cancel", server.base_url))
        .header("cookie", &cookie)
        .form(&[("csrf", csrf.as_str())])
        .send()
        .await?;
    assert_eq!(cancel.status(), StatusCode::SEE_OTHER);

    let register = client.post(format!("{}/v1/auth/register", server.base_url))
        .json(&serde_json::json!({"email":"driver@example.org","password":"correct horse battery staple"}))
        .send().await?;
    let id = register.json::<serde_json::Value>().await?["account"]["id"]
        .as_i64()
        .unwrap();
    let suspend = client
        .post(format!("{}/admin/accounts/{id}/suspend", server.base_url))
        .header("cookie", &cookie)
        .form(&[("csrf", csrf.as_str())])
        .send()
        .await?;
    assert_eq!(suspend.status(), StatusCode::SEE_OTHER);
    let blocked = client.post(format!("{}/v1/auth/login", server.base_url))
        .json(&serde_json::json!({"email":"driver@example.org","password":"correct horse battery staple"}))
        .send().await?;
    assert_eq!(blocked.status(), StatusCode::UNAUTHORIZED);
    let restore = client
        .post(format!("{}/admin/accounts/{id}/restore", server.base_url))
        .header("cookie", &cookie)
        .form(&[("csrf", csrf.as_str())])
        .send()
        .await?;
    assert_eq!(restore.status(), StatusCode::SEE_OTHER);
    Ok(())
}

#[tokio::test]
async fn administrator_can_cancel_while_upload_is_staging() -> Result<()> {
    let server = start_server().await?;
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()?;
    let login = client
        .post(format!("{}/login", server.base_url))
        .form(&[
            ("email", "admin@example.org"),
            ("password", "correct horse battery staple"),
        ])
        .send()
        .await?;
    let cookie = login.headers()["set-cookie"]
        .to_str()?
        .split(';')
        .next()
        .unwrap()
        .to_owned();
    let dashboard = client
        .get(format!("{}/admin", server.base_url))
        .header("cookie", &cookie)
        .send()
        .await?
        .text()
        .await?;
    let csrf = dashboard
        .split("name=\"csrf\" value=\"")
        .nth(1)
        .unwrap()
        .split('"')
        .next()
        .unwrap();
    let host = server.base_url.trim_start_matches("http://");
    let mut socket = tokio::net::TcpStream::connect(host).await?;
    let boundary = "staging-cancel-test";
    let prefix = format!(
        "--{boundary}\r\nContent-Disposition: form-data; name=\"csrf\"\r\n\r\n{csrf}\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"slow.csv\"\r\nContent-Type: text/csv\r\n\r\n"
    );
    let head = format!(
        "POST /admin/import HTTP/1.1\r\nHost: {host}\r\nCookie: {cookie}\r\nContent-Type: multipart/form-data; boundary={boundary}\r\nContent-Length: {}\r\n\r\n",
        prefix.len() + 100_000
    );
    socket.write_all(head.as_bytes()).await?;
    socket.write_all(prefix.as_bytes()).await?;
    socket
        .write_all(b"LTE,255,1,1864,1,,30.5,50.4,500,5\n")
        .await?;
    let mut saw_upload = false;
    for _ in 0..100 {
        let status = client
            .get(format!("{}/admin/jobs/status", server.base_url))
            .header("cookie", &cookie)
            .send()
            .await?
            .json::<serde_json::Value>()
            .await?;
        if status["phase"] == "uploading" {
            saw_upload = true;
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    }
    assert!(saw_upload);
    let cancelled = client
        .post(format!("{}/admin/jobs/cancel", server.base_url))
        .header("cookie", &cookie)
        .form(&[("csrf", csrf)])
        .send()
        .await?;
    assert_eq!(cancelled.status(), StatusCode::SEE_OTHER);
    drop(socket);
    let mut finished = false;
    for _ in 0..100 {
        let status = client
            .get(format!("{}/admin/jobs/status", server.base_url))
            .header("cookie", &cookie)
            .send()
            .await?
            .json::<serde_json::Value>()
            .await?;
        if status["status"] == "cancelled" {
            finished = true;
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    }
    assert!(finished);
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
        StatusCode::CONFLICT
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

#[tokio::test]
async fn browser_account_controls_pause_revoke_change_password_and_close() -> Result<()> {
    let server = start_server().await?;
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()?;
    let signup = client
        .post(format!("{}/signup", server.base_url))
        .form(&[
            ("email", "owner@example.org"),
            ("password", "owner long password"),
        ])
        .send()
        .await?;
    assert_eq!(signup.status(), StatusCode::SEE_OTHER);
    let cookie = signup.headers()["set-cookie"]
        .to_str()?
        .split(';')
        .next()
        .unwrap()
        .to_owned();
    let panel = client
        .get(format!("{}/account", server.base_url))
        .header("cookie", &cookie)
        .send()
        .await?
        .text()
        .await?;
    let csrf = panel
        .split("name=\"csrf\" value=\"")
        .nth(1)
        .unwrap()
        .split('"')
        .next()
        .unwrap()
        .to_owned();
    let app_login = client
        .post(format!("{}/v1/auth/login", server.base_url))
        .json(&serde_json::json!({"email":"owner@example.org","password":"owner long password"}))
        .send()
        .await?
        .json::<serde_json::Value>()
        .await?;
    let app_token = app_login["access_token"].as_str().unwrap();
    assert_eq!(
        client
            .post(format!("{}/account/sharing/pause", server.base_url))
            .header("cookie", &cookie)
            .form(&[("csrf", csrf.as_str())])
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    assert_eq!(
        client
            .post(format!("{}/v1/cells", server.base_url))
            .bearer_auth(app_token)
            .header("x-device-id", "owner-phone")
            .body(upload_body(tower(50.4))?)
            .send()
            .await?
            .status(),
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        client
            .post(format!("{}/account/sharing/resume", server.base_url))
            .header("cookie", &cookie)
            .form(&[("csrf", csrf.as_str()), ("password", "wrong")])
            .send()
            .await?
            .status(),
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        client
            .post(format!("{}/account/sharing/resume", server.base_url))
            .header("cookie", &cookie)
            .form(&[("csrf", csrf.as_str()), ("password", "owner long password")])
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    assert_eq!(
        client
            .post(format!("{}/v1/cells", server.base_url))
            .bearer_auth(app_token)
            .header("x-device-id", "owner-phone")
            .body(upload_body(tower(50.4))?)
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        client
            .post(format!(
                "{}/account/sessions/revoke-others",
                server.base_url
            ))
            .header("cookie", &cookie)
            .form(&[("csrf", csrf.as_str())])
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    assert_eq!(
        client
            .get(format!("{}/v1/auth/me", server.base_url))
            .bearer_auth(app_token)
            .send()
            .await?
            .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        client
            .post(format!("{}/account/password", server.base_url))
            .header("cookie", &cookie)
            .form(&[
                ("csrf", csrf.as_str()),
                ("current_password", "owner long password"),
                ("new_password", "new owner password")
            ])
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    assert_eq!(
        client
            .post(format!("{}/v1/auth/login", server.base_url))
            .json(
                &serde_json::json!({"email":"owner@example.org","password":"owner long password"})
            )
            .send()
            .await?
            .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        client
            .post(format!("{}/account/close", server.base_url))
            .header("cookie", &cookie)
            .form(&[
                ("csrf", csrf.as_str()),
                ("password", "new owner password"),
                ("confirm", "DELETE ACCOUNT")
            ])
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    assert_eq!(
        client
            .get(format!("{}/account", server.base_url))
            .header("cookie", &cookie)
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    assert_eq!(
        client
            .post(format!("{}/v1/auth/login", server.base_url))
            .json(&serde_json::json!({"email":"owner@example.org","password":"new owner password"}))
            .send()
            .await?
            .status(),
        StatusCode::UNAUTHORIZED
    );
    assert!(
        CellStore::open(server.database.path())?
            .consensus(&tower(50.4).key)?
            .is_none()
    );
    let connection = rusqlite::Connection::open(server.database.path())?;
    for table in [
        "users",
        "auth_tokens",
        "account_deleted_keys",
        "email_verifications",
        "password_resets",
    ] {
        let column = if table == "users" {
            "id"
        } else if table == "account_deleted_keys" {
            "account_id"
        } else {
            "user_id"
        };
        let sql = format!("SELECT COUNT(*) FROM {table} WHERE {column}=?1");
        let count: i64 = connection.query_row(
            &sql,
            [app_login["account"]["id"].as_i64().unwrap()],
            |row| row.get(0),
        )?;
        assert_eq!(count, 0, "account closure left {table} rows");
    }
    Ok(())
}

#[tokio::test]
async fn browser_account_forms_validate_filters_and_destructive_actions() -> Result<()> {
    let server = start_server().await?;
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()?;
    assert_eq!(
        client
            .get(format!("{}/", server.base_url))
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    assert_eq!(
        client
            .get(format!("{}/forgot-password", server.base_url))
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        client
            .post(format!("{}/forgot-password", server.base_url))
            .form(&[("email", "owner@example.org")])
            .send()
            .await?
            .status(),
        StatusCode::SERVICE_UNAVAILABLE
    );
    assert_eq!(
        client
            .post(format!("{}/forgot-password", server.base_url))
            .form(&[("email", "not-an-email")])
            .send()
            .await?
            .status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        client
            .get(format!("{}/account/export", server.base_url))
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    let signup = client
        .post(format!("{}/signup", server.base_url))
        .form(&[
            ("email", "filters@example.org"),
            ("password", "filters safe password"),
        ])
        .send()
        .await?;
    let cookie = signup.headers()["set-cookie"]
        .to_str()?
        .split(';')
        .next()
        .unwrap()
        .to_owned();
    let panel = client
        .get(format!("{}/account", server.base_url))
        .header("cookie", &cookie)
        .send()
        .await?
        .text()
        .await?;
    let csrf = panel
        .split("name=\"csrf\" value=\"")
        .nth(1)
        .unwrap()
        .split('"')
        .next()
        .unwrap()
        .to_owned();
    for path in [
        "/account?mcc=0",
        "/account?from_s=10&to_s=1",
        "/account?page=18446744073709551615",
    ] {
        assert_eq!(
            client
                .get(format!("{}{}", server.base_url, path))
                .header("cookie", &cookie)
                .send()
                .await?
                .status(),
            StatusCode::BAD_REQUEST
        );
    }
    assert_eq!(
        client
            .get(format!("{}/account?mcc=&from_s=&to_s=", server.base_url))
            .header("cookie", &cookie)
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        client
            .get(format!("{}/account/export?mcc=0", server.base_url))
            .header("cookie", &cookie)
            .send()
            .await?
            .status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        client
            .post(format!("{}/account/password", server.base_url))
            .header("cookie", &cookie)
            .form(&[
                ("csrf", "bad"),
                ("current_password", "filters safe password"),
                ("new_password", "another safe password")
            ])
            .send()
            .await?
            .status(),
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        client
            .post(format!("{}/account/password", server.base_url))
            .header("cookie", &cookie)
            .form(&[
                ("csrf", csrf.as_str()),
                ("current_password", "filters safe password"),
                ("new_password", "short")
            ])
            .send()
            .await?
            .status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        client
            .post(format!("{}/account/password", server.base_url))
            .header("cookie", &cookie)
            .form(&[
                ("csrf", csrf.as_str()),
                ("current_password", "wrong"),
                ("new_password", "another safe password")
            ])
            .send()
            .await?
            .status(),
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        client
            .post(format!("{}/account/email", server.base_url))
            .header("cookie", &cookie)
            .form(&[
                ("csrf", csrf.as_str()),
                ("password", "filters safe password"),
                ("email", "new@example.org")
            ])
            .send()
            .await?
            .status(),
        StatusCode::SERVICE_UNAVAILABLE
    );
    assert_eq!(
        client
            .post(format!("{}/account/email/resend", server.base_url))
            .header("cookie", &cookie)
            .form(&[("csrf", csrf.as_str())])
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    assert_eq!(
        client
            .post(format!(
                "{}/account/contributions/delete-all",
                server.base_url
            ))
            .header("cookie", &cookie)
            .form(&[("csrf", csrf.as_str()), ("confirm", "NO")])
            .send()
            .await?
            .status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        client
            .post(format!("{}/account/close", server.base_url))
            .header("cookie", &cookie)
            .form(&[
                ("csrf", csrf.as_str()),
                ("password", "filters safe password"),
                ("confirm", "NO")
            ])
            .send()
            .await?
            .status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        client
            .post(format!("{}/account/close", server.base_url))
            .header("cookie", &cookie)
            .form(&[
                ("csrf", csrf.as_str()),
                ("password", "wrong"),
                ("confirm", "DELETE ACCOUNT")
            ])
            .send()
            .await?
            .status(),
        StatusCode::FORBIDDEN
    );
    Ok(())
}

#[tokio::test]
async fn browser_verification_and_recovery_paths_gate_unverified_uploads() -> Result<()> {
    let mail = MailConfig {
        public_url: "https://example.org".to_owned(),
        smtp_host: "invalid host".to_owned(),
        smtp_username: "test".to_owned(),
        smtp_password: "test".to_owned(),
        smtp_from: "IMU Nav <no-reply@example.org>".to_owned(),
    };
    let server = start_server_with_mail(Policy::default(), Some(mail)).await?;
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()?;
    assert_eq!(
        client
            .get(format!("{}/verify-email", server.base_url))
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    let signup = client
        .post(format!("{}/signup", server.base_url))
        .form(&[
            ("email", "unverified@example.org"),
            ("password", "unverified password"),
        ])
        .send()
        .await?;
    assert_eq!(signup.status(), StatusCode::SEE_OTHER);
    let cookie = signup.headers()["set-cookie"]
        .to_str()?
        .split(';')
        .next()
        .unwrap()
        .to_owned();
    let panel = client
        .get(format!("{}/account", server.base_url))
        .header("cookie", &cookie)
        .send()
        .await?
        .text()
        .await?;
    assert!(panel.contains("Verify your email"));
    let csrf = panel
        .split("name=\"csrf\" value=\"")
        .nth(1)
        .unwrap()
        .split('"')
        .next()
        .unwrap()
        .to_owned();
    let login = client
        .post(format!("{}/v1/auth/login", server.base_url))
        .json(
            &serde_json::json!({"email":"unverified@example.org","password":"unverified password"}),
        )
        .send()
        .await?
        .json::<serde_json::Value>()
        .await?;
    assert_eq!(login["account"]["email_verified"], false);
    assert_eq!(
        client
            .post(format!("{}/v1/cells", server.base_url))
            .bearer_auth(login["access_token"].as_str().unwrap())
            .header("x-device-id", "phone")
            .body(upload_body(tower(50.4))?)
            .send()
            .await?
            .status(),
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        client
            .post(format!("{}/account/sharing/resume", server.base_url))
            .header("cookie", &cookie)
            .form(&[("csrf", csrf.as_str()), ("password", "unverified password")])
            .send()
            .await?
            .status(),
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        client
            .post(format!("{}/account/email/resend", server.base_url))
            .header("cookie", &cookie)
            .form(&[("csrf", csrf.as_str())])
            .send()
            .await?
            .status(),
        StatusCode::SERVICE_UNAVAILABLE
    );
    assert_eq!(
        client
            .post(format!("{}/account/email", server.base_url))
            .header("cookie", &cookie)
            .form(&[
                ("csrf", csrf.as_str()),
                ("password", "wrong"),
                ("email", "new@example.org")
            ])
            .send()
            .await?
            .status(),
        StatusCode::CONFLICT
    );
    assert_eq!(
        client
            .post(format!("{}/account/email", server.base_url))
            .header("cookie", &cookie)
            .form(&[
                ("csrf", csrf.as_str()),
                ("password", "unverified password"),
                ("email", "new@example.org")
            ])
            .send()
            .await?
            .status(),
        StatusCode::SERVICE_UNAVAILABLE
    );
    assert_eq!(
        client
            .post(format!("{}/forgot-password", server.base_url))
            .form(&[("email", "missing@example.org")])
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        client
            .post(format!("{}/forgot-password", server.base_url))
            .form(&[("email", "unverified@example.org")])
            .send()
            .await?
            .status(),
        StatusCode::SERVICE_UNAVAILABLE
    );
    Ok(())
}

#[tokio::test]
async fn administrator_can_impersonate_active_user_and_return_with_audit() -> Result<()> {
    let server = start_server().await?;
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()?;
    let admin_login = client
        .post(format!("{}/login", server.base_url))
        .form(&[
            ("email", "admin@example.org"),
            ("password", "correct horse battery staple"),
        ])
        .send()
        .await?;
    assert_eq!(admin_login.status(), StatusCode::SEE_OTHER);
    let admin_cookie = admin_login.headers()["set-cookie"]
        .to_str()?
        .split(';')
        .next()
        .unwrap()
        .to_owned();
    let register: serde_json::Value = client
        .post(format!("{}/v1/auth/register", server.base_url))
        .json(&serde_json::json!({"email":"driver@example.org","password":"driver safe password"}))
        .send()
        .await?
        .json()
        .await?;
    let target_id = register["account"]["id"].as_i64().unwrap();
    assert_eq!(
        client
            .post(format!("{}/v1/cells", server.base_url))
            .bearer_auth(register["access_token"].as_str().unwrap())
            .header("x-device-id", "driver-phone")
            .body(upload_body(tower(50.4))?)
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    let dashboard = client
        .get(format!("{}/admin", server.base_url))
        .header("cookie", &admin_cookie)
        .send()
        .await?
        .text()
        .await?;
    assert!(dashboard.contains(&format!("/admin/accounts/{target_id}/impersonate")));
    let csrf = dashboard
        .split("name=\"csrf\" value=\"")
        .nth(1)
        .unwrap()
        .split('"')
        .next()
        .unwrap()
        .to_owned();
    let path = format!("{}/admin/accounts/{target_id}/impersonate", server.base_url);
    assert_eq!(
        client
            .post(&path)
            .header("cookie", &admin_cookie)
            .form(&[("csrf", "wrong")])
            .send()
            .await?
            .status(),
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        client
            .post(format!("{}/admin/accounts/1/impersonate", server.base_url))
            .header("cookie", &admin_cookie)
            .form(&[("csrf", csrf.as_str())])
            .send()
            .await?
            .status(),
        StatusCode::CONFLICT
    );
    let switched = client
        .post(&path)
        .header("cookie", &admin_cookie)
        .form(&[("csrf", csrf.as_str())])
        .send()
        .await?;
    assert_eq!(switched.status(), StatusCode::SEE_OTHER);
    assert_eq!(switched.headers()["location"], "/account");
    let cookies = switched
        .headers()
        .get_all("set-cookie")
        .iter()
        .map(|value| {
            value
                .to_str()
                .unwrap()
                .split(';')
                .next()
                .unwrap()
                .to_owned()
        })
        .collect::<Vec<_>>();
    let user_cookie = cookies
        .iter()
        .find(|cookie| cookie.starts_with("imu_nav_session="))
        .unwrap()
        .clone();
    let return_cookie = cookies
        .iter()
        .find(|cookie| cookie.starts_with("imu_nav_admin_return="))
        .unwrap()
        .clone();
    let combined = format!("{user_cookie}; {return_cookie}");
    assert_eq!(
        client
            .get(format!("{}/admin", server.base_url))
            .header("cookie", &combined)
            .send()
            .await?
            .status(),
        StatusCode::FORBIDDEN
    );
    let panel = client
        .get(format!("{}/account", server.base_url))
        .header("cookie", &combined)
        .send()
        .await?
        .text()
        .await?;
    assert!(panel.contains("Administrator view as driver@example.org"));
    assert!(panel.contains("account:") && panel.contains("driver-phone"));
    let user_csrf = panel
        .split("name=\"csrf\" value=\"")
        .nth(1)
        .unwrap()
        .split('"')
        .next()
        .unwrap()
        .to_owned();
    assert_eq!(
        client
            .get(format!("{}/account/export?mcc=255", server.base_url))
            .header("cookie", &combined)
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    let device = format!("account:{target_id}:driver-phone");
    assert_eq!(
        client
            .post(format!("{}/account/contributions/delete", server.base_url))
            .header("cookie", &combined)
            .form(&delete_form(&user_csrf, &device))
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    assert_eq!(
        client
            .post(format!("{}/account/stop-impersonation", server.base_url))
            .header("cookie", &combined)
            .form(&[("csrf", "wrong")])
            .send()
            .await?
            .status(),
        StatusCode::FORBIDDEN
    );
    let restored = client
        .post(format!("{}/account/stop-impersonation", server.base_url))
        .header("cookie", &combined)
        .form(&[("csrf", user_csrf.as_str())])
        .send()
        .await?;
    assert_eq!(restored.status(), StatusCode::SEE_OTHER);
    assert_eq!(restored.headers()["location"], "/admin");
    assert_eq!(
        client
            .get(format!("{}/admin", server.base_url))
            .header("cookie", &admin_cookie)
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        client
            .get(format!("{}/account", server.base_url))
            .header("cookie", &user_cookie)
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    let automatic_return = client
        .get(format!("{}/account", server.base_url))
        .header("cookie", &combined)
        .send()
        .await?;
    assert_eq!(automatic_return.status(), StatusCode::SEE_OTHER);
    assert_eq!(automatic_return.headers()["location"], "/admin");
    let late_return = client
        .post(format!("{}/account/stop-impersonation", server.base_url))
        .header("cookie", &combined)
        .form(&[("csrf", user_csrf.as_str())])
        .send()
        .await?;
    assert_eq!(late_return.status(), StatusCode::SEE_OTHER);
    assert_eq!(late_return.headers()["location"], "/admin");
    let connection = rusqlite::Connection::open(server.database.path())?;
    let actions = connection
        .prepare("SELECT action FROM admin_audit WHERE actor_id=1 ORDER BY id")?
        .query_map([], |row| row.get::<_, String>(0))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    for expected in [
        "start_impersonation",
        "impersonate_export",
        "impersonate_delete_observation",
        "stop_impersonation",
    ] {
        assert!(
            actions.iter().any(|action| action == expected),
            "missing {expected} audit"
        );
    }
    assert_eq!(
        client
            .get(format!("{}/v1/auth/me", server.base_url))
            .bearer_auth(register["access_token"].as_str().unwrap())
            .send()
            .await?
            .status(),
        StatusCode::OK
    );
    let switched_again = client
        .post(&path)
        .header("cookie", &admin_cookie)
        .form(&[("csrf", csrf.as_str())])
        .send()
        .await?;
    let second_user_cookie = switched_again
        .headers()
        .get_all("set-cookie")
        .iter()
        .map(|value| {
            value
                .to_str()
                .unwrap()
                .split(';')
                .next()
                .unwrap()
                .to_owned()
        })
        .find(|cookie| cookie.starts_with("imu_nav_session="))
        .unwrap();
    assert_eq!(
        client
            .post(format!("{}/account/logout", server.base_url))
            .header("cookie", &admin_cookie)
            .form(&[("csrf", csrf.as_str())])
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    assert_eq!(
        client
            .get(format!("{}/account", server.base_url))
            .header("cookie", &second_user_cookie)
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    Ok(())
}

#[tokio::test]
async fn impersonation_rejects_suspended_users_and_signout_revokes_admin() -> Result<()> {
    let server = start_server().await?;
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()?;
    let register: serde_json::Value = client
        .post(format!("{}/v1/auth/register", server.base_url))
        .json(&serde_json::json!({"email":"other@example.org","password":"other safe password"}))
        .send()
        .await?
        .json()
        .await?;
    let target_id = register["account"]["id"].as_i64().unwrap();
    let login = client
        .post(format!("{}/login", server.base_url))
        .form(&[
            ("email", "admin@example.org"),
            ("password", "correct horse battery staple"),
        ])
        .send()
        .await?;
    let admin_cookie = login.headers()["set-cookie"]
        .to_str()?
        .split(';')
        .next()
        .unwrap()
        .to_owned();
    let dashboard = client
        .get(format!("{}/admin", server.base_url))
        .header("cookie", &admin_cookie)
        .send()
        .await?
        .text()
        .await?;
    let csrf = dashboard
        .split("name=\"csrf\" value=\"")
        .nth(1)
        .unwrap()
        .split('"')
        .next()
        .unwrap()
        .to_owned();
    assert_eq!(
        client
            .post(format!(
                "{}/admin/accounts/{target_id}/suspend",
                server.base_url
            ))
            .header("cookie", &admin_cookie)
            .form(&[("csrf", csrf.as_str())])
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    let impersonate = format!("{}/admin/accounts/{target_id}/impersonate", server.base_url);
    assert_eq!(
        client
            .post(&impersonate)
            .header("cookie", &admin_cookie)
            .form(&[("csrf", csrf.as_str())])
            .send()
            .await?
            .status(),
        StatusCode::CONFLICT
    );
    assert_eq!(
        client
            .post(format!(
                "{}/admin/accounts/{target_id}/restore",
                server.base_url
            ))
            .header("cookie", &admin_cookie)
            .form(&[("csrf", csrf.as_str())])
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    let switched = client
        .post(&impersonate)
        .header("cookie", &admin_cookie)
        .form(&[("csrf", csrf.as_str())])
        .send()
        .await?;
    let cookies = switched
        .headers()
        .get_all("set-cookie")
        .iter()
        .map(|value| {
            value
                .to_str()
                .unwrap()
                .split(';')
                .next()
                .unwrap()
                .to_owned()
        })
        .collect::<Vec<_>>();
    let active = cookies
        .iter()
        .find(|cookie| cookie.starts_with("imu_nav_session="))
        .unwrap();
    let return_cookie = cookies
        .iter()
        .find(|cookie| cookie.starts_with("imu_nav_admin_return="))
        .unwrap();
    let combined = format!("{active}; {return_cookie}");
    let panel = client
        .get(format!("{}/account", server.base_url))
        .header("cookie", &combined)
        .send()
        .await?
        .text()
        .await?;
    let user_csrf = panel
        .split("name=\"csrf\" value=\"")
        .nth(1)
        .unwrap()
        .split('"')
        .next()
        .unwrap()
        .to_owned();
    assert_eq!(
        client
            .post(format!("{}/account/logout", server.base_url))
            .header("cookie", &combined)
            .form(&[("csrf", user_csrf.as_str())])
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    assert_eq!(
        client
            .get(format!("{}/admin", server.base_url))
            .header("cookie", &admin_cookie)
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    assert_eq!(
        client
            .get(format!("{}/account", server.base_url))
            .header("cookie", &combined)
            .send()
            .await?
            .status(),
        StatusCode::SEE_OTHER
    );
    Ok(())
}
