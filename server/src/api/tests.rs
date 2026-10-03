use super::*;

#[test]
fn rate_and_identity_limits_expire_only_after_their_windows() {
    let file = tempfile::NamedTempFile::new().unwrap();
    let state = AppState::new(
        CellStore::open(file.path()).unwrap(),
        ServerConfig {
            api_key: None,
            trust_proxy: false,
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
fn proxy_headers_and_credentials_are_explicitly_trusted() {
    let peer = "127.0.0.1:1234".parse().unwrap();
    let mut headers = HeaderMap::new();
    headers.insert("x-forwarded-for", "192.0.2.1, 192.0.2.2".parse().unwrap());
    assert_eq!(client_ip(peer, &headers, false), "127.0.0.1");
    assert_eq!(client_ip(peer, &headers, true), "192.0.2.1");
    headers.insert("x-forwarded-for", "garbage, 192.0.2.1".parse().unwrap());
    assert_eq!(client_ip(peer, &headers, true), "127.0.0.1");
    assert!(authorize(&headers, None).is_ok());
    assert!(authorize(&headers, Some("")).is_ok());
    assert_eq!(
        authorize(&headers, Some("secret")).unwrap_err().0,
        StatusCode::UNAUTHORIZED
    );
    headers.insert(header::AUTHORIZATION, "Bearer secret".parse().unwrap());
    assert!(authorize(&headers, Some("secret")).is_ok());
    for device in ["short", "1234567_", "1234567é", &"a".repeat(65)] {
        assert!(!valid_device_id(device));
    }
    for device in ["12345678", "device-1", &"a".repeat(64)] {
        assert!(valid_device_id(device));
    }
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
    let state = AppState::new(
        CellStore::open(file.path()).unwrap(),
        ServerConfig {
            api_key: None,
            policy: Policy::default(),
            trust_proxy: false,
        },
    )
    .unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let application = router(state.clone()).into_make_service_with_connect_info::<SocketAddr>();
    let task = tokio::spawn(async move {
        axum::serve(listener, application).await.unwrap();
    });
    let (mut socket, _) = tokio_tungstenite::connect_async(format!("ws://{address}/v1/events"))
        .await
        .unwrap();
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
