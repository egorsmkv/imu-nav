use super::*;

fn test_account(value: i64) -> crate::AccountId {
    crate::AccountId::new(value).expect("synthetic positive account ID")
}

fn sample() -> Sample {
    Sample {
        sequence: imu_nav_protocol::Sequence::new(1).unwrap(),
        lat: -50.5,
        lon: -30.25,
        speed_mps: 10.0,
        bearing_deg: 90.0,
        uncertainty_m: 80.0,
        source: "DR".parse().unwrap(),
        elapsed_ms: imu_nav_protocol::ElapsedMs::new(1_000).unwrap(),
        utc_ms: imu_nav_protocol::UnixMs::new(1_767_225_600_000).unwrap(),
        age_ms: imu_nav_protocol::DurationMs::new(0).unwrap(),
    }
}

#[test]
fn nmea_is_estimated_and_rounds_coordinate_carries() {
    let encoded = sample().nmea();
    assert!(
        encoded.starts_with(
            "$GPRMC,000000.000,A,5030.000000,S,03015.000000,W,19.438,90.000,010126,,,E*"
        )
    );
    let (body, checksum) = encoded.trim().split_once('*').unwrap();
    let expected = body[1..].bytes().fold(0, |sum, byte| sum ^ byte);
    assert_eq!(checksum, format!("{expected:02X}"));
    assert_eq!(coordinate(89.999_999_999_9, 2), "9000.000000");
}

#[test]
fn invalid_samples_and_public_listener_configs_are_rejected() {
    assert!(sample().valid());
    let mut invalid = sample();
    invalid.lat = f64::NAN;
    assert!(!invalid.valid());
    assert!(serde_json::from_str::<imu_nav_protocol::PositionSource>("\"NONE\"").is_err());
    invalid = sample();
    invalid.age_ms = imu_nav_protocol::DurationMs::new(2_001).unwrap();
    assert!(!invalid.valid());
    let mut config = LivePositionConfig {
        bind: Ipv4Addr::LOCALHOST,
        receivers: vec![LivePositionReceiver {
            account_id: test_account(1),
            port: 10110,
            peer: Ipv4Addr::LOCALHOST,
        }],
    };
    assert!(config.validate().is_ok());
    config.bind = Ipv4Addr::UNSPECIFIED;
    assert!(config.validate().is_err());
    config.bind = Ipv4Addr::LOCALHOST;
    config.receivers.push(config.receivers[0].clone());
    assert!(config.validate().is_err());
}

#[test]
fn expiry_discards_latest_samples_and_closes_subscriptions() {
    let hub = LivePositionHub::default();
    let (sender, mut receiver) = watch::channel(Some(Arc::new(Published {
        sample: sample(),
        guidance: None,
        route: None,
        received: Instant::now(),
    })));
    hub.sessions.lock().unwrap().insert(
        test_account(1),
        Session {
            id: "a".repeat(64),
            login_id: "test".into(),
            updated: Instant::now().checked_sub(SESSION_TTL).unwrap(),
            sequence: Some(imu_nav_protocol::Sequence::new(1).unwrap()),
            elapsed_ms: Some(imu_nav_protocol::ElapsedMs::new(1_000).unwrap()),
            samples: sender,
            slots: Arc::new(Semaphore::new(4)),
            route: None,
        },
    );
    hub.prune();
    assert!(hub.sessions.lock().unwrap().is_empty());
    assert!(receiver.borrow_and_update().is_none());
}

#[tokio::test]
async fn account_session_publishes_only_to_its_receiver_and_withdrawal_stops_it()
-> anyhow::Result<()> {
    use tokio::io::AsyncBufReadExt;
    let (_file, state) = fixture()?;
    let probe = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    let port = probe.local_addr()?.port();
    drop(probe);
    state.configure_live_position(LivePositionConfig {
        bind: Ipv4Addr::LOCALHOST,
        receivers: vec![LivePositionReceiver {
            account_id: test_account(1),
            port,
            peer: Ipv4Addr::LOCALHOST,
        }],
    })?;
    let _listeners = state.spawn_live_position().await?;
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    let base = format!("http://{}", listener.local_addr()?);
    let server = tokio::spawn(
        axum::serve(
            listener,
            crate::router(state.clone())
                .into_make_service_with_connect_info::<std::net::SocketAddr>(),
        )
        .into_future(),
    );
    let client = reqwest::Client::new();
    let login: serde_json::Value = client
        .post(format!("{base}/v1/auth/login"))
        .json(&serde_json::json!({"email":"test@example.org", "password":"synthetic-password-123"}))
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    let token = login["access_token"].as_str().unwrap();
    assert_eq!(
        client
            .post(format!("{base}/v1/live/session"))
            .send()
            .await?
            .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        client
            .post(format!("{base}/v1/live/session"))
            .bearer_auth(token)
            .send()
            .await?
            .status(),
        StatusCode::FORBIDDEN
    );
    client
        .put(format!("{base}/v1/privacy/consents/live_position"))
        .bearer_auth(token)
        .json(&serde_json::json!({"notice_version":"test"}))
        .send()
        .await?
        .error_for_status()?;
    let session: serde_json::Value = client
        .post(format!("{base}/v1/live/session"))
        .bearer_auth(token)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    let session_id = session["session"].as_str().unwrap();
    assert_eq!(session["receiver"]["navigation_version"], VERSION);
    assert_body_limits(&client, &base, token, session_id).await?;
    let body = serde_json::json!({"sequence":1, "lat":-50.5,"lon":-30.25,"speed_mps":10.0,"bearing_deg":90.0,
        "uncertainty_m":80.0,"source":"DR","elapsed_ms":1_000,"utc_ms":1_767_225_600_000_i64,"age_ms":0});
    let publish = || {
        client
            .put(format!("{base}/v1/live/session"))
            .bearer_auth(token)
            .header("X-Live-Session", session_id)
            .json(&body)
    };
    publish().send().await?.error_for_status()?;
    assert_rejected_peer(port).await?;
    assert_eq!(publish().send().await?.status(), StatusCode::CONFLICT);
    let socket = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).await?;
    let mut reader = tokio::io::BufReader::new(socket);
    let mut line = String::new();
    tokio::time::timeout(Duration::from_secs(3), reader.read_line(&mut line)).await??;
    assert_eq!(line, sample().nmea());
    client
        .delete(format!("{base}/v1/privacy/consents/live_position"))
        .bearer_auth(token)
        .send()
        .await?
        .error_for_status()?;
    line.clear();
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(3), reader.read_line(&mut line)).await??,
        0
    );
    assert!(state.live_position.sessions.lock().unwrap().is_empty());
    server.abort();
    Ok(())
}

/// Verify the original and mirror endpoints retain their distinct upload limits.
async fn assert_body_limits(
    client: &reqwest::Client,
    base: &str,
    token: &str,
    session_id: &str,
) -> anyhow::Result<()> {
    for (suffix, limit) in [
        ("", 2_048),
        ("/navigation", 8_192),
        ("/route", MAX_ROUTE_BYTES),
    ] {
        assert_eq!(
            client
                .put(format!("{base}/v1/live/session{suffix}"))
                .bearer_auth(token)
                .header("X-Live-Session", session_id)
                .header("Content-Type", "application/json")
                .body(" ".repeat(limit + 1))
                .send()
                .await?
                .status(),
            StatusCode::PAYLOAD_TOO_LARGE
        );
    }
    Ok(())
}

/// Synthetic account and notice shared by live-relay endpoint tests.
fn fixture() -> anyhow::Result<(tempfile::NamedTempFile, AppState)> {
    let file = tempfile::NamedTempFile::new()?;
    let store = crate::CellStore::open(file.path())?;
    crate::create_admin(&store, "test@example.org", "synthetic-password-123")?;
    let notice = crate::PrivacyNotice {
        version: "test".into(),
        controller: "test".into(),
        contact: "test".into(),
        rights_contact: "test".into(),
        region: "test".into(),
        recipients: "test".into(),
        transfers: "test".into(),
        account_basis: "test".into(),
        security_basis: "test".into(),
        notice_en: "test".into(),
        notice_uk: "test".into(),
        notice_ru: "test".into(),
        tower_retention_months: 12,
        diagnostics_retention_days: 30,
    };
    let state = AppState::new(
        store,
        crate::ServerConfig {
            trip_archive: crate::TripArchiveLimits::default(),
            mail: None,
            policy: crate::Policy::default(),
            trust_proxy: false,
            secure_cookies: false,
            privacy: Some(notice),
        },
    )?;
    Ok((file, state))
}

async fn assert_rejected_peer(port: u16) -> anyhow::Result<()> {
    use tokio::io::AsyncReadExt;
    let socket = tokio::net::TcpSocket::new_v4()?;
    socket.bind((Ipv4Addr::new(127, 0, 0, 2), 0).into())?;
    let mut socket = socket.connect((Ipv4Addr::LOCALHOST, port).into()).await?;
    let mut byte = [0];
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(2), socket.read(&mut byte)).await??,
        0
    );
    Ok(())
}

/// Install only synthetic credentials to exercise the same token/session checks as the HTTP flow.
fn headers(state: &AppState, account: crate::AccountId, login: &str) -> anyhow::Result<HeaderMap> {
    let token = format!("synthetic-{account}-{login}");
    let connection = state.store.connection()?;
    for kind in ["access", "refresh"] {
        let digest = auth::digest(&format!("{token}-{kind}"));
        connection.execute(
            "INSERT INTO auth_tokens VALUES (?1,?2,?3,?4,?5)",
            crate::db::params![digest, account, login, kind, auth::now_s() + 3600],
        )?;
    }
    let mut headers = HeaderMap::new();
    headers.insert("Authorization", format!("Bearer {token}-access").parse()?);
    Ok(headers)
}

#[tokio::test]
async fn account_isolation_refresh_ordering_and_logout_are_enforced() -> anyhow::Result<()> {
    let (_file, state) = fixture()?;
    crate::create_admin(&state.store, "other@example.org", "synthetic-password-456")?;
    configure_accounts(&state)?;
    for account in [test_account(1), test_account(2)] {
        state
            .store
            .grant_privacy_consent(account, "live_position", "test")?;
    }
    let mut first = headers(&state, test_account(1), "first")?;
    let mut second = headers(&state, test_account(2), "second")?;
    let Json(created) = start(State(state.clone()), first.clone()).await.unwrap();
    first.insert("X-Live-Session", created.session.parse()?);
    second.insert("X-Live-Session", created.session.parse()?);
    assert_eq!(
        start(State(state.clone()), first.clone())
            .await
            .err()
            .unwrap()
            .0,
        StatusCode::CONFLICT
    );
    assert_eq!(
        publish(State(state.clone()), second, Json(sample()))
            .await
            .err()
            .unwrap()
            .0,
        StatusCode::NOT_FOUND
    );
    let _ = publish(State(state.clone()), first.clone(), Json(sample()))
        .await
        .unwrap();
    assert_eq!(
        publish(State(state.clone()), first.clone(), Json(sample()))
            .await
            .err()
            .unwrap()
            .0,
        StatusCode::CONFLICT
    );
    let mut next = sample();
    next.sequence = imu_nav_protocol::Sequence::new(2).unwrap();
    next.elapsed_ms = imu_nav_protocol::ElapsedMs::new(1_500).unwrap();
    // Keep the rate gate closed even if unrelated concurrent tests consume more than 250 ms.
    state
        .live_position
        .sessions
        .lock()
        .unwrap()
        .get_mut(&test_account(1))
        .unwrap()
        .updated = Instant::now() + Duration::from_secs(30);
    assert_eq!(
        publish(State(state.clone()), first.clone(), Json(next.clone()))
            .await
            .err()
            .unwrap()
            .0,
        StatusCode::TOO_MANY_REQUESTS
    );
    state
        .live_position
        .sessions
        .lock()
        .unwrap()
        .get_mut(&test_account(1))
        .unwrap()
        .updated = Instant::now()
        .checked_sub(Duration::from_millis(251))
        .unwrap();
    // Access-token rotation retains the same login identity and can publish the next sample.
    let connection = state.store.connection()?;
    connection.execute(
        "INSERT INTO auth_tokens VALUES (?1,1,'first','access',?2)",
        crate::db::params![
            auth::digest("synthetic-rotated-access"),
            auth::now_s() + 3600
        ],
    )?;
    drop(connection);
    first.insert("Authorization", "Bearer synthetic-rotated-access".parse()?);
    let _ = publish(State(state.clone()), first.clone(), Json(next.clone()))
        .await
        .unwrap();
    state.store.connection()?.execute(
        "DELETE FROM auth_tokens WHERE session_id='first'",
        crate::db::params![],
    )?;
    assert_eq!(
        publish(State(state.clone()), first, Json(next))
            .await
            .err()
            .unwrap()
            .0,
        StatusCode::UNAUTHORIZED
    );
    assert!(!eligible(&state, test_account(1), "first").await.unwrap());
    state.live_position.stop_login(test_account(1), "first");
    assert!(state.live_position.sessions.lock().unwrap().is_empty());
    Ok(())
}

#[test]
fn sample_expiry_clears_memory_before_the_session_lease_expires() {
    let hub = LivePositionHub::default();
    let (sender, mut receiver) = watch::channel(Some(Arc::new(Published {
        sample: sample(),
        guidance: None,
        route: None,
        received: Instant::now().checked_sub(SAMPLE_TTL).unwrap(),
    })));
    hub.sessions.lock().unwrap().insert(
        test_account(1),
        Session {
            id: "a".repeat(64),
            login_id: "new-login".into(),
            updated: Instant::now(),
            sequence: Some(imu_nav_protocol::Sequence::new(1).unwrap()),
            elapsed_ms: Some(imu_nav_protocol::ElapsedMs::new(1000).unwrap()),
            samples: sender,
            slots: Arc::new(Semaphore::new(4)),
            route: None,
        },
    );
    hub.stop_login(test_account(1), "old-login");
    assert_eq!(hub.sessions.lock().unwrap().len(), 1);
    hub.prune();
    assert!(receiver.borrow_and_update().is_none());
    assert_eq!(hub.sessions.lock().unwrap().len(), 1);
}

#[tokio::test]
async fn logout_disconnects_an_idle_receiver_without_another_publish() -> anyhow::Result<()> {
    use tokio::io::AsyncBufReadExt;
    let (_file, state) = fixture()?;
    state.configure_live_position(LivePositionConfig {
        bind: Ipv4Addr::LOCALHOST,
        receivers: vec![LivePositionReceiver {
            account_id: test_account(1),
            port: 10111,
            peer: Ipv4Addr::LOCALHOST,
        }],
    })?;
    state
        .store
        .grant_privacy_consent(test_account(1), "live_position", "test")?;
    let mut headers = headers(&state, test_account(1), "synthetic-login")?;
    let Json(created) = start(State(state.clone()), headers.clone()).await.unwrap();
    headers.insert("X-Live-Session", created.session.parse()?);
    let _ = publish(State(state.clone()), headers, Json(sample()))
        .await
        .unwrap();
    let samples = state
        .live_position
        .sessions
        .lock()
        .unwrap()
        .get(&test_account(1))
        .unwrap()
        .samples
        .subscribe();
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    let receiver = TcpStream::connect(listener.local_addr()?).await?;
    let (sender, _) = listener.accept().await?;
    let worker = tokio::spawn(stream(
        sender,
        state.clone(),
        test_account(1),
        "synthetic-login".into(),
        samples,
    ));
    let mut receiver = tokio::io::BufReader::new(receiver);
    let mut line = String::new();
    tokio::time::timeout(Duration::from_secs(3), receiver.read_line(&mut line)).await??;
    state.store.connection()?.execute(
        "DELETE FROM auth_tokens WHERE session_id='synthetic-login'",
        crate::db::params![],
    )?;
    line.clear();
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(3), receiver.read_line(&mut line)).await??,
        0
    );
    worker.await?;
    assert!(state.live_position.sessions.lock().unwrap().is_empty());
    Ok(())
}

fn configure_accounts(state: &AppState) -> anyhow::Result<()> {
    state.configure_live_position(LivePositionConfig {
        bind: Ipv4Addr::LOCALHOST,
        receivers: vec![
            LivePositionReceiver {
                account_id: test_account(1),
                port: 10111,
                peer: Ipv4Addr::LOCALHOST,
            },
            LivePositionReceiver {
                account_id: test_account(2),
                port: 10112,
                peer: Ipv4Addr::new(127, 0, 0, 2),
            },
        ],
    })?;
    Ok(())
}

#[tokio::test]
async fn mirror_stream_sends_atomic_guidance_and_route_then_revocation_closes_it()
-> anyhow::Result<()> {
    use tokio::io::AsyncBufReadExt;
    let (_file, state) = fixture()?;
    configure_accounts(&state)?;
    state
        .store
        .grant_privacy_consent(test_account(1), "live_position", "test")?;
    let mut headers = headers(&state, test_account(1), "mirror-login")?;
    let Json(created) = start(State(state.clone()), headers.clone()).await.unwrap();
    assert_eq!(created.receiver.navigation_version, VERSION);
    headers.insert("X-Live-Session", created.session.parse()?);
    let route = Route {
        revision: imu_nav_protocol::Revision::new(1).unwrap(),
        geometry: vec![[-50.5, -30.25], [-50.49, -30.24]],
        summary: "Synthetic".into(),
        destination: [-50.49, -30.24],
    };
    publish_route(State(state.clone()), headers.clone(), Json(route.clone()))
        .await
        .unwrap();
    let guidance: Guidance = serde_json::from_str(include_str!(
        "../../../desktop/protocol/fixtures/guidance.json"
    ))?;
    let _ = publish_navigation(
        State(state.clone()),
        headers.clone(),
        Json(NavigationPublish {
            sample: sample(),
            guidance: Some(guidance.clone()),
        }),
    )
    .await
    .unwrap();
    let samples = state
        .live_position
        .sessions
        .lock()
        .unwrap()
        .get(&test_account(1))
        .unwrap()
        .samples
        .subscribe();
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    let receiver = TcpStream::connect(listener.local_addr()?).await?;
    let (sender, _) = listener.accept().await?;
    let worker = tokio::spawn(stream(
        sender,
        state.clone(),
        test_account(1),
        "mirror-login".into(),
        samples,
    ));
    let mut receiver = tokio::io::BufReader::new(receiver);
    let mut line = String::new();
    tokio::time::timeout(Duration::from_secs(3), receiver.read_line(&mut line)).await??;
    assert_eq!(line, sample().nmea());
    for byte in imu_nav_protocol::HANDSHAKE {
        receiver.get_mut().write_all(&[*byte]).await?;
    }
    line.clear();
    tokio::time::timeout(Duration::from_secs(3), receiver.read_line(&mut line)).await??;
    assert_eq!(
        imu_nav_protocol::decode(line.as_bytes())?,
        Frame::Hello { version: VERSION }
    );
    line.clear();
    tokio::time::timeout(Duration::from_secs(3), receiver.read_line(&mut line)).await??;
    let Frame::State {
        state: mirrored, ..
    } = imu_nav_protocol::decode(line.as_bytes())?
    else {
        panic!("mirror state");
    };
    assert_eq!(mirrored.guidance, Some(guidance));
    assert!(mirrored.valid());
    let mut assembly = imu_nav_protocol::RouteAssembly::default();
    loop {
        line.clear();
        tokio::time::timeout(Duration::from_secs(3), receiver.read_line(&mut line)).await??;
        if let Some(decoded) = assembly.accept(imu_nav_protocol::decode(line.as_bytes())?)? {
            assert_eq!(decoded, route);
            break;
        }
    }
    state
        .store
        .withdraw_privacy_consent(test_account(1), "live_position", &state.policy())?;
    state.live_position.stop(test_account(1));
    line.clear();
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(3), receiver.read_line(&mut line)).await??,
        0
    );
    worker.await?;
    assert!(state.live_position.sessions.lock().unwrap().is_empty());
    Ok(())
}

#[tokio::test]
async fn mirror_routes_enforce_ownership_ordering_and_expiry() -> anyhow::Result<()> {
    let (_file, state) = fixture()?;
    configure_accounts(&state)?;
    state
        .store
        .grant_privacy_consent(test_account(1), "live_position", "test")?;
    let mut headers = headers(&state, test_account(1), "route-login")?;
    let Json(created) = start(State(state.clone()), headers.clone()).await.unwrap();
    headers.insert("X-Live-Session", created.session.parse()?);
    let route = Route {
        revision: imu_nav_protocol::Revision::new(2).unwrap(),
        geometry: vec![[0.0, 0.0], [0.01, 0.01]],
        summary: String::new(),
        destination: [0.01, 0.01],
    };
    publish_route(State(state.clone()), headers.clone(), Json(route.clone()))
        .await
        .unwrap();
    publish_route(State(state.clone()), headers.clone(), Json(route.clone()))
        .await
        .unwrap();
    let mut older = route.clone();
    older.revision = imu_nav_protocol::Revision::new(1).unwrap();
    assert_eq!(
        publish_route(State(state.clone()), headers.clone(), Json(older))
            .await
            .unwrap_err()
            .0,
        StatusCode::CONFLICT
    );
    let mut bad_headers = headers.clone();
    bad_headers.insert("X-Live-Session", "b".repeat(64).parse()?);
    assert_eq!(
        publish_route(State(state.clone()), bad_headers, Json(route))
            .await
            .unwrap_err()
            .0,
        StatusCode::NOT_FOUND
    );
    let mut guidance: Guidance = serde_json::from_str(include_str!(
        "../../../desktop/protocol/fixtures/guidance.json"
    ))?;
    assert_eq!(
        publish_navigation(
            State(state.clone()),
            headers.clone(),
            Json(NavigationPublish {
                sample: sample(),
                guidance: Some(guidance.clone())
            })
        )
        .await
        .err()
        .unwrap()
        .0,
        StatusCode::CONFLICT
    );
    guidance.route_revision = Some(imu_nav_protocol::Revision::new(2).unwrap());
    let _ = publish_navigation(
        State(state.clone()),
        headers.clone(),
        Json(NavigationPublish {
            sample: sample(),
            guidance: Some(guidance),
        }),
    )
    .await
    .unwrap();
    {
        let mut sessions = state.live_position.sessions.lock().unwrap();
        let session = sessions.get_mut(&test_account(1)).unwrap();
        let published = session.samples.borrow().as_ref().unwrap().clone();
        session.samples.send_replace(Some(Arc::new(Published {
            sample: published.sample.clone(),
            guidance: published.guidance.clone(),
            route: published.route.clone(),
            received: Instant::now().checked_sub(SAMPLE_TTL).unwrap(),
        })));
    }
    state.live_position.prune();
    assert!(
        state
            .live_position
            .sessions
            .lock()
            .unwrap()
            .get(&test_account(1))
            .unwrap()
            .route
            .is_none()
    );
    Ok(())
}
