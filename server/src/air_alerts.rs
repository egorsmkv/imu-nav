//! Opt-in nationwide air-raid alerts from the `UkraineAlarm` v3 API.
//!
//! Provider webhooks only wake the worker. Published facts always come from an authenticated
//! provider snapshot, so a leaked callback URL alone cannot manufacture an alert or all-clear.

use crate::api::{ApiError, AppState, run_db};
use crate::auth;
use axum::body::Bytes;
use axum::extract::ws::{Message, WebSocket};
use axum::extract::{DefaultBodyLimit, Path, State, WebSocketUpgrade};
use axum::http::{HeaderMap, StatusCode, header};
use axum::routing::{get, post};
use axum::{Json, Router, response::IntoResponse};
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::sync::{Arc, RwLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::sync::{Notify, broadcast};

const PROVIDER_BASE: &str = "https://api.ukrainealarm.com";
const RECONCILE_EVERY: Duration = Duration::from_mins(5);
const REGISTER_RETRY: Duration = Duration::from_secs(30);
const STREAM_CHECK_EVERY: Duration = Duration::from_secs(60);
const MAX_PROVIDER_BYTES: usize = 2 * 1024 * 1024;

/// The operator keeps these values out of repository files and server logs.
#[derive(Clone)]
pub struct AirAlertConfig {
    pub api_key: String,
    pub webhook_secret: String,
    pub public_url: String,
    provider_base: String,
}

impl AirAlertConfig {
    /// Provider origin is fixed for operator configuration; tests replace it with a local stub.
    #[must_use]
    pub fn new(api_key: String, webhook_secret: String, public_url: String) -> Self {
        Self {
            api_key,
            webhook_secret,
            public_url,
            provider_base: PROVIDER_BASE.to_owned(),
        }
    }
}

#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd, Serialize)]
pub(crate) struct AlertRegion {
    region_id: String,
    name_uk: String,
    name_en: String,
}

#[derive(Clone, Debug)]
struct AlertSnapshot {
    active: Vec<AlertRegion>,
    updated_s: Option<i64>,
    stale: bool,
    sequence: u64,
}

impl Default for AlertSnapshot {
    fn default() -> Self {
        Self {
            active: Vec::new(),
            updated_s: None,
            stale: true,
            sequence: 0,
        }
    }
}

#[derive(Clone, Debug, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum AlertMessage {
    Snapshot {
        active: Vec<AlertRegion>,
        updated_s: Option<i64>,
        stale: bool,
        sequence: u64,
    },
    Changes {
        started: Vec<AlertRegion>,
        cleared: Vec<AlertRegion>,
        updated_s: i64,
        sequence: u64,
    },
}

/// One process-local publication hub; Compose deploys one server replica.
pub(crate) struct AirAlertHub {
    config: RwLock<Option<AirAlertConfig>>,
    snapshot: RwLock<AlertSnapshot>,
    events: broadcast::Sender<AlertMessage>,
    refresh: Notify,
}

impl AirAlertHub {
    pub(crate) fn new() -> Self {
        let (events, _) = broadcast::channel(256);
        Self {
            config: RwLock::new(None),
            snapshot: RwLock::new(AlertSnapshot::default()),
            events,
            refresh: Notify::new(),
        }
    }

    pub(crate) fn configured(&self) -> bool {
        self.config
            .read()
            .expect("alert config lock poisoned")
            .is_some()
    }

    fn config(&self) -> Option<AirAlertConfig> {
        self.config
            .read()
            .expect("alert config lock poisoned")
            .clone()
    }

    fn snapshot_message(&self) -> AlertMessage {
        let snapshot = self.snapshot.read().expect("alert snapshot lock poisoned");
        AlertMessage::Snapshot {
            active: snapshot.active.clone(),
            updated_s: snapshot.updated_s,
            stale: snapshot.stale,
            sequence: snapshot.sequence,
        }
    }

    /// A valid full snapshot is the only source of published starts and all-clears.
    fn apply(&self, active: Vec<AlertRegion>) -> bool {
        let mut snapshot = self.snapshot.write().expect("alert snapshot lock poisoned");
        let prior = snapshot
            .active
            .iter()
            .map(|region| (region.region_id.as_str(), region))
            .collect::<BTreeMap<_, _>>();
        let next = active
            .iter()
            .map(|region| (region.region_id.as_str(), region))
            .collect::<BTreeMap<_, _>>();
        let started = active
            .iter()
            .filter(|region| !prior.contains_key(region.region_id.as_str()))
            .cloned()
            .collect::<Vec<_>>();
        let cleared = snapshot
            .active
            .iter()
            .filter(|region| !next.contains_key(region.region_id.as_str()))
            .cloned()
            .collect::<Vec<_>>();
        let was_initialized = snapshot.updated_s.is_some();
        let was_stale = snapshot.stale;
        snapshot.active = active;
        snapshot.updated_s = Some(now_s());
        snapshot.stale = false;
        if !was_initialized {
            snapshot.sequence = snapshot.sequence.saturating_add(1);
            let _ = self.events.send(Self::snapshot_message_from(&snapshot));
            return !started.is_empty();
        }
        if started.is_empty() && cleared.is_empty() {
            if was_stale {
                snapshot.sequence = snapshot.sequence.saturating_add(1);
                let _ = self.events.send(Self::snapshot_message_from(&snapshot));
            }
            return false;
        }
        snapshot.sequence = snapshot.sequence.saturating_add(1);
        let _ = self.events.send(AlertMessage::Changes {
            started,
            cleared,
            updated_s: snapshot.updated_s.unwrap_or_default(),
            sequence: snapshot.sequence,
        });
        true
    }

    fn snapshot_message_from(snapshot: &AlertSnapshot) -> AlertMessage {
        AlertMessage::Snapshot {
            active: snapshot.active.clone(),
            updated_s: snapshot.updated_s,
            stale: snapshot.stale,
            sequence: snapshot.sequence,
        }
    }

    fn mark_stale(&self) {
        let mut snapshot = self.snapshot.write().expect("alert snapshot lock poisoned");
        if !snapshot.stale {
            snapshot.stale = true;
            snapshot.sequence = snapshot.sequence.saturating_add(1);
            let _ = self.events.send(Self::snapshot_message_from(&snapshot));
        }
    }
}

impl AppState {
    /// Enable provider integration only after startup settings have been validated.
    ///
    /// # Panics
    /// Panics if another thread poisoned the alert configuration lock.
    pub fn configure_air_alerts(&self, config: AirAlertConfig) {
        *self
            .air_alerts
            .config
            .write()
            .expect("alert config lock poisoned") = Some(config);
    }

    /// The provider worker is absent from local installations without an API key.
    #[must_use]
    pub fn spawn_air_alert_job(&self) -> Option<tokio::task::JoinHandle<()>> {
        self.air_alerts.configured().then(|| {
            let hub = self.air_alerts.clone();
            tokio::spawn(async move { run_provider(hub).await })
        })
    }
}

#[derive(Serialize)]
struct Preference {
    enabled: bool,
    available: bool,
}

#[derive(Deserialize)]
struct PreferenceUpdate {
    enabled: bool,
}

pub(crate) fn router() -> Router<AppState> {
    Router::new()
        .route(
            "/v1/air-alerts/preferences",
            get(preference).put(set_preference),
        )
        .route("/v1/air-alerts/stream", get(stream_upgrade))
        .route(
            "/v1/air-alerts/provider/{secret}",
            post(provider_callback).layer(DefaultBodyLimit::max(16 * 1024)),
        )
}

async fn preference(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Preference>, ApiError> {
    let account = auth::bearer_account(&state, &headers).await?;
    let store = state.store.clone();
    let enabled = run_db(move || store.air_alerts_enabled(account.id)).await?;
    Ok(Json(Preference {
        enabled,
        available: state.air_alerts.configured(),
    }))
}

async fn set_preference(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(update): Json<PreferenceUpdate>,
) -> Result<StatusCode, ApiError> {
    let account = auth::bearer_account(&state, &headers).await?;
    if update.enabled && !state.air_alerts.configured() {
        return Err(ApiError(
            StatusCode::SERVICE_UNAVAILABLE,
            "AIR_ALERTS_UNAVAILABLE",
        ));
    }
    let store = state.store.clone();
    run_db(move || store.set_air_alerts_enabled(account.id, update.enabled)).await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn stream_upgrade(
    State(state): State<AppState>,
    headers: HeaderMap,
    websocket: WebSocketUpgrade,
) -> Result<impl IntoResponse, ApiError> {
    let account = auth::bearer_account(&state, &headers).await?;
    if !state.air_alerts.configured() {
        return Err(ApiError(
            StatusCode::SERVICE_UNAVAILABLE,
            "AIR_ALERTS_UNAVAILABLE",
        ));
    }
    let store = state.store.clone();
    if !run_db(move || store.air_alerts_enabled(account.id)).await? {
        return Err(ApiError(StatusCode::FORBIDDEN, "AIR_ALERTS_DISABLED"));
    }
    let token = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .unwrap_or_default()
        .to_owned();
    Ok(websocket.on_upgrade(move |socket| stream(socket, state, token, account.id)))
}

async fn stream(mut socket: WebSocket, state: AppState, token: String, account_id: i64) {
    let mut events = state.air_alerts.events.subscribe();
    if !stream_allowed(&state, &token, account_id).await {
        return;
    }
    let snapshot = state.air_alerts.snapshot_message();
    let AlertMessage::Snapshot {
        sequence: mut last_sequence,
        ..
    } = snapshot
    else {
        unreachable!()
    };
    if send(&mut socket, &snapshot).await.is_err() {
        return;
    }
    let mut checks = tokio::time::interval(STREAM_CHECK_EVERY);
    loop {
        tokio::select! {
            _ = checks.tick() => {
                if !stream_allowed(&state, &token, account_id).await { break; }
            }
            result = events.recv() => match result {
                Ok(message) => {
                    let sequence = match &message {
                        AlertMessage::Changes { sequence, .. } | AlertMessage::Snapshot { sequence, .. } => *sequence,
                    };
                    if sequence <= last_sequence { continue; }
                    last_sequence = sequence;
                    if !stream_allowed(&state, &token, account_id).await || send(&mut socket, &message).await.is_err() { break; }
                }
                Err(broadcast::error::RecvError::Lagged(_)) => {
                    if !stream_allowed(&state, &token, account_id).await || send(&mut socket, &state.air_alerts.snapshot_message()).await.is_err() { break; }
                }
                Err(broadcast::error::RecvError::Closed) => break,
            },
            incoming = socket.next() => match incoming {
                Some(Ok(Message::Close(_)) | Err(_)) | None => break,
                _ => {}
            }
        }
    }
}

async fn stream_allowed(state: &AppState, token: &str, account_id: i64) -> bool {
    let store = state.store.clone();
    let token = token.to_owned();
    run_db(move || {
        let account = auth::account_for_token(&store, &token, "access")?;
        Ok(account.is_some_and(|(account, _)| account.id == account_id)
            && store.air_alerts_enabled(account_id)?)
    })
    .await
    .unwrap_or(false)
}

async fn send(socket: &mut WebSocket, message: &AlertMessage) -> anyhow::Result<()> {
    socket
        .send(Message::Text(serde_json::to_string(message)?.into()))
        .await?;
    Ok(())
}

async fn provider_callback(
    State(state): State<AppState>,
    Path(secret): Path<String>,
    _body: Bytes,
) -> StatusCode {
    let Some(config) = state.air_alerts.config() else {
        return StatusCode::NOT_FOUND;
    };
    let expected = Sha256::digest(config.webhook_secret.as_bytes());
    let actual = Sha256::digest(secret.as_bytes());
    let equal = expected
        .iter()
        .zip(actual.iter())
        .fold(0u8, |diff, (left, right)| diff | (left ^ right))
        == 0;
    if !equal {
        return StatusCode::NOT_FOUND;
    }
    state.air_alerts.refresh.notify_one();
    StatusCode::ACCEPTED
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ProviderRegion {
    region_id: String,
    region_type: String,
    region_name: Option<String>,
    region_eng_name: Option<String>,
    active_alerts: Option<Vec<ProviderAlert>>,
}

#[derive(Deserialize)]
struct ProviderAlert {
    #[serde(rename = "type")]
    alert_type: String,
}

fn parse_regions(body: &[u8]) -> anyhow::Result<Vec<AlertRegion>> {
    let regions: Vec<ProviderRegion> = serde_json::from_slice(body)?;
    let mut active = BTreeMap::new();
    for region in regions {
        if region.region_type != "State" {
            continue;
        }
        let alerts = region
            .active_alerts
            .ok_or_else(|| anyhow::anyhow!("provider state missing activeAlerts"))?;
        if alerts.iter().any(|alert| alert.alert_type == "AIR") {
            anyhow::ensure!(
                !region.region_id.is_empty(),
                "provider state missing regionId"
            );
            let name_uk = region
                .region_name
                .filter(|name| !name.is_empty())
                .unwrap_or_else(|| region.region_id.clone());
            let name_en = region
                .region_eng_name
                .filter(|name| !name.is_empty())
                .unwrap_or_else(|| name_uk.clone());
            active.insert(
                region.region_id.clone(),
                AlertRegion {
                    region_id: region.region_id,
                    name_uk,
                    name_en,
                },
            );
        }
    }
    Ok(active.into_values().collect())
}

async fn run_provider(hub: Arc<AirAlertHub>) {
    let Some(config) = hub.config() else {
        return;
    };
    let client = match reqwest::Client::builder()
        .timeout(Duration::from_secs(15))
        .build()
    {
        Ok(client) => client,
        Err(error) => {
            tracing::warn!(%error, "air alert HTTP client failed");
            return;
        }
    };
    let mut refresh_failed = if let Err(error) = fetch_snapshot(&client, &config, &hub).await {
        tracing::warn!(%error, "air alert initial fetch failed");
        hub.mark_stale();
        true
    } else {
        false
    };
    let mut registered = false;
    loop {
        if !registered {
            match register_webhook(&client, &config).await {
                Ok(()) => registered = true,
                Err(error) => tracing::warn!(%error, "air alert webhook registration failed"),
            }
        }
        let triggered = tokio::select! {
            () = hub.refresh.notified() => true,
            () = tokio::time::sleep(if registered && !refresh_failed { RECONCILE_EVERY } else { REGISTER_RETRY }) => false,
        };
        match fetch_snapshot(&client, &config, &hub).await {
            Ok(changed) if triggered && !changed => {
                refresh_failed = false;
                tokio::time::sleep(Duration::from_secs(5)).await;
                if let Err(error) = fetch_snapshot(&client, &config, &hub).await {
                    tracing::warn!(%error, "air alert refresh failed");
                    hub.mark_stale();
                    refresh_failed = true;
                }
            }
            Ok(_) => refresh_failed = false,
            Err(error) => {
                tracing::warn!(%error, "air alert refresh failed");
                hub.mark_stale();
                refresh_failed = true;
            }
        }
    }
}

async fn fetch_snapshot(
    client: &reqwest::Client,
    config: &AirAlertConfig,
    hub: &AirAlertHub,
) -> anyhow::Result<bool> {
    let response = client
        .get(format!("{}/api/v3/alerts", config.provider_base))
        .header("Authorization", &config.api_key)
        .send()
        .await?
        .error_for_status()?;
    if response
        .content_length()
        .is_some_and(|length| length > MAX_PROVIDER_BYTES as u64)
    {
        anyhow::bail!("provider response too large");
    }
    let mut bytes = Vec::new();
    let mut chunks = response.bytes_stream();
    while let Some(chunk) = chunks.next().await {
        let chunk = chunk?;
        anyhow::ensure!(
            bytes.len().saturating_add(chunk.len()) <= MAX_PROVIDER_BYTES,
            "provider response too large"
        );
        bytes.extend_from_slice(&chunk);
    }
    Ok(hub.apply(parse_regions(&bytes)?))
}

async fn register_webhook(client: &reqwest::Client, config: &AirAlertConfig) -> anyhow::Result<()> {
    let url = format!(
        "{}/v1/air-alerts/provider/{}",
        config.public_url.trim_end_matches('/'),
        config.webhook_secret
    );
    let endpoint = format!("{}/api/v3/webhook", config.provider_base);
    let body = serde_json::json!({"webHookUrl":url});
    let response = client
        .post(&endpoint)
        .header("Authorization", &config.api_key)
        .json(&body)
        .send()
        .await?;
    if response.status().is_success() {
        return Ok(());
    }
    client
        .patch(&endpoint)
        .header("Authorization", &config.api_key)
        .json(&body)
        .send()
        .await?
        .error_for_status()?;
    Ok(())
}

fn now_s() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| {
            i64::try_from(duration.as_secs()).unwrap_or(i64::MAX)
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::routing::get;
    use tokio_tungstenite::tungstenite::client::IntoClientRequest;

    async fn mock_provider(
        body: Arc<RwLock<String>>,
        post_status: StatusCode,
    ) -> (String, tokio::task::JoinHandle<()>) {
        let source = body.clone();
        let app = Router::new()
            .route(
                "/api/v3/alerts",
                get(move || {
                    let source = source.clone();
                    async move { source.read().unwrap().clone() }
                }),
            )
            .route(
                "/api/v3/webhook",
                post(move || async move { post_status }).patch(|| async { StatusCode::NO_CONTENT }),
            );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        (format!("http://{address}"), task)
    }

    fn test_config(provider_base: String) -> AirAlertConfig {
        AirAlertConfig {
            api_key: "test-api-key".to_owned(),
            webhook_secret: "abcdefghijklmnopqrstuvwxyz012345".to_owned(),
            public_url: "https://cells.example.org".to_owned(),
            provider_base,
        }
    }

    #[test]
    fn filters_air_to_oblasts_and_diffs_without_repeating_notifications() {
        let hub = AirAlertHub::new();
        let body = r#"[{"regionId":"1","regionType":"State","regionName":"Київська","regionEngName":"Kyiv","activeAlerts":[{"type":"AIR"}]},{"regionId":"2","regionType":"District","activeAlerts":[{"type":"AIR"}]},{"regionId":"3","regionType":"State","activeAlerts":[{"type":"ARTILLERY"}]}]"#.as_bytes();
        let active = parse_regions(body).unwrap();
        assert_eq!(active.len(), 1);
        let mut events = hub.events.subscribe();
        hub.apply(active.clone());
        assert!(matches!(
            events.try_recv().unwrap(),
            AlertMessage::Snapshot { .. }
        ));
        assert!(!hub.apply(active));
        assert!(events.try_recv().is_err());
        hub.apply(Vec::new());
        assert!(
            matches!(events.try_recv().unwrap(), AlertMessage::Changes { cleared, .. } if cleared.len() == 1)
        );
        assert!(parse_regions(br#"[{"regionId":"1","regionType":"State"}]"#).is_err());
    }

    #[tokio::test]
    async fn provider_registration_and_refresh_use_api_key_and_verified_snapshot() {
        let body = Arc::new(RwLock::new(
            r#"[{"regionId":"1","regionType":"State","regionName":"Київська","regionEngName":"Kyiv","activeAlerts":[{"type":"AIR"}]}]"#.to_owned(),
        ));
        let source = body.clone();
        let app = Router::new()
            .route("/api/v3/alerts", get(move |headers: HeaderMap| {
                let source = source.clone();
                async move {
                    assert_eq!(headers.get("Authorization").unwrap(), "test-api-key");
                    source.read().unwrap().clone()
                }
            }))
            .route("/api/v3/webhook", post(|headers: HeaderMap, Json(body): Json<serde_json::Value>| async move {
                assert_eq!(headers.get("Authorization").unwrap(), "test-api-key");
                assert_eq!(body["webHookUrl"], "https://cells.example.org/v1/air-alerts/provider/abcdefghijklmnopqrstuvwxyz012345");
                StatusCode::NO_CONTENT
            }))
            .with_state(());
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        let config = AirAlertConfig {
            api_key: "test-api-key".to_owned(),
            webhook_secret: "abcdefghijklmnopqrstuvwxyz012345".to_owned(),
            public_url: "https://cells.example.org".to_owned(),
            provider_base: format!("http://{address}"),
        };
        let client = reqwest::Client::new();
        let hub = AirAlertHub::new();
        register_webhook(&client, &config).await.unwrap();
        assert!(fetch_snapshot(&client, &config, &hub).await.unwrap());
        assert_eq!(hub.snapshot.read().unwrap().active.len(), 1);
        let mut events = hub.events.subscribe();
        *body.write().unwrap() = "[]".to_owned();
        assert!(fetch_snapshot(&client, &config, &hub).await.unwrap());
        assert!(
            matches!(events.try_recv().unwrap(), AlertMessage::Changes { cleared, .. } if cleared.len() == 1)
        );
        task.abort();
    }

    #[test]
    fn malformed_regions_do_not_invent_an_all_clear() {
        let hub = AirAlertHub::new();
        let active = parse_regions(
            br#"[{"regionId":"14","regionType":"State","activeAlerts":[{"type":"AIR"}]}]"#,
        )
        .unwrap();
        assert_eq!(active[0].name_uk, "14");
        assert_eq!(active[0].name_en, "14");
        hub.apply(active.clone());
        assert!(
            parse_regions(br#"[{"regionId":"14","regionType":"State","activeAlerts":null}]"#)
                .is_err()
        );
        assert!(
            parse_regions(
                br#"[{"regionId":"","regionType":"State","activeAlerts":[{"type":"AIR"}]}]"#
            )
            .is_err()
        );
        assert_eq!(hub.snapshot.read().unwrap().active, active);
        hub.mark_stale();
        assert!(hub.snapshot.read().unwrap().stale);
        hub.apply(active);
        assert!(!hub.snapshot.read().unwrap().stale);
    }

    #[tokio::test]
    async fn webhook_registration_updates_an_existing_provider_subscription() {
        let (base, task) =
            mock_provider(Arc::new(RwLock::new("[]".to_owned())), StatusCode::CONFLICT).await;
        let config = test_config(base);
        register_webhook(&reqwest::Client::new(), &config)
            .await
            .unwrap();
        task.abort();
    }

    #[tokio::test]
    async fn provider_failure_marks_status_stale_without_sending_a_false_all_clear() {
        let body = Arc::new(RwLock::new(
            r#"[{"regionId":"14","regionType":"State","regionName":"Київська","regionEngName":"Kyiv","activeAlerts":[{"type":"AIR"}]}]"#.to_owned(),
        ));
        let (base, server) = mock_provider(body.clone(), StatusCode::NO_CONTENT).await;
        let hub = Arc::new(AirAlertHub::new());
        *hub.config.write().unwrap() = Some(test_config(base));
        let mut events = hub.events.subscribe();
        let worker = tokio::spawn(run_provider(hub.clone()));
        let initial = tokio::time::timeout(Duration::from_secs(3), events.recv())
            .await
            .unwrap()
            .unwrap();
        assert!(
            matches!(initial, AlertMessage::Snapshot { stale: false, active, .. } if active.len() == 1)
        );

        *body.write().unwrap() = "broken".to_owned();
        hub.refresh.notify_one();
        let failed = tokio::time::timeout(Duration::from_secs(3), events.recv())
            .await
            .unwrap()
            .unwrap();
        assert!(
            matches!(failed, AlertMessage::Snapshot { stale: true, active, .. } if active.len() == 1)
        );

        *body.write().unwrap() = "[]".to_owned();
        hub.refresh.notify_one();
        let clear = tokio::time::timeout(Duration::from_secs(3), events.recv())
            .await
            .unwrap()
            .unwrap();
        assert!(matches!(clear, AlertMessage::Changes { cleared, .. } if cleared.len() == 1));
        assert!(!hub.snapshot.read().unwrap().stale);
        worker.abort();
        server.abort();
    }

    #[tokio::test]
    async fn user_stream_starts_with_a_snapshot_and_stops_after_opt_out() -> anyhow::Result<()> {
        let database = tempfile::NamedTempFile::new()?;
        let store = crate::CellStore::open(database.path())?;
        crate::create_admin(&store, "driver@example.org", "correct horse battery staple")?;
        let state = AppState::new(
            store,
            crate::ServerConfig {
                mail: None,
                policy: crate::Policy::default(),
                trust_proxy: false,
                secure_cookies: false,
                privacy: None,
            },
        )?;
        state.configure_air_alerts(test_config(PROVIDER_BASE.to_owned()));
        let hub = state.air_alerts.clone();
        let kyiv = AlertRegion {
            region_id: "14".to_owned(),
            name_uk: "Київська".to_owned(),
            name_en: "Kyiv".to_owned(),
        };
        hub.apply(vec![kyiv.clone()]);
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
        let address = listener.local_addr()?;
        let server = tokio::spawn(async move {
            axum::serve(
                listener,
                crate::router(state).into_make_service_with_connect_info::<std::net::SocketAddr>(),
            )
            .await
            .unwrap();
        });
        let client = reqwest::Client::new();
        let base = format!("http://{address}");
        let login: serde_json::Value = client.post(format!("{base}/v1/auth/login"))
            .json(&serde_json::json!({"email":"driver@example.org","password":"correct horse battery staple"}))
            .send().await?.json().await?;
        let token = login["access_token"].as_str().unwrap();
        client
            .put(format!("{base}/v1/air-alerts/preferences"))
            .bearer_auth(token)
            .json(&serde_json::json!({"enabled":true}))
            .send()
            .await?
            .error_for_status()?;
        let mut request = format!("ws://{address}/v1/air-alerts/stream").into_client_request()?;
        request
            .headers_mut()
            .insert("Authorization", format!("Bearer {token}").parse()?);
        let (mut socket, _) = tokio_tungstenite::connect_async(request).await?;
        let initial = socket.next().await.unwrap()?;
        let initial: serde_json::Value = serde_json::from_str(initial.to_text()?)?;
        assert_eq!(initial["type"], "snapshot");
        assert_eq!(initial["active"].as_array().unwrap().len(), 1);

        hub.apply(Vec::new());
        let clear = tokio::time::timeout(Duration::from_secs(3), socket.next())
            .await?
            .unwrap()?;
        let clear: serde_json::Value = serde_json::from_str(clear.to_text()?)?;
        assert_eq!(clear["type"], "changes");
        assert_eq!(clear["cleared"].as_array().unwrap().len(), 1);

        client
            .put(format!("{base}/v1/air-alerts/preferences"))
            .bearer_auth(token)
            .json(&serde_json::json!({"enabled":false}))
            .send()
            .await?
            .error_for_status()?;
        hub.apply(vec![kyiv]);
        let closed = tokio::time::timeout(Duration::from_secs(3), socket.next()).await?;
        assert!(
            closed.is_none_or(
                |message| message.is_err() || message.is_ok_and(|value| value.is_close())
            )
        );
        server.abort();
        Ok(())
    }
}
