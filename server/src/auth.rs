//! Account credentials and scoped, revocable sessions for cell sharing.

use crate::CellStore;
use crate::api::{ApiError, AppState, client_ip, run_db};
use argon2::Argon2;
use argon2::password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString};
use axum::Json;
use axum::Router;
use axum::extract::{ConnectInfo, State};
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{Html, IntoResponse, Response};
use axum::routing::{get, post};
use base64::Engine as _;
use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use lettre::transport::smtp::authentication::Credentials;
use lettre::{Message, SmtpTransport, Transport};
use rand::RngCore;
use rusqlite::{OptionalExtension, params};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, VecDeque};
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const ACCESS_LIFETIME: i64 = 15 * 60;
const REFRESH_LIFETIME: i64 = 30 * 24 * 60 * 60;
const RESET_LIFETIME: i64 = 30 * 60;

/// Optional mail delivery for self-service password recovery.
#[derive(Clone)]
pub struct MailConfig {
    pub public_url: String,
    pub smtp_host: String,
    pub smtp_username: String,
    pub smtp_password: String,
    pub smtp_from: String,
}

#[derive(Clone, Debug, Serialize)]
pub struct Account {
    pub id: i64,
    pub email: String,
    pub admin: bool,
}

#[derive(Serialize)]
struct SessionResponse {
    access_token: String,
    refresh_token: String,
    expires_in: i64,
    account: Account,
}

#[derive(Deserialize)]
struct CredentialsBody {
    email: String,
    password: String,
}

#[derive(Deserialize)]
struct TokenBody {
    refresh_token: String,
}

#[derive(Deserialize)]
struct EmailBody {
    email: String,
}

#[derive(Deserialize)]
struct ResetBody {
    token: String,
    password: String,
}

#[derive(Serialize)]
struct OkResponse {
    status: &'static str,
}

/// Shared bounded counters for account endpoints; both IP and email are throttled.
#[derive(Default)]
pub(crate) struct AuthRateLimits {
    hits: HashMap<String, VecDeque<Instant>>,
}

impl AuthRateLimits {
    fn check(&mut self, operation: &str, ip: &str, email: &str) -> Result<(), ApiError> {
        let now = Instant::now();
        let (ip_limit, email_limit) = if operation == "login" {
            (100, 20)
        } else {
            (20, 5)
        };
        for (key, maximum) in [
            (format!("{operation}:ip:{ip}"), ip_limit),
            (format!("{operation}:email:{email}"), email_limit),
        ] {
            let queue = self.hits.entry(key).or_default();
            while queue
                .front()
                .is_some_and(|at| now.duration_since(*at) > Duration::from_secs(3600))
            {
                queue.pop_front();
            }
            if queue.len() >= maximum {
                return Err(ApiError(StatusCode::TOO_MANY_REQUESTS, "RATE_LIMITED"));
            }
            queue.push_back(now);
        }
        if self.hits.len() > 10_000 {
            self.hits.retain(|_, queue| {
                queue
                    .back()
                    .is_some_and(|at| now.duration_since(*at) <= Duration::from_secs(3600))
            });
        }
        Ok(())
    }
}

pub(crate) type SharedAuthLimits = Arc<Mutex<AuthRateLimits>>;

pub(crate) fn router() -> Router<AppState> {
    Router::new()
        .route("/v1/auth/register", post(register))
        .route("/v1/auth/login", post(login))
        .route("/v1/auth/refresh", post(refresh))
        .route("/v1/auth/logout", post(logout))
        .route("/v1/auth/me", get(me))
        .route("/v1/auth/password-reset/request", post(request_reset))
        .route("/v1/auth/password-reset/confirm", post(confirm_reset))
        .route("/reset-password", get(reset_page))
}

async fn reset_page() -> Response {
    let page = r#"<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Reset IMU Nav password</title><body><h1>Reset password</h1><form id="reset"><label>New password <input id="password" type="password" minlength="12" required autocomplete="new-password"></label><button>Reset password</button></form><p id="result" role="status"></p><script>const token=new URLSearchParams(location.hash.slice(1)).get('token');history.replaceState(null,'','/reset-password');document.getElementById('reset').addEventListener('submit',async event=>{event.preventDefault();const response=await fetch('/v1/auth/password-reset/confirm',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token,password:document.getElementById('password').value})});document.getElementById('result').textContent=response.ok?'Password changed. Return to the app and sign in.':'Link expired or invalid. Request another reset in the app.';});</script></body></html>"#;
    let mut response = Html(page).into_response();
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, "no-store".parse().expect("header"));
    response
        .headers_mut()
        .insert("referrer-policy", "no-referrer".parse().expect("header"));
    response.headers_mut().insert(header::CONTENT_SECURITY_POLICY, "default-src 'none'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; base-uri 'none'".parse().expect("header"));
    response
}

fn now_s() -> i64 {
    i64::try_from(
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
    )
    .unwrap_or(i64::MAX)
}

fn normalize_email(email: &str) -> Result<String, ApiError> {
    let email = email.trim().to_ascii_lowercase();
    if email.len() > 254
        || email.len() < 3
        || email.contains(char::is_whitespace)
        || email
            .split_once('@')
            .is_none_or(|(local, domain)| local.is_empty() || !domain.contains('.'))
    {
        return Err(ApiError(StatusCode::BAD_REQUEST, "INVALID_EMAIL"));
    }
    Ok(email)
}

fn validate_password(password: &str) -> Result<(), ApiError> {
    if !(12..=256).contains(&password.chars().count()) || password.len() > 1_024 {
        return Err(ApiError(StatusCode::BAD_REQUEST, "INVALID_PASSWORD"));
    }
    Ok(())
}

fn token() -> String {
    let mut bytes = [0u8; 32];
    rand::rng().fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

fn digest(value: &str) -> String {
    format!("{:x}", Sha256::digest(value.as_bytes()))
}

fn hash_password(password: &str) -> anyhow::Result<String> {
    let mut salt_bytes = [0u8; 16];
    rand::rng().fill_bytes(&mut salt_bytes);
    let salt =
        SaltString::encode_b64(&salt_bytes).map_err(|error| anyhow::anyhow!(error.to_string()))?;
    Argon2::default()
        .hash_password(password.as_bytes(), &salt)
        .map(|hash| hash.to_string())
        .map_err(|error| anyhow::anyhow!(error.to_string()))
}

fn verify_password(password: &str, stored: &str) -> bool {
    PasswordHash::new(stored).is_ok_and(|hash| {
        Argon2::default()
            .verify_password(password.as_bytes(), &hash)
            .is_ok()
    })
}

fn rate_limit(
    state: &AppState,
    operation: &str,
    peer: SocketAddr,
    headers: &HeaderMap,
    email: &str,
) -> Result<(), ApiError> {
    state
        .auth_limits
        .lock()
        .map_err(|_| ApiError(StatusCode::INTERNAL_SERVER_ERROR, "SERVER_ERROR"))?
        .check(
            operation,
            &client_ip(peer, headers, state.config.trust_proxy),
            email,
        )
}

fn issue_session(
    store: &CellStore,
    account: Account,
    session_id: Option<String>,
) -> anyhow::Result<SessionResponse> {
    let access_token = token();
    let refresh_token = token();
    let session_id = session_id.unwrap_or_else(token);
    let now = now_s();
    let connection = store.connection()?;
    connection.execute("DELETE FROM auth_tokens WHERE expires_s<=?1", [now])?;
    connection.execute(
        "INSERT INTO auth_tokens VALUES (?1,?2,?3,'access',?4)",
        params![
            digest(&access_token),
            account.id,
            session_id,
            now + ACCESS_LIFETIME
        ],
    )?;
    connection.execute(
        "INSERT INTO auth_tokens VALUES (?1,?2,?3,'refresh',?4)",
        params![
            digest(&refresh_token),
            account.id,
            session_id,
            now + REFRESH_LIFETIME
        ],
    )?;
    Ok(SessionResponse {
        access_token,
        refresh_token,
        expires_in: ACCESS_LIFETIME,
        account,
    })
}

fn account_for_token(
    store: &CellStore,
    raw: &str,
    kind: &str,
) -> anyhow::Result<Option<(Account, String)>> {
    let connection = store.connection()?;
    connection.query_row(
        "SELECT users.id,users.email,users.admin,auth_tokens.session_id FROM auth_tokens JOIN users ON users.id=auth_tokens.user_id WHERE token_hash=?1 AND kind=?2 AND expires_s>?3",
        params![digest(raw), kind, now_s()],
        |row| Ok((Account { id: row.get(0)?, email: row.get(1)?, admin: row.get(2)? }, row.get(3)?)),
    ).optional().map_err(Into::into)
}

pub(crate) async fn bearer_account(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<Account, ApiError> {
    let raw = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .ok_or(ApiError(StatusCode::UNAUTHORIZED, "UNAUTHORIZED"))?
        .to_owned();
    let store = state.store.clone();
    run_db(move || account_for_token(&store, &raw, "access"))
        .await?
        .map(|(account, _)| account)
        .ok_or(ApiError(StatusCode::UNAUTHORIZED, "UNAUTHORIZED"))
}

pub(crate) async fn admin_account(
    state: &AppState,
    headers: &HeaderMap,
    peer: SocketAddr,
) -> Result<Account, ApiError> {
    let basic = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Basic "))
        .and_then(|encoded| STANDARD.decode(encoded).ok())
        .and_then(|bytes| String::from_utf8(bytes).ok())
        .and_then(|value| {
            value
                .split_once(':')
                .map(|(a, b)| (a.to_owned(), b.to_owned()))
        });
    if let Some((email, password)) = basic {
        let store = state.store.clone();
        let email_for_lookup = email.clone();
        let account = run_db(move || login_account(&store, &email_for_lookup, &password)).await?;
        if let Some(account) = account.filter(|account| account.admin) {
            return Ok(account);
        }
        state
            .auth_limits
            .lock()
            .map_err(|_| ApiError(StatusCode::INTERNAL_SERVER_ERROR, "SERVER_ERROR"))?
            .check(
                "admin",
                &client_ip(peer, headers, state.config.trust_proxy),
                &email,
            )?;
        return Err(ApiError(StatusCode::UNAUTHORIZED, "UNAUTHORIZED"));
    }
    let account = bearer_account(state, headers).await?;
    account
        .admin
        .then_some(account)
        .ok_or(ApiError(StatusCode::FORBIDDEN, "FORBIDDEN"))
}

fn login_account(
    store: &CellStore,
    email: &str,
    password: &str,
) -> anyhow::Result<Option<Account>> {
    let connection = store.connection()?;
    let result: Option<(Account, String)> = connection
        .query_row(
            "SELECT id,email,admin,password_hash FROM users WHERE email=?1",
            [email.trim().to_ascii_lowercase()],
            |row| {
                Ok((
                    Account {
                        id: row.get(0)?,
                        email: row.get(1)?,
                        admin: row.get(2)?,
                    },
                    row.get(3)?,
                ))
            },
        )
        .optional()?;
    Ok(result.and_then(|(account, hash)| verify_password(password, &hash).then_some(account)))
}

async fn register(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(body): Json<CredentialsBody>,
) -> Result<Json<SessionResponse>, ApiError> {
    let email = normalize_email(&body.email)?;
    validate_password(&body.password)?;
    rate_limit(&state, "register", peer, &headers, &email)?;
    let store = state.store.clone();
    let result = run_db(move || {
        let hash = hash_password(&body.password)?;
        let connection = store.connection()?;
        let count = connection.execute(
            "INSERT OR IGNORE INTO users(email,password_hash,admin) VALUES (?1,?2,0)",
            params![email, hash],
        )?;
        if count == 0 {
            return Ok(None);
        }
        let account = Account {
            id: connection.last_insert_rowid(),
            email,
            admin: false,
        };
        issue_session(&store, account, None).map(Some)
    })
    .await?;
    result
        .map(Json)
        .ok_or(ApiError(StatusCode::CONFLICT, "EMAIL_EXISTS"))
}

async fn login(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(body): Json<CredentialsBody>,
) -> Result<Json<SessionResponse>, ApiError> {
    let email = normalize_email(&body.email)?;
    rate_limit(&state, "login", peer, &headers, &email)?;
    let store = state.store.clone();
    run_db(move || {
        let account = login_account(&store, &email, &body.password)?;
        account
            .map(|account| issue_session(&store, account, None))
            .transpose()
    })
    .await?
    .map(Json)
    .ok_or(ApiError(StatusCode::UNAUTHORIZED, "INVALID_CREDENTIALS"))
}

async fn refresh(
    State(state): State<AppState>,
    Json(body): Json<TokenBody>,
) -> Result<Json<SessionResponse>, ApiError> {
    let store = state.store.clone();
    run_db(move || {
        let Some((account, session_id)) =
            account_for_token(&store, &body.refresh_token, "refresh")?
        else {
            return Ok(None);
        };
        let mut connection = store.connection()?;
        let transaction = connection.transaction()?;
        let deleted = transaction.execute(
            "DELETE FROM auth_tokens WHERE token_hash=?1 AND kind='refresh'",
            [digest(&body.refresh_token)],
        )?;
        if deleted == 0 {
            return Ok(None);
        }
        let access_token = token();
        let refresh_token = token();
        let now = now_s();
        transaction.execute("DELETE FROM auth_tokens WHERE expires_s<=?1", [now])?;
        transaction.execute(
            "INSERT INTO auth_tokens VALUES (?1,?2,?3,'access',?4)",
            params![
                digest(&access_token),
                account.id,
                session_id,
                now + ACCESS_LIFETIME
            ],
        )?;
        transaction.execute(
            "INSERT INTO auth_tokens VALUES (?1,?2,?3,'refresh',?4)",
            params![
                digest(&refresh_token),
                account.id,
                session_id,
                now + REFRESH_LIFETIME
            ],
        )?;
        transaction.commit()?;
        Ok(Some(SessionResponse {
            access_token,
            refresh_token,
            expires_in: ACCESS_LIFETIME,
            account,
        }))
    })
    .await?
    .map(Json)
    .ok_or(ApiError(StatusCode::UNAUTHORIZED, "UNAUTHORIZED"))
}

async fn logout(
    State(state): State<AppState>,
    Json(body): Json<TokenBody>,
) -> Result<Json<OkResponse>, ApiError> {
    let store = state.store.clone();
    run_db(move || {
        if let Some((_, session_id)) = account_for_token(&store, &body.refresh_token, "refresh")? {
            store
                .connection()?
                .execute("DELETE FROM auth_tokens WHERE session_id=?1", [session_id])?;
        }
        Ok(())
    })
    .await?;
    Ok(Json(OkResponse { status: "ok" }))
}

async fn me(State(state): State<AppState>, headers: HeaderMap) -> Result<Json<Account>, ApiError> {
    bearer_account(&state, &headers).await.map(Json)
}

async fn request_reset(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(body): Json<EmailBody>,
) -> Result<Json<OkResponse>, ApiError> {
    let email = normalize_email(&body.email)?;
    rate_limit(&state, "reset", peer, &headers, &email)?;
    let Some(mail) = state.config.mail.clone() else {
        return Err(ApiError(
            StatusCode::SERVICE_UNAVAILABLE,
            "MAIL_UNAVAILABLE",
        ));
    };
    let store = state.store.clone();
    let reset = run_db(move || {
        let connection = store.connection()?;
        connection.execute("DELETE FROM password_resets WHERE expires_s<=?1", [now_s()])?;
        let user_id: Option<i64> = connection
            .query_row("SELECT id FROM users WHERE email=?1", [&email], |row| {
                row.get(0)
            })
            .optional()?;
        let Some(user_id) = user_id else {
            return Ok(None);
        };
        let raw = token();
        connection.execute(
            "INSERT INTO password_resets VALUES (?1,?2,?3)",
            params![digest(&raw), user_id, now_s() + RESET_LIFETIME],
        )?;
        Ok(Some((email, raw)))
    })
    .await?;
    if let Some((recipient, raw)) = reset {
        tokio::task::spawn_blocking(move || send_reset_mail(&mail, &recipient, &raw)).await??;
    }
    Ok(Json(OkResponse { status: "ok" }))
}

fn send_reset_mail(mail: &MailConfig, recipient: &str, raw: &str) -> anyhow::Result<()> {
    let url = format!(
        "{}/reset-password#token={raw}",
        mail.public_url.trim_end_matches('/')
    );
    let message = Message::builder()
        .from(mail.smtp_from.parse()?)
        .to(recipient.parse()?)
        .subject("IMU Nav password reset")
        .body(format!(
            "Use this link within 30 minutes to reset your IMU Nav password:\n{url}\n"
        ))?;
    SmtpTransport::relay(&mail.smtp_host)?
        .credentials(Credentials::new(
            mail.smtp_username.clone(),
            mail.smtp_password.clone(),
        ))
        .build()
        .send(&message)?;
    Ok(())
}

async fn confirm_reset(
    State(state): State<AppState>,
    Json(body): Json<ResetBody>,
) -> Result<Json<OkResponse>, ApiError> {
    validate_password(&body.password)?;
    let store = state.store.clone();
    let valid = run_db(move || {
        let mut connection = store.connection()?;
        let transaction = connection.transaction()?;
        let user_id: Option<i64> = transaction
            .query_row(
                "SELECT user_id FROM password_resets WHERE token_hash=?1 AND expires_s>?2",
                params![digest(&body.token), now_s()],
                |row| row.get(0),
            )
            .optional()?;
        let Some(user_id) = user_id else {
            return Ok(false);
        };
        let hash = hash_password(&body.password)?;
        transaction.execute(
            "UPDATE users SET password_hash=?1 WHERE id=?2",
            params![hash, user_id],
        )?;
        transaction.execute("DELETE FROM password_resets WHERE user_id=?1", [user_id])?;
        transaction.execute("DELETE FROM auth_tokens WHERE user_id=?1", [user_id])?;
        transaction.commit()?;
        Ok(true)
    })
    .await?;
    valid
        .then_some(Json(OkResponse { status: "ok" }))
        .ok_or(ApiError(StatusCode::BAD_REQUEST, "INVALID_RESET_TOKEN"))
}

/// Create an administrator locally, without exposing an admin-signup HTTP endpoint.
///
/// # Errors
/// Returns an error for invalid credentials, a duplicate email, or a database failure.
pub fn create_admin(store: &CellStore, email: &str, password: &str) -> anyhow::Result<()> {
    let email = normalize_email(email).map_err(|_| anyhow::anyhow!("invalid admin email"))?;
    validate_password(password)
        .map_err(|_| anyhow::anyhow!("password must be 12–256 characters"))?;
    let hash = hash_password(password)?;
    store.connection()?.execute(
        "INSERT INTO users(email,password_hash,admin) VALUES (?1,?2,1)",
        params![email, hash],
    )?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Policy;

    #[test]
    fn account_attempts_are_bounded_by_email_and_ip() {
        let mut limits = AuthRateLimits::default();
        for _ in 0..5 {
            assert!(
                limits
                    .check("register", "192.0.2.1", "one@example.org")
                    .is_ok()
            );
        }
        assert_eq!(
            limits
                .check("register", "192.0.2.2", "one@example.org")
                .err()
                .unwrap()
                .1,
            "RATE_LIMITED"
        );
        for index in 0..15 {
            assert!(
                limits
                    .check("register", "192.0.2.1", &format!("new-{index}@example.org"))
                    .is_ok()
            );
        }
        assert_eq!(
            limits
                .check("register", "192.0.2.1", "another@example.org")
                .err()
                .unwrap()
                .1,
            "RATE_LIMITED"
        );
    }

    #[tokio::test]
    async fn reset_is_one_use_and_revokes_every_session() {
        let file = tempfile::NamedTempFile::new().unwrap();
        let store = CellStore::open(file.path()).unwrap();
        create_admin(&store, "admin@example.org", "correct horse battery staple").unwrap();
        let old = issue_session(
            &store,
            login_account(&store, "admin@example.org", "correct horse battery staple")
                .unwrap()
                .unwrap(),
            None,
        )
        .unwrap();
        let reset_token = token();
        store
            .connection()
            .unwrap()
            .execute(
                "INSERT INTO password_resets VALUES (?1,1,?2)",
                params![digest(&reset_token), now_s() + 60],
            )
            .unwrap();
        let state = AppState::new(
            store.clone(),
            crate::ServerConfig {
                mail: None,
                policy: Policy::default(),
                trust_proxy: false,
            },
        )
        .unwrap();
        let _ = confirm_reset(
            State(state.clone()),
            Json(ResetBody {
                token: reset_token.clone(),
                password: "new correct horse battery".to_owned(),
            }),
        )
        .await
        .unwrap();
        assert!(
            account_for_token(&store, &old.access_token, "access")
                .unwrap()
                .is_none()
        );
        assert!(
            account_for_token(&store, &old.refresh_token, "refresh")
                .unwrap()
                .is_none()
        );
        assert!(
            login_account(&store, "admin@example.org", "correct horse battery staple")
                .unwrap()
                .is_none()
        );
        assert!(
            login_account(&store, "admin@example.org", "new correct horse battery")
                .unwrap()
                .is_some()
        );
        assert_eq!(
            confirm_reset(
                State(state),
                Json(ResetBody {
                    token: reset_token,
                    password: "another correct password".to_owned()
                })
            )
            .await
            .err()
            .unwrap()
            .1,
            "INVALID_RESET_TOKEN"
        );
    }
}
