use anyhow::Result;
use futures_util::StreamExt;
use imu_nav_cell_server::{
    AppState, CellKey, CellStore, CellTower, Consensus, Policy, Radio, ServerConfig, decode_towers,
    encode_towers, router,
};
use reqwest::StatusCode;
use std::net::{Ipv4Addr, SocketAddr};
use tempfile::NamedTempFile;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;

struct TestServer {
    base_url: String,
    task: tokio::task::JoinHandle<()>,
    _database: NamedTempFile,
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
    let state = AppState::new(
        store,
        ServerConfig {
            api_key: Some("secret".to_owned()),
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
    Ok(TestServer {
        base_url: format!("http://{address}"),
        task,
        _database: database,
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

#[tokio::test]
async fn android_protocol_and_websocket_events_stay_compatible() -> Result<()> {
    let server = start_server().await?;
    let client = reqwest::Client::new();
    let ws_url = server.base_url.replace("http://", "ws://") + "/v1/events";
    let mut request = ws_url.into_client_request()?;
    request
        .headers_mut()
        .insert("authorization", HeaderValue::from_static("Bearer secret"));
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
            .bearer_auth("secret")
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
                .bearer_auth("secret")
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
        .bearer_auth("secret")
        .json(&serde_json::json!({"lat": 50.45, "lon": 30.52, "range_m": 700.0, "samples": 20}))
        .send()
        .await?;
    assert_eq!(response.status(), StatusCode::OK);
    let response = client
        .get(format!("{}{}", server.base_url, path))
        .bearer_auth("secret")
        .send()
        .await?;
    assert_eq!(response.status(), StatusCode::OK);
    let response = client
        .get(format!("{}/v1/towers", server.base_url))
        .bearer_auth("secret")
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
        .bearer_auth("secret")
        .send()
        .await?;
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    let response = client
        .get(format!("{}/v1/towers", server.base_url))
        .bearer_auth("secret")
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
async fn invalid_filters_keys_and_oversized_uploads_are_rejected() -> Result<()> {
    let policy = Policy {
        max_rows_per_upload: 1,
        ..Policy::default()
    };
    let server = start_server_with_policy(policy).await?;
    let client = reqwest::Client::new();

    let response = client
        .get(format!("{}/v1/towers?mcc=not-a-number", server.base_url))
        .bearer_auth("secret")
        .send()
        .await?;
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);

    let response = client
        .put(format!("{}/v1/towers/LTE/-1/1/1864/99", server.base_url))
        .bearer_auth("secret")
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
        .bearer_auth("secret")
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
            .bearer_auth("secret")
            .header("x-device-id", "device-aaaa-1111")
            .body(body.clone())
            .send()
            .await?;
        assert_eq!(response.status(), expected);
    }
    Ok(())
}
