//! Ephemeral account-owned navigation streams. Raw NMEA is available only on a private VPN interface.

use crate::api::{ApiError, AppState, run_db};
use crate::auth;
use axum::extract::{DefaultBodyLimit, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::{get, post};
use axum::{Json, Router};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::Ipv4Addr;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};
use tokio::io::AsyncWriteExt;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{Semaphore, watch};
use tokio::task::JoinSet;

const MAX_SESSIONS: usize = 128;
const SAMPLE_TTL: Duration = Duration::from_secs(3);
const SESSION_TTL: Duration = Duration::from_secs(30);
const WRITE_TIMEOUT: Duration = Duration::from_secs(2);
const AUTH_INTERVAL: Duration = Duration::from_millis(500);

/// Operator-owned VPN settings. There is no public listener or application-level NMEA authentication.
#[derive(Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LivePositionConfig {
    pub bind: Ipv4Addr,
    pub receivers: Vec<LivePositionReceiver>,
}

/// One account's dedicated VPN port and exact receiver peer address.
#[derive(Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LivePositionReceiver {
    pub account_id: i64,
    pub port: u16,
    pub peer: Ipv4Addr,
}

impl LivePositionConfig {
    /// Reject wildcard/public binds, duplicate ports/accounts and unbounded listener configurations.
    ///
    /// # Errors
    /// Returns an error when these settings cannot be safely exposed on a private VPN.
    pub fn validate(&self) -> anyhow::Result<()> {
        anyhow::ensure!(
            private_address(self.bind),
            "live_position requires a private VPN IPv4 bind"
        );
        anyhow::ensure!(
            !self.receivers.is_empty() && self.receivers.len() <= MAX_SESSIONS,
            "invalid live_position receiver count"
        );
        let mut accounts = std::collections::HashSet::new();
        let mut ports = std::collections::HashSet::new();
        for receiver in &self.receivers {
            anyhow::ensure!(
                receiver.account_id > 0 && receiver.port >= 1024 && private_address(receiver.peer),
                "invalid live_position receiver"
            );
            anyhow::ensure!(
                accounts.insert(receiver.account_id) && ports.insert(receiver.port),
                "duplicate live_position receiver"
            );
        }
        Ok(())
    }
}

fn private_address(address: Ipv4Addr) -> bool {
    let octets = address.octets();
    address.is_private()
        || address.is_loopback()
        || (octets[0] == 100 && (64..=127).contains(&octets[1]))
}

/// Validated structured input. Elapsed time orders measurements without trusting the Android wall clock.
#[derive(Clone, Deserialize)]
#[serde(deny_unknown_fields)]
struct Sample {
    sequence: u64,
    lat: f64,
    lon: f64,
    speed_mps: f64,
    bearing_deg: f64,
    uncertainty_m: f64,
    source: String,
    elapsed_ms: u64,
    utc_ms: i64,
    age_ms: u64,
}

impl Sample {
    fn valid(&self) -> bool {
        self.sequence > 0
            && (-90.0..=90.0).contains(&self.lat)
            && (-180.0..=180.0).contains(&self.lon)
            && (0.0..=1_000.0).contains(&self.speed_mps)
            && (0.0..360.0).contains(&self.bearing_deg)
            && self.uncertainty_m.is_finite()
            && self.uncertainty_m >= 0.0
            && matches!(
                self.source.as_str(),
                "GPS" | "GPS_SUSPECT" | "DR" | "DR_NET" | "DR_OBD" | "DR_STOPPED" | "CELL"
            )
            && (0..=4_102_444_799_999).contains(&self.utc_ms)
            && self.age_ms <= 2_000
    }

    fn nmea(&self) -> String {
        let date =
            time::OffsetDateTime::from_unix_timestamp_nanos(i128::from(self.utc_ms) * 1_000_000)
                .expect("validated UTC range");
        let body = format!(
            "GPRMC,{:02}{:02}{:02}.{:03},A,{},{},{},{},{:.3},{:.3},{:02}{:02}{:02},,,E",
            date.hour(),
            date.minute(),
            date.second(),
            date.millisecond(),
            coordinate(self.lat, 2),
            if self.lat < 0.0 { "S" } else { "N" },
            coordinate(self.lon, 3),
            if self.lon < 0.0 { "W" } else { "E" },
            self.speed_mps / 0.514_444_444_444_444_5,
            self.bearing_deg,
            date.day(),
            u8::from(date.month()),
            date.year() % 100,
        );
        let checksum = body.bytes().fold(0, |value, byte| value ^ byte);
        format!("${body}*{checksum:02X}\r\n")
    }
}

// Coordinate bounds make the rounded integer exactly representable and safely convertible.
#[allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss
)]
fn coordinate(degrees: f64, width: usize) -> String {
    let units = (degrees.abs() * 60.0 * 1_000_000.0).round() as u64;
    let whole = units / 60_000_000;
    let minutes = (units % 60_000_000) as f64 / 1_000_000.0;
    format!("{whole:0width$}{minutes:09.6}")
}

struct Published {
    sample: Sample,
    received: Instant,
}

struct Session {
    id: String,
    login_id: String,
    updated: Instant,
    sequence: u64,
    elapsed_ms: Option<u64>,
    samples: watch::Sender<Option<Arc<Published>>>,
    slots: Arc<Semaphore>,
}

/// Only the current sample and bounded active sessions survive in memory; nothing is archived.
#[derive(Default)]
pub(crate) struct LivePositionHub {
    config: OnceLock<LivePositionConfig>,
    sessions: Mutex<HashMap<i64, Session>>,
}

impl LivePositionHub {
    fn stop_login(&self, account: i64, login_id: &str) {
        let mut sessions = self.sessions.lock().expect("live session lock");
        if sessions
            .get(&account)
            .is_some_and(|session| session.login_id == login_id)
            && let Some(session) = sessions.remove(&account)
        {
            session.samples.send_replace(None);
        }
    }

    pub(crate) fn stop(&self, account: i64) {
        if let Some(session) = self
            .sessions
            .lock()
            .expect("live session lock")
            .remove(&account)
        {
            session.samples.send_replace(None);
        }
    }

    fn prune(&self) {
        self.sessions
            .lock()
            .expect("live session lock")
            .retain(|_, session| {
                let stale = session
                    .samples
                    .borrow()
                    .as_ref()
                    .is_some_and(|sample| sample.received.elapsed() >= SAMPLE_TTL);
                if stale {
                    session.samples.send_replace(None);
                }
                if session.updated.elapsed() >= SESSION_TTL {
                    session.samples.send_replace(None);
                    false
                } else {
                    true
                }
            });
    }
}

impl AppState {
    /// Enable live sharing explicitly before starting the HTTP server.
    ///
    /// # Errors
    /// Returns an error for unsafe settings, a missing operator notice or repeated configuration.
    pub fn configure_live_position(&self, config: LivePositionConfig) -> anyhow::Result<()> {
        config.validate()?;
        anyhow::ensure!(
            self.config.privacy.is_some(),
            "live_position requires an operator privacy notice"
        );
        self.live_position
            .config
            .set(config)
            .map_err(|_| anyhow::anyhow!("live_position already configured"))
    }

    /// Bind all VPN listeners before accepting HTTP requests. Dropping the returned set stops listeners and receivers.
    ///
    /// # Errors
    /// Returns an error if a configured VPN address or port cannot be bound.
    pub async fn spawn_live_position(&self) -> anyhow::Result<JoinSet<()>> {
        let mut jobs = JoinSet::new();
        if let Some(config) = self.live_position.config.get() {
            for receiver in &config.receivers {
                let listener = TcpListener::bind((config.bind, receiver.port)).await?;
                let state = self.clone();
                let receiver = receiver.clone();
                jobs.spawn(async move { accept_receivers(listener, state, receiver).await });
            }
            let hub = self.live_position.clone();
            jobs.spawn(async move {
                let mut interval = tokio::time::interval(Duration::from_secs(1));
                loop {
                    interval.tick().await;
                    hub.prune();
                }
            });
        }
        Ok(jobs)
    }
}

pub(crate) fn router() -> Router<AppState> {
    Router::new()
        .route("/v1/live", get(capability))
        .route("/v1/live/session", post(start).put(publish).delete(stop))
        .layer(DefaultBodyLimit::max(2_048))
}

#[derive(Serialize)]
struct ReceiverInfo {
    address: Ipv4Addr,
    port: u16,
    receivers: usize,
}

fn receiver_info(state: &AppState, account: i64) -> Result<ReceiverInfo, ApiError> {
    let config = state
        .live_position
        .config
        .get()
        .ok_or(ApiError(StatusCode::SERVICE_UNAVAILABLE, "LIVE_DISABLED"))?;
    let receiver = config
        .receivers
        .iter()
        .find(|receiver| receiver.account_id == account)
        .ok_or(ApiError(StatusCode::FORBIDDEN, "RECEIVER_NOT_CONFIGURED"))?;
    let receivers = state
        .live_position
        .sessions
        .lock()
        .expect("live session lock")
        .get(&account)
        .map_or(0, |session| 4 - session.slots.available_permits());
    tracing::debug!(receivers, "live position receiver status");
    Ok(ReceiverInfo {
        address: config.bind,
        port: receiver.port,
        receivers,
    })
}

async fn capability(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<ReceiverInfo>, ApiError> {
    let account = auth::bearer_account(&state, &headers).await?;
    receiver_info(&state, account.id).map(Json)
}

async fn authorized(state: &AppState, headers: &HeaderMap) -> Result<(i64, String), ApiError> {
    let (account, session) = auth::bearer_account_session(state, headers).await?;
    if !eligible(state, account.id, &session).await? {
        state.live_position.stop_login(account.id, &session);
        return Err(ApiError(StatusCode::FORBIDDEN, "LIVE_CONSENT_REQUIRED"));
    }
    receiver_info(state, account.id)?;
    Ok((account.id, session))
}

async fn eligible(state: &AppState, account: i64, session: &str) -> Result<bool, ApiError> {
    let store = state.store.clone();
    let session = session.to_owned();
    let version = state
        .config
        .privacy
        .as_ref()
        .map(|notice| notice.version.clone())
        .unwrap_or_default();
    run_db(move || {
        let connection = store.connection()?;
        let valid: bool = connection.query_row(
            "SELECT EXISTS(SELECT 1 FROM users JOIN auth_tokens ON users.id=auth_tokens.user_id WHERE users.id=?1 AND users.suspended=0 AND users.email_verified=1 AND users.sharing_enabled=1 AND auth_tokens.session_id=?2 AND auth_tokens.kind='refresh' AND auth_tokens.expires_s>?3)",
            crate::db::params![account, session, auth::now_s()], |row| row.get(0),
        )?;
        drop(connection);
        Ok(valid && store.has_privacy_consent(account, "live_position", &version)?)
    }).await
}

#[derive(Serialize)]
struct Started {
    session: String,
    receiver: ReceiverInfo,
}

async fn start(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Started>, ApiError> {
    let _gate = state.write_gate.read().await;
    let (account, login_id) = authorized(&state, &headers).await?;
    state.live_position.prune();
    let receiver = receiver_info(&state, account)?;
    let mut sessions = state
        .live_position
        .sessions
        .lock()
        .expect("live session lock");
    if sessions.contains_key(&account) {
        return Err(ApiError(StatusCode::CONFLICT, "LIVE_ALREADY_ACTIVE"));
    }
    if sessions.len() >= MAX_SESSIONS {
        return Err(ApiError(StatusCode::TOO_MANY_REQUESTS, "LIVE_LIMIT"));
    }
    let mut random = [0u8; 32];
    rand::rng().fill_bytes(&mut random);
    let id = random
        .iter()
        .fold(String::with_capacity(64), |mut text, byte| {
            use std::fmt::Write;
            write!(text, "{byte:02x}").expect("string write");
            text
        });
    let (samples, _) = watch::channel(None);
    sessions.insert(
        account,
        Session {
            id: id.clone(),
            login_id,
            updated: Instant::now(),
            sequence: 0,
            elapsed_ms: None,
            samples,
            slots: Arc::new(Semaphore::new(4)),
        },
    );
    tracing::info!(sessions = sessions.len(), "live position session started");
    Ok(Json(Started {
        session: id,
        receiver,
    }))
}

fn session_header(headers: &HeaderMap) -> Result<&str, ApiError> {
    headers
        .get("X-Live-Session")
        .and_then(|value| value.to_str().ok())
        .filter(|value| value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit()))
        .ok_or(ApiError(StatusCode::BAD_REQUEST, "INVALID_LIVE_SESSION"))
}

async fn publish(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(sample): Json<Sample>,
) -> Result<Json<ReceiverInfo>, ApiError> {
    if !sample.valid() {
        return Err(ApiError(StatusCode::BAD_REQUEST, "INVALID_LIVE_SAMPLE"));
    }
    let _gate = state.write_gate.read().await;
    let (account, login_id) = authorized(&state, &headers).await?;
    let id = session_header(&headers)?;
    state.live_position.prune();
    let receiver = receiver_info(&state, account)?;
    let mut sessions = state
        .live_position
        .sessions
        .lock()
        .expect("live session lock");
    let session = sessions
        .get_mut(&account)
        .filter(|session| session.id == id && session.login_id == login_id)
        .ok_or(ApiError(StatusCode::NOT_FOUND, "LIVE_SESSION_NOT_FOUND"))?;
    if sample.sequence <= session.sequence
        || session
            .elapsed_ms
            .is_some_and(|elapsed| sample.elapsed_ms <= elapsed)
    {
        return Err(ApiError(StatusCode::CONFLICT, "LIVE_SAMPLE_ORDER"));
    }
    if session.sequence > 0 && session.updated.elapsed() < Duration::from_millis(250) {
        return Err(ApiError(StatusCode::TOO_MANY_REQUESTS, "LIVE_RATE_LIMIT"));
    }
    session.sequence = sample.sequence;
    session.elapsed_ms = Some(sample.elapsed_ms);
    session.updated = Instant::now();
    session.samples.send_replace(Some(Arc::new(Published {
        sample,
        received: Instant::now(),
    })));
    Ok(Json(receiver))
}

async fn stop(State(state): State<AppState>, headers: HeaderMap) -> Result<StatusCode, ApiError> {
    let (account, login_id) = auth::bearer_account_session(&state, &headers).await?;
    let id = session_header(&headers)?;
    let mut sessions = state
        .live_position
        .sessions
        .lock()
        .expect("live session lock");
    if sessions
        .get(&account.id)
        .is_some_and(|session| session.id == id && session.login_id == login_id)
        && let Some(session) = sessions.remove(&account.id)
    {
        session.samples.send_replace(None);
    }
    Ok(StatusCode::NO_CONTENT)
}

async fn accept_receivers(listener: TcpListener, state: AppState, receiver: LivePositionReceiver) {
    let mut clients = JoinSet::new();
    loop {
        tokio::select! {
            accepted = listener.accept() => {
                let Ok((socket, peer)) = accepted else { break; };
                if peer.ip() != receiver.peer { continue; }
                let subscription = {
                    let sessions = state.live_position.sessions.lock().expect("live session lock");
                    sessions.get(&receiver.account_id).and_then(|session| {
                        let sample = session.samples.borrow();
                        sample.as_ref().filter(|sample| sample.received.elapsed() < SAMPLE_TTL)?;
                        Some((session.samples.subscribe(), session.slots.clone().try_acquire_owned().ok()?, session.login_id.clone()))
                    })
                };
                if let Some((samples, permit, login_id)) = subscription {
                    let state = state.clone();
                    let account = receiver.account_id;
                    clients.spawn(async move {
                        let _permit = permit;
                        stream(socket, state, account, login_id, samples).await;
                    });
                }
            }
            _ = clients.join_next(), if !clients.is_empty() => {}
        }
    }
}

async fn stream(
    mut socket: TcpStream,
    state: AppState,
    account: i64,
    login_id: String,
    mut samples: watch::Receiver<Option<Arc<Published>>>,
) {
    let mut interval = tokio::time::interval(AUTH_INTERVAL);
    let mut last_sequence = 0;
    loop {
        if !eligible(&state, account, &login_id).await.unwrap_or(false) {
            state.live_position.stop_login(account, &login_id);
            break;
        }
        let current = samples.borrow_and_update().clone();
        let Some(current) = current.filter(|sample| sample.received.elapsed() < SAMPLE_TTL) else {
            break;
        };
        if current.sample.sequence != last_sequence {
            let nmea = current.sample.nmea();
            if !matches!(
                tokio::time::timeout(WRITE_TIMEOUT, socket.write_all(nmea.as_bytes())).await,
                Ok(Ok(()))
            ) {
                break;
            }
            last_sequence = current.sample.sequence;
        }
        tokio::select! {
            changed = samples.changed() => { if changed.is_err() { break; } }
            _ = interval.tick() => {}
        }
    }
    let _ = socket.shutdown().await;
}

#[cfg(test)]
mod tests;
