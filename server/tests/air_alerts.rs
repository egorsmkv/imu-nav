use anyhow::Result;
use futures_util::StreamExt;
use imu_nav_cell_server::{
    AirAlertConfig, AppState, CellStore, Policy, ServerConfig, create_admin, router,
};
use reqwest::StatusCode;
use std::net::{Ipv4Addr, SocketAddr};
use tempfile::NamedTempFile;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;

#[tokio::test]
async fn account_opt_in_gates_the_user_stream_and_provider_callback() -> Result<()> {
    let database = NamedTempFile::new()?;
    let store = CellStore::open(database.path())?;
    create_admin(&store, "admin@example.org", "correct horse battery staple")?;
    let state = AppState::new(
        store,
        ServerConfig {
            mail: None,
            policy: Policy::default(),
            trust_proxy: false,
            secure_cookies: false,
            privacy: None,
        },
    )?;
    state.configure_air_alerts(AirAlertConfig::new(
        "test-key".to_owned(),
        "abcdefghijklmnopqrstuvwxyz012345".to_owned(),
        "https://cells.example.org".to_owned(),
    ));
    let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    let address = listener.local_addr()?;
    let task = tokio::spawn(async move {
        axum::serve(
            listener,
            router(state).into_make_service_with_connect_info::<SocketAddr>(),
        )
        .await
        .unwrap();
    });
    let base = format!("http://{address}");
    let client = reqwest::Client::new();
    let login: serde_json::Value = client.post(format!("{base}/v1/auth/login"))
        .json(&serde_json::json!({"email":"admin@example.org","password":"correct horse battery staple"}))
        .send().await?.json().await?;
    let token = login["access_token"].as_str().unwrap();
    let preference: serde_json::Value = client
        .get(format!("{base}/v1/air-alerts/preferences"))
        .bearer_auth(token)
        .send()
        .await?
        .json()
        .await?;
    assert_eq!(
        preference,
        serde_json::json!({"enabled":false,"available":true})
    );
    assert_eq!(
        client
            .post(format!("{base}/v1/air-alerts/provider/wrong"))
            .send()
            .await?
            .status(),
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        client
            .post(format!(
                "{base}/v1/air-alerts/provider/abcdefghijklmnopqrstuvwxyz012345"
            ))
            .send()
            .await?
            .status(),
        StatusCode::ACCEPTED
    );
    assert_eq!(
        client
            .put(format!("{base}/v1/air-alerts/preferences"))
            .bearer_auth(token)
            .json(&serde_json::json!({"enabled":true}))
            .send()
            .await?
            .status(),
        StatusCode::NO_CONTENT
    );
    let mut request = format!("ws://{address}/v1/air-alerts/stream").into_client_request()?;
    request
        .headers_mut()
        .insert("Authorization", format!("Bearer {token}").parse()?);
    let (mut socket, _) = tokio_tungstenite::connect_async(request).await?;
    let initial = socket.next().await.unwrap()?;
    let message: serde_json::Value = serde_json::from_str(initial.to_text()?)?;
    assert_eq!(message["type"], "snapshot");
    assert_eq!(message["active"], serde_json::json!([]));
    assert_eq!(
        client
            .put(format!("{base}/v1/air-alerts/preferences"))
            .bearer_auth(token)
            .json(&serde_json::json!({"enabled":false}))
            .send()
            .await?
            .status(),
        StatusCode::NO_CONTENT
    );
    assert_preference(&client, &base, token, false).await?;
    task.abort();
    Ok(())
}

async fn assert_preference(
    client: &reqwest::Client,
    base: &str,
    token: &str,
    expected: bool,
) -> Result<()> {
    let preference: serde_json::Value = client
        .get(format!("{base}/v1/air-alerts/preferences"))
        .bearer_auth(token)
        .send()
        .await?
        .json()
        .await?;
    assert_eq!(preference["enabled"], expected);
    Ok(())
}
