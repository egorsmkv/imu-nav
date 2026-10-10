use super::*;
use anyhow::Result;
use base64::{Engine as _, engine::general_purpose::STANDARD};
use futures_util::SinkExt;
use tokio_tungstenite::{
    MaybeTlsStream, WebSocketStream,
    tungstenite::{Message as ClientMessage, client::IntoClientRequest},
};

type ClientSocket = WebSocketStream<MaybeTlsStream<tokio::net::TcpStream>>;
const INPUT_LIMIT: usize = 4 * 1024;

#[tokio::test]
async fn cancelled_database_waiter_keeps_admission_until_the_worker_finishes() -> Result<()> {
    for privacy_write in [false, true] {
        let gate = Arc::new(tokio::sync::RwLock::new(()));
        let guard: Box<dyn Send> = if privacy_write {
            Box::new(gate.clone().write_owned().await)
        } else {
            Box::new(gate.clone().read_owned().await)
        };
        let (entered, started) = tokio::sync::oneshot::channel();
        let (release, wait) = std::sync::mpsc::channel();
        let (finished, done) = tokio::sync::oneshot::channel();
        let task = tokio::spawn(run_db_guarded(guard, move || {
            entered.send(()).unwrap();
            wait.recv()?;
            finished.send(()).unwrap();
            Ok(())
        }));
        started.await?;
        task.abort();
        assert!(matches!(task.await, Err(error) if error.is_cancelled()));
        let held_while_running = if privacy_write {
            gate.try_read().is_err()
        } else {
            gate.try_write().is_err()
        };
        release.send(())?;
        done.await?;
        let _released = tokio::time::timeout(Duration::from_secs(5), gate.write()).await?;
        assert!(
            held_while_running,
            "cancelling the waiter must not admit conflicting work before its worker finishes"
        );
    }
    Ok(())
}

#[tokio::test]
async fn completed_database_work_keeps_admission_until_publication_and_releases_on_error()
-> Result<()> {
    let gate = Arc::new(tokio::sync::RwLock::new(()));
    let (result, guard) = run_db_guarded(gate.clone().write_owned().await, || Ok(42))
        .await
        .unwrap();
    assert_eq!(result, 42);
    assert!(
        gate.try_read().is_err(),
        "publication must retain admission after the worker returns"
    );
    drop(guard);
    assert!(gate.try_read().is_ok());
    assert!(
        run_db_guarded(gate.clone().write_owned().await, || Err::<(), _>(
            anyhow::anyhow!("synthetic failure")
        ))
        .await
        .is_err()
    );
    assert!(
        gate.try_read().is_ok(),
        "a failed worker must release admission"
    );
    Ok(())
}

struct TestServer {
    state: AppState,
    address: SocketAddr,
    token: String,
    task: tokio::task::JoinHandle<()>,
    _database: tempfile::NamedTempFile,
}

impl Drop for TestServer {
    fn drop(&mut self) {
        self.task.abort();
    }
}

impl TestServer {
    async fn start() -> Result<Self> {
        let database = tempfile::NamedTempFile::new()?;
        let store = CellStore::open(database.path())?;
        crate::auth::create_admin(&store, "admin@example.org", "correct horse battery staple")?;
        let token = auth::token();
        store.connection()?.execute(
            "INSERT INTO auth_tokens(token_hash,user_id,session_id,kind,expires_s) SELECT ?1,id,'security-test','access',?2 FROM users WHERE email='admin@example.org'",
            crate::db::params![auth::digest(&token), now_s() + 900],
        )?;
        let state = AppState::new(
            store,
            ServerConfig {
                trip_archive: crate::TripArchiveLimits::default(),
                mail: None,
                policy: Policy::default(),
                trust_proxy: false,
                secure_cookies: false,
                privacy: None,
            },
        )?;
        state.configure_air_alerts(crate::AirAlertConfig::new(
            "synthetic-provider-key".into(),
            "abcdefghijklmnopqrstuvwxyz012345".into(),
            "https://cells.example.org".into(),
        ));
        let account_id = state.store.connection()?.query_row(
            "SELECT id FROM users WHERE email='admin@example.org'",
            crate::db::params![],
            |row| row.get(0),
        )?;
        state.store.set_air_alerts_enabled(account_id, true)?;
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
        let address = listener.local_addr()?;
        let app = router(state.clone()).into_make_service_with_connect_info::<SocketAddr>();
        let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        Ok(Self {
            state,
            address,
            token,
            task,
            _database: database,
        })
    }

    async fn connect(&self, path: &str, basic: bool) -> Result<ClientSocket> {
        let mut request = format!("ws://{}{path}", self.address).into_client_request()?;
        let authorization = if basic {
            format!(
                "Basic {}",
                STANDARD.encode("admin@example.org:correct horse battery staple")
            )
        } else {
            format!("Bearer {}", self.token)
        };
        request
            .headers_mut()
            .insert("authorization", authorization.parse()?);
        let (mut socket, _) = tokio_tungstenite::connect_async(request).await?;
        let initial = tokio::time::timeout(Duration::from_secs(5), socket.next())
            .await?
            .expect("initial stream message")?;
        let message: serde_json::Value = serde_json::from_str(initial.to_text()?)?;
        assert_eq!(
            message["type"],
            if path == "/v1/events" {
                "ready"
            } else {
                "snapshot"
            }
        );
        Ok(socket)
    }

    fn publish(&self) {
        self.state
            .events
            .send(ServerEvent::Ready { published: 42 })
            .unwrap();
    }
}

async fn assert_closed(socket: &mut ClientSocket) -> Result<()> {
    let next = tokio::time::timeout(Duration::from_secs(5), socket.next()).await?;
    assert!(
        matches!(next, None | Some(Err(_) | Ok(ClientMessage::Close(_)))),
        "the server must close without sending another data message: {next:?}"
    );
    Ok(())
}

#[tokio::test]
async fn bearer_admin_stream_stops_after_revocation_expiry_demotion_or_suspension() -> Result<()> {
    for mutation in [
        "DELETE FROM auth_tokens",
        "UPDATE auth_tokens SET expires_s=0",
        "UPDATE users SET admin=0",
        "UPDATE users SET suspended=1",
    ] {
        let server = TestServer::start().await?;
        let mut socket = server.connect("/v1/events", false).await?;
        server.state.store.connection()?.execute_batch(mutation)?;
        server.publish();
        assert_closed(&mut socket).await?;
    }
    Ok(())
}

#[tokio::test]
async fn idle_admin_stream_rechecks_revocation_without_waiting_for_an_event() -> Result<()> {
    let server = TestServer::start().await?;
    let mut socket = server.connect("/v1/events", false).await?;
    server
        .state
        .store
        .connection()?
        .execute_batch("DELETE FROM auth_tokens")?;
    tokio::time::pause();
    tokio::time::advance(Duration::from_secs(61)).await;
    tokio::time::resume();
    assert_closed(&mut socket).await?;
    Ok(())
}

#[tokio::test]
async fn lagged_admin_stream_rechecks_before_resync() -> Result<()> {
    let server = TestServer::start().await?;
    let mut socket = server.connect("/v1/events", false).await?;
    server
        .state
        .store
        .connection()?
        .execute_batch("DELETE FROM auth_tokens")?;
    // No await until all sends complete: the current-thread stream receiver must lag.
    for _ in 0..2048 {
        server.publish();
    }
    assert_closed(&mut socket).await?;
    Ok(())
}

#[tokio::test]
async fn basic_admin_stream_stops_after_credential_change_demotion_or_suspension() -> Result<()> {
    for mutation in [
        "UPDATE users SET password_hash='changed-test-hash'",
        "UPDATE users SET email='changed@example.org'",
        "UPDATE users SET admin=0",
        "UPDATE users SET suspended=1",
    ] {
        let server = TestServer::start().await?;
        let mut socket = server.connect("/v1/events", true).await?;
        server.state.store.connection()?.execute_batch(mutation)?;
        server.publish();
        assert_closed(&mut socket).await?;
    }
    Ok(())
}

#[tokio::test]
async fn valid_admin_stream_keeps_sending_and_accepts_bounded_input() -> Result<()> {
    for basic in [false, true] {
        let server = TestServer::start().await?;
        let mut socket = server.connect("/v1/events", basic).await?;
        socket
            .send(ClientMessage::Text("x".repeat(INPUT_LIMIT).into()))
            .await?;
        socket
            .send(ClientMessage::Ping(vec![1, 2, 3].into()))
            .await?;
        let pong = tokio::time::timeout(Duration::from_secs(5), socket.next())
            .await?
            .unwrap()?;
        assert!(matches!(pong, ClientMessage::Pong(_)));
        server.publish();
        let next = tokio::time::timeout(Duration::from_secs(5), socket.next())
            .await?
            .unwrap()?;
        let event: serde_json::Value = serde_json::from_str(next.to_text()?)?;
        assert_eq!(event["published"], 42);
        socket.close(None).await?;
    }
    Ok(())
}

#[tokio::test]
async fn both_streams_reject_oversized_input_frames() -> Result<()> {
    for path in ["/v1/events", "/v1/air-alerts/stream"] {
        let server = TestServer::start().await?;
        let mut socket = server.connect(path, false).await?;
        socket
            .send(ClientMessage::Binary(vec![0; INPUT_LIMIT + 1].into()))
            .await?;
        socket.send(ClientMessage::Ping(vec![1].into())).await?;
        assert_closed(&mut socket).await?;
    }
    Ok(())
}

#[tokio::test]
async fn both_streams_bound_reassembled_fragmented_messages() -> Result<()> {
    use tokio_tungstenite::tungstenite::protocol::frame::{
        Frame,
        coding::{Data, OpCode},
    };
    for path in ["/v1/events", "/v1/air-alerts/stream"] {
        let server = TestServer::start().await?;
        let mut socket = server.connect(path, false).await?;
        for (opcode, final_frame) in [(Data::Binary, false), (Data::Continue, true)] {
            socket
                .send(ClientMessage::Frame(Frame::message(
                    vec![0; INPUT_LIMIT / 2 + 1],
                    OpCode::Data(opcode),
                    final_frame,
                )))
                .await?;
        }
        socket.send(ClientMessage::Ping(vec![1].into())).await?;
        assert_closed(&mut socket).await?;
    }
    Ok(())
}
