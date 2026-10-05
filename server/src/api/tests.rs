use super::*;

#[test]
fn rate_and_identity_limits_expire_only_after_their_windows() {
    let file = tempfile::NamedTempFile::new().unwrap();
    let state = AppState::new(
        CellStore::open(file.path()).unwrap(),
        ServerConfig {
            mail: None,
            trust_proxy: false,
            secure_cookies: false,
            policy: Policy {
                max_uploads_per_hour_per_device: 1,
                max_uploads_per_hour_per_ip: 2,
                max_devices_per_ip_per_day: 1,
                ..Policy::default()
            },
        },
    )
    .unwrap();
    let now = Instant::now();
    assert!(enforce_limits_at(&state, "ip1", "device1", now).is_ok());
    assert_eq!(
        enforce_limits_at(&state, "ip1", "device1", now)
            .unwrap_err()
            .1,
        "RATE_LIMITED"
    );
    assert_eq!(
        enforce_limits_at(&state, "ip1", "device2", now)
            .unwrap_err()
            .1,
        "TOO_MANY_DEVICES"
    );
    assert!(enforce_limits_at(&state, "ip2", "device1", now + HOUR).is_err());
    assert!(
        enforce_limits_at(
            &state,
            "ip2",
            "device1",
            now + HOUR + Duration::from_nanos(1)
        )
        .is_ok()
    );
    assert!(enforce_limits_at(&state, "ip1", "device2", now + DAY).is_err());
    assert!(
        enforce_limits_at(
            &state,
            "ip1",
            "device2",
            now + DAY + Duration::from_nanos(1)
        )
        .is_ok()
    );
    let mut limits = state.limits.lock().unwrap();
    limits.cleanup_if_due(now + DAY * 3);
    assert!(limits.by_device.is_empty());
    assert!(limits.by_ip.is_empty());
    assert!(limits.devices_by_ip.is_empty());
}

#[test]
fn proxy_headers_and_device_ids_are_validated() {
    let peer = "127.0.0.1:1234".parse().unwrap();
    let mut headers = HeaderMap::new();
    headers.insert("x-forwarded-for", "192.0.2.1, 192.0.2.2".parse().unwrap());
    assert_eq!(client_ip(peer, &headers, false), "127.0.0.1");
    assert_eq!(client_ip(peer, &headers, true), "192.0.2.1");
    headers.insert("x-forwarded-for", "garbage, 192.0.2.1".parse().unwrap());
    assert_eq!(client_ip(peer, &headers, true), "127.0.0.1");
    for device in ["short", "1234567_", "1234567é", &"a".repeat(65)] {
        assert!(!valid_device_id(device));
    }
    for device in ["12345678", "device-1", &"a".repeat(64)] {
        assert!(valid_device_id(device));
    }
}

#[test]
fn export_writer_stops_when_client_disconnects() {
    let (sender, receiver) = mpsc::channel(1);
    drop(receiver);
    let mut writer = ChannelWriter::new(sender);
    let error = writer
        .write_all(&vec![b'x'; DOWNLOAD_CHUNK_BYTES])
        .unwrap_err();
    assert_eq!(error.kind(), io::ErrorKind::BrokenPipe);
}

#[tokio::test]
async fn public_export_slots_are_bounded() {
    let file = tempfile::NamedTempFile::new().unwrap();
    let state = AppState::new(
        CellStore::open(file.path()).unwrap(),
        ServerConfig {
            mail: None,
            policy: Policy::default(),
            trust_proxy: false,
            secure_cookies: false,
        },
    )
    .unwrap();
    let _first = state.downloads.clone().acquire_owned().await.unwrap();
    let _second = state.downloads.clone().acquire_owned().await.unwrap();
    assert_eq!(
        stream_public_export(state, "application/gzip", |_| Ok(()))
            .unwrap_err()
            .0,
        StatusCode::TOO_MANY_REQUESTS
    );
}

#[tokio::test]
async fn health_count_is_cached_until_its_short_ttl_expires() {
    let file = tempfile::NamedTempFile::new().unwrap();
    let store = CellStore::open(file.path()).unwrap();
    let state = AppState::new(
        store.clone(),
        ServerConfig {
            mail: None,
            policy: Policy::default(),
            trust_proxy: false,
            secure_cookies: false,
        },
    )
    .unwrap();
    assert_eq!(health(State(state.clone())).await.unwrap(), "ok 0\n");
    store
        .seed(
            &[CellTower {
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
            }],
            100,
            &Policy::default(),
        )
        .unwrap();
    assert_eq!(health(State(state.clone())).await.unwrap(), "ok 0\n");
    *state.health_cache.lock().await = Some((
        Instant::now().checked_sub(HEALTH_CACHE_LIFETIME).unwrap(),
        0,
    ));
    assert_eq!(health(State(state)).await.unwrap(), "ok 1\n");
}

#[tokio::test]
async fn database_errors_and_panics_become_server_errors() {
    assert_eq!(
        run_db(|| Err::<(), _>(anyhow::anyhow!("test failure")))
            .await
            .unwrap_err()
            .0,
        StatusCode::INTERNAL_SERVER_ERROR
    );
    assert_eq!(
        run_db::<()>(|| panic!("test panic")).await.unwrap_err().0,
        StatusCode::INTERNAL_SERVER_ERROR
    );
}

#[tokio::test]
async fn slow_websocket_consumers_receive_resync_then_live_events() {
    use futures_util::SinkExt;
    use tokio_tungstenite::tungstenite::Message as ClientMessage;
    let file = tempfile::NamedTempFile::new().unwrap();
    let store = CellStore::open(file.path()).unwrap();
    crate::auth::create_admin(&store, "admin@example.org", "correct horse battery staple").unwrap();
    let state = AppState::new(
        store,
        ServerConfig {
            mail: None,
            policy: Policy::default(),
            trust_proxy: false,
            secure_cookies: false,
        },
    )
    .unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let application = router(state.clone()).into_make_service_with_connect_info::<SocketAddr>();
    let task = tokio::spawn(async move {
        axum::serve(listener, application).await.unwrap();
    });
    let response = reqwest::Client::new().post(format!("http://{address}/v1/auth/login"))
        .json(&serde_json::json!({"email":"admin@example.org","password":"correct horse battery staple"}))
        .send().await.unwrap();
    let token = response.json::<serde_json::Value>().await.unwrap()["access_token"]
        .as_str()
        .unwrap()
        .to_owned();
    let mut request =
        tokio_tungstenite::tungstenite::client::IntoClientRequest::into_client_request(format!(
            "ws://{address}/v1/events"
        ))
        .unwrap();
    request
        .headers_mut()
        .insert("authorization", format!("Bearer {token}").parse().unwrap());
    let (mut socket, _) = tokio_tungstenite::connect_async(request).await.unwrap();
    let ready = socket.next().await.unwrap().unwrap();
    assert!(ready.to_text().unwrap().contains("\"ready\""));
    // This is a current-thread runtime: the receiver cannot run until we next await.
    for published in 0..2048 {
        state.events.send(ServerEvent::Ready { published }).unwrap();
    }
    let message = tokio::time::timeout(Duration::from_secs(5), socket.next())
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    let event: serde_json::Value = serde_json::from_str(message.to_text().unwrap()).unwrap();
    assert_eq!(event["type"], "resync_required");
    assert_eq!(event["missed"], 1024);
    let next = socket.next().await.unwrap().unwrap();
    assert!(next.to_text().unwrap().contains("\"ready\""));
    socket
        .send(ClientMessage::Ping(vec![1, 2, 3].into()))
        .await
        .unwrap();
    socket.close(None).await.unwrap();
    task.abort();
}
