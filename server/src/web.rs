//! Browser account pages backed by a separate cookie session and account-scoped store queries.

use crate::api::{ApiError, AppState, run_db};
use crate::auth::{self, Account};
use crate::store::{OwnContributionChange, OwnContributionPage, OwnFilter};
use crate::{CellKey, Radio, ServerEvent};
use askama::Template;
use axum::Router;
use axum::body::Body;
use axum::extract::Request;
use axum::extract::{ConnectInfo, Form, Query, State};
use axum::http::{HeaderMap, Method, StatusCode, Uri, header};
use axum::middleware::Next;
use axum::response::{Html, IntoResponse, Response};
use axum::routing::{get, post};
use futures_util::StreamExt;
use rusqlite::params;
use serde::Deserialize;
use std::net::SocketAddr;
use std::str::FromStr;
use time::{
    OffsetDateTime, PrimitiveDateTime, format_description::well_known::Rfc3339,
    macros::format_description,
};
use tokio_util::io::ReaderStream;

pub(crate) const WEB_LIFETIME_S: i64 = 7 * 24 * 60 * 60;
pub(crate) const IMPERSONATION_LIFETIME_S: i64 = 60 * 60;
const PAGE_SIZE: usize = 100;
const CONTENT_SECURITY_POLICY: &str = "default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";

/// Account pages use normal HTML forms; Android keeps the JSON bearer-token API.
pub(crate) fn router() -> Router<AppState> {
    Router::new()
        .route("/", get(home))
        .route("/login", get(login_page).post(login))
        .route("/signup", get(signup_page).post(signup))
        .route("/data-usage", get(data_usage_page))
        .route("/web-locale.js", get(locale_script))
        .route("/forgot-password", get(forgot_page).post(forgot_submit))
        .route("/account", get(account_page))
        .route("/account/export", get(export_own))
        .route("/account/password", post(change_password))
        .route("/account/email", post(change_email))
        .route("/account/email/resend", post(resend_verification))
        .route("/account/sharing/pause", post(pause_sharing))
        .route("/account/sharing/resume", post(resume_sharing))
        .route("/account/sessions/revoke", post(revoke_session))
        .route(
            "/account/sessions/revoke-others",
            post(revoke_other_sessions),
        )
        .route("/account/close", post(close_account))
        .route("/account/logout", post(logout))
        .route("/account/stop-impersonation", post(stop_impersonation))
        .route("/account/contributions/delete", post(delete_one))
        .route("/account/contributions/delete-all", post(delete_all))
}

#[derive(Template)]
#[template(path = "account_form.html")]
struct AccountFormTemplate {
    signup: bool,
    title: &'static str,
    action: &'static str,
    email: String,
    error: &'static str,
}

#[derive(Template)]
#[template(path = "data_usage.html")]
struct DataUsageTemplate;

#[derive(Template)]
#[template(path = "account.html")]
struct AccountTemplate {
    email: String,
    csrf: String,
    rows: Vec<AccountRow>,
    total: usize,
    page: usize,
    has_previous: bool,
    has_next: bool,
    previous_page: usize,
    next_page: usize,
    filter_device: String,
    filter_mcc: String,
    filter_from: String,
    filter_to: String,
    sharing_enabled: bool,
    email_verified: bool,
    message: String,
    sessions: Vec<SessionRow>,
    impersonating: bool,
    actor_email: String,
}

struct Impersonation {
    actor_id: i64,
    actor_email: String,
    target_id: i64,
    admin_token_hash: String,
}

struct SessionRow {
    id: String,
    label: String,
    expires: WebTime,
    current: bool,
}

/// Display a database timestamp as UTC while retaining a machine-readable HTML value.
pub(crate) struct WebTime {
    pub display: String,
    pub datetime: String,
    pub valid: bool,
}

pub(crate) fn utc_time(epoch_s: i64) -> WebTime {
    OffsetDateTime::from_unix_timestamp(epoch_s)
        .ok()
        .and_then(|value| {
            Some(WebTime {
                display: value
                    .format(format_description!(
                        "[day padding:none] [month repr:short] [year], [hour]:[minute]:[second] UTC"
                    ))
                    .ok()?,
                datetime: value.format(&Rfc3339).ok()?,
                valid: true,
            })
        })
        .unwrap_or_else(|| WebTime {
            display: format!("Invalid time (Unix {epoch_s})"),
            datetime: String::new(),
            valid: false,
        })
}

/// Date-time inputs have no zone, so the page and parser both treat them as UTC.
fn utc_input(epoch_s: i64) -> String {
    OffsetDateTime::from_unix_timestamp(epoch_s)
        .ok()
        .and_then(|value| {
            value
                .format(format_description!(
                    "[year]-[month]-[day]T[hour]:[minute]:[second]"
                ))
                .ok()
        })
        .unwrap_or_default()
}

fn parse_utc_filter(raw: &str) -> Result<i64, &'static str> {
    if let Ok(epoch_s) = raw.parse::<i64>() {
        return OffsetDateTime::from_unix_timestamp(epoch_s)
            .map(|_| epoch_s)
            .map_err(|_| "Timestamp is outside the supported range");
    }
    let second_format = format_description!("[year]-[month]-[day]T[hour]:[minute]:[second]");
    let minute_format = format_description!("[year]-[month]-[day]T[hour]:[minute]");
    let datetime = PrimitiveDateTime::parse(raw, second_format)
        .or_else(|_| PrimitiveDateTime::parse(raw, minute_format))
        .map_err(|_| "Use a valid UTC date and time")?;
    Ok(datetime.assume_utc().unix_timestamp())
}

fn optional_utc_filter<'de, D>(deserializer: D) -> Result<Option<i64>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let raw = String::deserialize(deserializer)?;
    if raw.is_empty() {
        Ok(None)
    } else {
        parse_utc_filter(&raw)
            .map(Some)
            .map_err(serde::de::Error::custom)
    }
}

#[cfg(test)]
mod time_tests {
    use super::{parse_utc_filter, utc_input, utc_time};

    #[test]
    fn utc_display_and_input_keep_the_exact_second() {
        let time = utc_time(1_706_753_445);
        assert!(time.valid);
        assert_eq!(time.display, "1 Feb 2024, 02:10:45 UTC");
        assert_eq!(time.datetime, "2024-02-01T02:10:45Z");
        assert_eq!(utc_input(1_706_753_445), "2024-02-01T02:10:45");
        assert_eq!(
            parse_utc_filter(&utc_input(1_706_753_445)),
            Ok(1_706_753_445)
        );
        assert!(!utc_time(i64::MAX).valid);
    }

    #[test]
    fn utc_filter_accepts_browser_values_and_legacy_unix_seconds() {
        assert_eq!(parse_utc_filter("2024-02-01T02:10:45"), Ok(1_706_753_445));
        assert_eq!(parse_utc_filter("2024-02-01T02:10"), Ok(1_706_753_400));
        assert_eq!(parse_utc_filter("1706753445"), Ok(1_706_753_445));
        assert!(parse_utc_filter("2024-02-30T02:10").is_err());
        assert!(parse_utc_filter("2024-02-01T25:10").is_err());
        assert!(parse_utc_filter("999999999999999999").is_err());
    }
}

/// Preformatted fields keep display logic and URL construction out of the template.
struct AccountRow {
    radio: String,
    mcc: i64,
    mnc: i64,
    area: i64,
    cid: i64,
    device: String,
    device_label: String,
    lat: String,
    lon: String,
    range_m: String,
    samples: i64,
    updated_time: WebTime,
}

impl From<OwnContributionPage> for Vec<AccountRow> {
    fn from(page: OwnContributionPage) -> Self {
        page.rows
            .into_iter()
            .map(|row| AccountRow {
                radio: row.key.radio.to_string(),
                mcc: row.key.mcc,
                mnc: row.key.mnc,
                area: row.key.area,
                cid: row.key.cid,
                device_label: row
                    .device
                    .splitn(3, ':')
                    .nth(2)
                    .unwrap_or(&row.device)
                    .to_owned(),
                device: row.device,
                lat: format!("{:.7}", row.lat),
                lon: format!("{:.7}", row.lon),
                range_m: format!("{:.0}", row.range_m),
                samples: row.samples,
                updated_time: utc_time(row.updated_s),
            })
            .collect()
    }
}

#[derive(Deserialize)]
struct CredentialsForm {
    email: String,
    password: String,
}

#[derive(Deserialize)]
struct PasswordForm {
    csrf: String,
    current_password: String,
    new_password: String,
}

#[derive(Deserialize)]
struct EmailChangeForm {
    csrf: String,
    password: String,
    email: String,
}

#[derive(Deserialize)]
struct PasswordConfirmForm {
    csrf: String,
    password: String,
}

#[derive(Deserialize)]
struct CloseForm {
    csrf: String,
    password: String,
    confirm: String,
}

#[derive(Deserialize)]
struct SessionForm {
    csrf: String,
    session_id: String,
}

#[derive(Deserialize)]
struct EmailForm {
    email: String,
}

#[derive(Template)]
#[template(path = "forgot.html")]
struct ForgotTemplate {
    error: &'static str,
    success: bool,
}

#[derive(Deserialize)]
struct AccountQuery {
    #[serde(default, deserialize_with = "optional_number")]
    page: Option<usize>,
    device: Option<String>,
    #[serde(default, deserialize_with = "optional_number")]
    mcc: Option<i64>,
    #[serde(default, deserialize_with = "optional_utc_filter")]
    from_s: Option<i64>,
    #[serde(default, deserialize_with = "optional_utc_filter")]
    to_s: Option<i64>,
    message: Option<String>,
}

fn optional_number<'de, D, T>(deserializer: D) -> Result<Option<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: FromStr,
    T::Err: std::fmt::Display,
{
    let raw = String::deserialize(deserializer)?;
    if raw.is_empty() {
        Ok(None)
    } else {
        raw.parse().map(Some).map_err(serde::de::Error::custom)
    }
}

impl AccountQuery {
    fn filter(&self) -> Option<OwnFilter> {
        let device = self.device.clone().unwrap_or_default();
        if device.len() > 100
            || self.mcc.is_some_and(|mcc| !(1..=999).contains(&mcc))
            || self.from_s.is_some_and(|value| value < 0)
            || self.to_s.is_some_and(|value| value < 0)
            || self
                .from_s
                .zip(self.to_s)
                .is_some_and(|(from, to)| from > to)
        {
            return None;
        }
        Some(OwnFilter {
            device,
            mcc: self.mcc,
            from_s: self.from_s,
            to_s: self.to_s,
        })
    }
}

#[derive(Deserialize)]
struct CsrfForm {
    csrf: String,
}

#[derive(Deserialize)]
struct DeleteAllForm {
    csrf: String,
    confirm: String,
}

#[derive(Deserialize)]
struct DeleteForm {
    csrf: String,
    radio: String,
    mcc: i64,
    mnc: i64,
    area: i64,
    cid: i64,
    device: String,
}

async fn home() -> Response {
    redirect("/account")
}

async fn login_page() -> Result<Response, ApiError> {
    form_page(false, String::new(), "", StatusCode::OK)
}

async fn signup_page() -> Result<Response, ApiError> {
    form_page(true, String::new(), "", StatusCode::OK)
}

/// Explain the server's data flow without requiring an account or loading external assets.
async fn data_usage_page() -> Result<Response, ApiError> {
    render(StatusCode::OK, &DataUsageTemplate)
}

async fn forgot_page() -> Result<Response, ApiError> {
    render(
        StatusCode::OK,
        &ForgotTemplate {
            error: "",
            success: false,
        },
    )
}

async fn forgot_submit(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Form(form): Form<EmailForm>,
) -> Result<Response, ApiError> {
    match auth::request_password_reset(&state, peer, &headers, &form.email).await {
        Ok(()) => render(
            StatusCode::OK,
            &ForgotTemplate {
                error: "",
                success: true,
            },
        ),
        Err(ApiError(StatusCode::SERVICE_UNAVAILABLE, _)) => render(
            StatusCode::SERVICE_UNAVAILABLE,
            &ForgotTemplate {
                error: "Password recovery email is unavailable on this server.",
                success: false,
            },
        ),
        Err(ApiError(StatusCode::TOO_MANY_REQUESTS, _)) => render(
            StatusCode::TOO_MANY_REQUESTS,
            &ForgotTemplate {
                error: "Too many requests. Try again later.",
                success: false,
            },
        ),
        Err(ApiError(StatusCode::BAD_REQUEST, _)) => render(
            StatusCode::BAD_REQUEST,
            &ForgotTemplate {
                error: "Enter a valid email address.",
                success: false,
            },
        ),
        Err(ApiError(StatusCode::INTERNAL_SERVER_ERROR, _)) => render(
            StatusCode::SERVICE_UNAVAILABLE,
            &ForgotTemplate {
                error: "Email delivery failed. Try again later.",
                success: false,
            },
        ),
        Err(error) => Err(error),
    }
}

fn form_page(
    signup: bool,
    email: String,
    error: &'static str,
    status: StatusCode,
) -> Result<Response, ApiError> {
    let template = AccountFormTemplate {
        signup,
        title: if signup { "Create account" } else { "Sign in" },
        action: if signup { "/signup" } else { "/login" },
        email,
        error,
    };
    render(status, &template)
}

/// Reuse the same account validation and rate limits as the JSON API.
async fn signup(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    uri: Uri,
    headers: HeaderMap,
    Form(form): Form<CredentialsForm>,
) -> Result<Response, ApiError> {
    let Ok(email) = auth::normalize_email(&form.email) else {
        return form_page(
            true,
            form.email,
            "Enter a valid email address.",
            StatusCode::BAD_REQUEST,
        );
    };
    if auth::validate_password(&form.password).is_err() {
        return form_page(
            true,
            email,
            "Use a password between 12 and 256 characters.",
            StatusCode::BAD_REQUEST,
        );
    }
    if auth::rate_limit(&state, "register", peer, &headers, &email).is_err() {
        return form_page(
            true,
            email,
            "Too many attempts. Try again later.",
            StatusCode::TOO_MANY_REQUESTS,
        );
    }
    let store = state.store.clone();
    let password = form.password;
    let lookup_email = email.clone();
    let mail = state.config.mail.clone();
    let registered = run_db(move || {
        let Some(account) = auth::register_account_with_verification(
            &store,
            &lookup_email,
            &password,
            mail.is_none(),
        )?
        else {
            return Ok(None);
        };
        let verification = if mail.is_some() {
            auth::create_email_verification(&store, account.id, &account.email)?
        } else {
            None
        };
        Ok(Some((account, verification)))
    })
    .await?;
    let Some((account, verification)) = registered else {
        return form_page(
            true,
            email,
            "That email is already registered.",
            StatusCode::CONFLICT,
        );
    };
    if let (Some(raw), Some(mail)) = (verification, state.config.mail.clone()) {
        let recipient = account.email.clone();
        if let Err(error) = tokio::task::spawn_blocking(move || {
            auth::send_verification_mail(&mail, &recipient, &raw)
        })
        .await?
        {
            tracing::error!(%error, "verification email failed");
        }
    }
    start_session(&state, account, &uri, &headers).await
}

async fn login(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    uri: Uri,
    headers: HeaderMap,
    Form(form): Form<CredentialsForm>,
) -> Result<Response, ApiError> {
    let Ok(email) = auth::normalize_email(&form.email) else {
        return form_page(
            false,
            form.email,
            "Invalid email or password.",
            StatusCode::UNAUTHORIZED,
        );
    };
    if auth::rate_limit(&state, "login", peer, &headers, &email).is_err() {
        return form_page(
            false,
            email,
            "Too many attempts. Try again later.",
            StatusCode::TOO_MANY_REQUESTS,
        );
    }
    let store = state.store.clone();
    let lookup_email = email.clone();
    let account =
        run_db(move || auth::login_account(&store, &lookup_email, &form.password)).await?;
    let Some(account) = account else {
        return form_page(
            false,
            email,
            "Invalid email or password.",
            StatusCode::UNAUTHORIZED,
        );
    };
    start_session(&state, account, &uri, &headers).await
}

/// Browser sessions are revocable and do not expose an Android API bearer token to scripts.
async fn start_session(
    state: &AppState,
    account: Account,
    uri: &Uri,
    headers: &HeaderMap,
) -> Result<Response, ApiError> {
    let raw = auth::token();
    let hash = auth::digest(&raw);
    let store = state.store.clone();
    run_db(move || {
        store.connection()?.execute(
            "INSERT INTO auth_tokens(token_hash,user_id,session_id,kind,expires_s) VALUES (?1,?2,?3,'web',?4)",
            params![hash, account.id, auth::token(), auth::now_s() + WEB_LIFETIME_S],
        )?;
        Ok(())
    })
    .await?;
    let mut response = redirect(if account.admin { "/admin" } else { "/account" });
    set_session_cookie(
        &mut response,
        "imu_nav_session",
        &raw,
        WEB_LIFETIME_S,
        secure_cookie(state, uri, headers),
    );
    clear_cookie(&mut response, "imu_nav_admin_return");
    Ok(response)
}

pub(crate) fn secure_cookie(state: &AppState, uri: &Uri, headers: &HeaderMap) -> bool {
    uri.scheme_str() == Some("https")
        || state.config.trust_proxy
            && headers
                .get("x-forwarded-proto")
                .is_some_and(|value| value == "https")
}

pub(crate) fn set_session_cookie(
    response: &mut Response,
    name: &str,
    raw: &str,
    lifetime: i64,
    secure: bool,
) {
    response.headers_mut().append(
        header::SET_COOKIE,
        format!(
            "{name}={raw}; Path=/; HttpOnly; SameSite=Strict; Max-Age={lifetime}{}",
            if secure { "; Secure" } else { "" }
        )
        .parse()
        .expect("URL-safe token cookie"),
    );
}

fn clear_cookie(response: &mut Response, name: &str) {
    response.headers_mut().append(
        header::SET_COOKIE,
        format!("{name}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0")
            .parse()
            .expect("cookie"),
    );
}

pub(crate) fn cookie_token(headers: &HeaderMap, name: &str) -> Option<String> {
    headers
        .get(header::COOKIE)
        .and_then(|value| value.to_str().ok())
        .and_then(|cookies| {
            cookies
                .split(';')
                .map(str::trim)
                .find_map(|cookie| cookie.strip_prefix(&format!("{name}=")))
        })
        .filter(|value| {
            value.len() == 43
                && value
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
        })
        .map(str::to_owned)
}

/// Record successful account actions performed through a delegated browser session.
pub(crate) async fn audit_impersonated_requests(
    State(state): State<AppState>,
    request: Request,
    next: Next,
) -> Response {
    let action = match (request.method(), request.uri().path()) {
        (&Method::GET, "/account/export") => Some("impersonate_export"),
        (&Method::POST, "/account/logout") => Some("impersonate_logout"),
        (&Method::POST, "/account/password") => Some("impersonate_change_password"),
        (&Method::POST, "/account/email") => Some("impersonate_change_email"),
        (&Method::POST, "/account/email/resend") => Some("impersonate_resend_email"),
        (&Method::POST, "/account/sharing/pause") => Some("impersonate_pause_sharing"),
        (&Method::POST, "/account/sharing/resume") => Some("impersonate_resume_sharing"),
        (&Method::POST, "/account/sessions/revoke") => Some("impersonate_revoke_session"),
        (&Method::POST, "/account/sessions/revoke-others") => Some("impersonate_revoke_sessions"),
        (&Method::POST, "/account/close") => Some("impersonate_close_account"),
        (&Method::POST, "/account/contributions/delete") => Some("impersonate_delete_observation"),
        (&Method::POST, "/account/contributions/delete-all") => Some("impersonate_delete_all"),
        _ => None,
    };
    let actor = if let (Some(_), Some(raw)) =
        (action, cookie_token(request.headers(), "imu_nav_session"))
    {
        let store = state.store.clone();
        match run_db(move || {
            let target = auth::account_for_token(&store, &raw, "web_impersonated")?;
            let impersonation = impersonation_for_token(&store, &raw)?;
            Ok(target
                .zip(impersonation)
                .and_then(|((account, _), record)| {
                    (account.id == record.target_id).then_some((record.actor_id, record.target_id))
                }))
        })
        .await
        {
            Ok(actor) => actor,
            Err(error) => {
                tracing::error!(?error, "impersonation audit lookup failed");
                None
            }
        }
    } else {
        None
    };
    let response = next.run(request).await;
    if let (Some(action), Some((actor_id, target_id))) = (action, actor)
        && (response.status().is_success() || response.status().is_redirection())
    {
        let store = state.store.clone();
        if let Err(error) =
            run_db(move || store.audit(actor_id, action, &target_id.to_string())).await
        {
            tracing::error!(?error, "impersonation audit write failed");
        }
    }
    response
}

fn impersonation_for_token(
    store: &crate::CellStore,
    raw: &str,
) -> anyhow::Result<Option<Impersonation>> {
    use rusqlite::OptionalExtension;
    Ok(store.connection()?.query_row(
        "SELECT wi.actor_id,actor.email,wi.target_id,wi.admin_token_hash FROM web_impersonations wi
         JOIN auth_tokens admin_token ON admin_token.token_hash=wi.admin_token_hash AND admin_token.kind='web' AND admin_token.expires_s>?2
         JOIN users actor ON actor.id=wi.actor_id AND actor.admin=1 AND actor.suspended=0
         WHERE wi.token_hash=?1",
        params![auth::digest(raw), auth::now_s()],
        |row| Ok(Impersonation { actor_id: row.get(0)?, actor_email: row.get(1)?, target_id: row.get(2)?, admin_token_hash: row.get(3)? }),
    ).optional()?)
}

/// Resolve only a valid, unexpired browser token from an `HttpOnly` cookie.
pub(crate) async fn web_account(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<Option<(Account, String)>, ApiError> {
    let raw = cookie_token(headers, "imu_nav_session");
    let Some(raw) = raw else {
        return Ok(None);
    };
    let store = state.store.clone();
    let token = raw.clone();
    let account = run_db(move || {
        if let Some((account, _)) = auth::account_for_token(&store, &token, "web")? {
            return Ok(Some(account));
        }
        let Some((account, _)) = auth::account_for_token(&store, &token, "web_impersonated")?
        else {
            return Ok(None);
        };
        let valid =
            impersonation_for_token(&store, &token)?.is_some_and(|imp| imp.target_id == account.id);
        Ok(valid.then_some(account))
    })
    .await?;
    Ok(account.map(|account| (account, raw)))
}

async fn account_page(
    State(state): State<AppState>,
    uri: Uri,
    headers: HeaderMap,
    Query(query): Query<AccountQuery>,
) -> Result<Response, ApiError> {
    let Some((account, raw)) = web_account(&state, &headers).await? else {
        if let Some(admin_raw) = cookie_token(&headers, "imu_nav_admin_return") {
            let store = state.store.clone();
            let token = admin_raw.clone();
            let valid = run_db(move || {
                Ok(auth::account_for_token(&store, &token, "web")?
                    .is_some_and(|(actor, _)| actor.admin))
            })
            .await?;
            if valid {
                let mut response = redirect("/admin");
                set_session_cookie(
                    &mut response,
                    "imu_nav_session",
                    &admin_raw,
                    WEB_LIFETIME_S,
                    secure_cookie(&state, &uri, &headers),
                );
                clear_cookie(&mut response, "imu_nav_admin_return");
                return Ok(response);
            }
        }
        return Ok(redirect("/login"));
    };
    let store = state.store.clone();
    let token = raw.clone();
    let impersonation = run_db(move || impersonation_for_token(&store, &token)).await?;
    let Some(filter) = query.filter() else {
        return Ok(error_page(
            StatusCode::BAD_REQUEST,
            "Invalid observation filter.",
        ));
    };
    let page = query.page.unwrap_or(0);
    let Some(offset) = page
        .checked_mul(PAGE_SIZE)
        .filter(|offset| i64::try_from(*offset).is_ok())
    else {
        return Ok(error_page(StatusCode::BAD_REQUEST, "Invalid page number."));
    };
    let store = state.store.clone();
    let filter_for_query = filter.clone();
    let raw_for_sessions = raw.clone();
    let (contributions, (sharing_enabled, email_verified), sessions) = run_db(move || {
        let current = auth::account_for_token(&store, &raw_for_sessions, "web")?
            .map(|(_, session_id)| session_id).unwrap_or_default();
        let connection = store.connection()?;
        let mut statement = connection.prepare("SELECT session_id,MAX(expires_s),MAX(CASE WHEN kind IN ('web','web_impersonated') THEN 1 ELSE 0 END)
            FROM auth_tokens WHERE user_id=?1 AND expires_s>?2 GROUP BY session_id ORDER BY MAX(expires_s) DESC")?;
        let sessions = statement.query_map(rusqlite::params![account.id, auth::now_s()], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?, row.get::<_, bool>(2)?))
        })?.collect::<rusqlite::Result<Vec<_>>>()?.into_iter().map(|(id, expires, browser)| {
            let label = format!("{} · {}", if browser { "Browser" } else { "App" }, &id[id.len().saturating_sub(8)..]);
            SessionRow { current: id == current, id, label, expires: utc_time(expires) }
        }).collect::<Vec<_>>();
        Ok((store.own_contributions(account.id, &filter_for_query, PAGE_SIZE, offset)?,
            store.account_sharing_status(account.id)?, sessions))
    }).await?;
    let total = contributions.total;
    let has_next = offset + PAGE_SIZE < total;
    render(
        StatusCode::OK,
        &AccountTemplate {
            email: account.email,
            csrf: csrf_token(&raw),
            rows: contributions.into(),
            total,
            page,
            has_previous: page > 0,
            has_next,
            previous_page: page.saturating_sub(1),
            next_page: page + 1,
            filter_device: filter.device,
            filter_mcc: filter
                .mcc
                .map_or_else(String::new, |value| value.to_string()),
            filter_from: filter.from_s.map_or_else(String::new, utc_input),
            filter_to: filter.to_s.map_or_else(String::new, utc_input),
            sharing_enabled,
            email_verified,
            message: account_message(query.message.as_deref()).to_owned(),
            sessions,
            impersonating: impersonation.is_some(),
            actor_email: impersonation.map_or_else(String::new, |record| record.actor_email),
        },
    )
}

fn account_message(key: Option<&str>) -> &'static str {
    match key {
        Some("deleted") => {
            "Observation deleted. Previously deleted data will not be accepted again."
        }
        Some("all-deleted") => "Your observations were deleted and sharing is paused.",
        Some("sharing-resumed") => {
            "Sharing is active again; previously deleted towers stay blocked."
        }
        Some("password-changed") => "Password changed. Other sessions were signed out.",
        Some("sessions-revoked") => "Other sessions were signed out.",
        Some("email-sent") => "Check your email for the verification link.",
        _ => "",
    }
}

async fn export_own(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<AccountQuery>,
) -> Result<Response, ApiError> {
    let Some((account, _)) = web_account(&state, &headers).await? else {
        return Ok(redirect("/login"));
    };
    let Some(filter) = query.filter() else {
        return Ok(error_page(
            StatusCode::BAD_REQUEST,
            "Invalid observation filter.",
        ));
    };
    let temporary = tempfile::NamedTempFile::new()
        .map_err(|error| ApiError::from(anyhow::Error::new(error)))?
        .into_temp_path();
    let path = temporary.to_path_buf();
    let store = state.store.clone();
    run_db(move || store.export_own_to_path(account.id, &filter, &path)).await?;
    let file = tokio::fs::File::open(&temporary)
        .await
        .map_err(|error| ApiError::from(anyhow::Error::new(error)))?;
    let stream = ReaderStream::new(file).map(move |chunk| {
        let _keep = &temporary;
        chunk
    });
    let mut response = Body::from_stream(stream).into_response();
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        "application/gzip".parse().expect("header"),
    );
    response.headers_mut().insert(
        header::CONTENT_DISPOSITION,
        "attachment; filename=\"my-cell-observations.csv.gz\""
            .parse()
            .expect("header"),
    );
    Ok(no_store(response))
}

async fn change_password(
    State(state): State<AppState>,
    headers: HeaderMap,
    Form(form): Form<PasswordForm>,
) -> Result<Response, ApiError> {
    let Some((account, raw)) = web_account(&state, &headers).await? else {
        return Ok(redirect("/login"));
    };
    if form.csrf != csrf_token(&raw) {
        return Ok(error_page(StatusCode::FORBIDDEN, "Invalid form token."));
    }
    if auth::validate_password(&form.new_password).is_err() {
        return Ok(error_page(
            StatusCode::BAD_REQUEST,
            "New password must be 12–256 characters.",
        ));
    }
    let store = state.store.clone();
    let changed = run_db(move || {
        auth::change_account_password(
            &store,
            account.id,
            &form.current_password,
            &form.new_password,
            &raw,
        )
    })
    .await?;
    if !changed {
        return Ok(error_page(
            StatusCode::FORBIDDEN,
            "Current password is incorrect.",
        ));
    }
    Ok(redirect("/account?message=password-changed"))
}

async fn change_email(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Form(form): Form<EmailChangeForm>,
) -> Result<Response, ApiError> {
    let Some((account, raw)) = web_account(&state, &headers).await? else {
        return Ok(redirect("/login"));
    };
    if form.csrf != csrf_token(&raw) {
        return Ok(error_page(StatusCode::FORBIDDEN, "Invalid form token."));
    }
    let Some(mail) = state.config.mail.clone() else {
        return Ok(error_page(
            StatusCode::SERVICE_UNAVAILABLE,
            "Email changes require server email delivery.",
        ));
    };
    let email = match auth::normalize_email(&form.email) {
        Ok(email) => email,
        Err(_) => {
            return Ok(error_page(
                StatusCode::BAD_REQUEST,
                "Invalid email address.",
            ));
        }
    };
    auth::rate_limit(&state, "verify", peer, &headers, &email)?;
    let store = state.store.clone();
    let password = form.password;
    let target = email.clone();
    let token = run_db(move || {
        if !auth::verify_account_password(&store, account.id, &password)? {
            return Ok(None);
        }
        auth::create_email_verification(&store, account.id, &target)
    })
    .await?;
    let Some(token) = token else {
        return Ok(error_page(
            StatusCode::CONFLICT,
            "Password is incorrect or email is already in use.",
        ));
    };
    if let Err(error) =
        tokio::task::spawn_blocking(move || auth::send_verification_mail(&mail, &email, &token))
            .await?
    {
        tracing::error!(%error, "verification email failed");
        return Ok(error_page(
            StatusCode::SERVICE_UNAVAILABLE,
            "Email delivery failed. Try again later.",
        ));
    }
    Ok(redirect("/account?message=email-sent"))
}

async fn resend_verification(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Form(form): Form<CsrfForm>,
) -> Result<Response, ApiError> {
    let Some((account, raw)) = web_account(&state, &headers).await? else {
        return Ok(redirect("/login"));
    };
    if form.csrf != csrf_token(&raw) {
        return Ok(error_page(StatusCode::FORBIDDEN, "Invalid form token."));
    }
    if account.email_verified {
        return Ok(redirect("/account"));
    }
    let Some(mail) = state.config.mail.clone() else {
        return Ok(error_page(
            StatusCode::SERVICE_UNAVAILABLE,
            "Email delivery is unavailable.",
        ));
    };
    auth::rate_limit(&state, "verify", peer, &headers, &account.email)?;
    let store = state.store.clone();
    let recipient = account.email;
    let email = recipient.clone();
    let token = run_db(move || auth::create_email_verification(&store, account.id, &email))
        .await?
        .ok_or(ApiError(StatusCode::CONFLICT, "EMAIL_EXISTS"))?;
    if let Err(error) =
        tokio::task::spawn_blocking(move || auth::send_verification_mail(&mail, &recipient, &token))
            .await?
    {
        tracing::error!(%error, "verification email failed");
        return Ok(error_page(
            StatusCode::SERVICE_UNAVAILABLE,
            "Email delivery failed. Try again later.",
        ));
    }
    Ok(redirect("/account?message=email-sent"))
}

async fn pause_sharing(
    State(state): State<AppState>,
    headers: HeaderMap,
    Form(form): Form<CsrfForm>,
) -> Result<Response, ApiError> {
    let Some((account, raw)) = web_account(&state, &headers).await? else {
        return Ok(redirect("/login"));
    };
    if form.csrf != csrf_token(&raw) {
        return Ok(error_page(StatusCode::FORBIDDEN, "Invalid form token."));
    }
    let _guard = state.write_gate.write().await;
    let store = state.store.clone();
    run_db(move || store.set_account_sharing(account.id, false)).await?;
    Ok(redirect("/account"))
}

async fn resume_sharing(
    State(state): State<AppState>,
    headers: HeaderMap,
    Form(form): Form<PasswordConfirmForm>,
) -> Result<Response, ApiError> {
    let Some((account, raw)) = web_account(&state, &headers).await? else {
        return Ok(redirect("/login"));
    };
    if form.csrf != csrf_token(&raw) {
        return Ok(error_page(StatusCode::FORBIDDEN, "Invalid form token."));
    }
    let _guard = state.write_gate.write().await;
    let store = state.store.clone();
    let resumed = run_db(move || {
        if !auth::verify_account_password(&store, account.id, &form.password)? {
            return Ok(false);
        }
        store.set_account_sharing(account.id, true)
    })
    .await?;
    if !resumed {
        return Ok(error_page(
            StatusCode::FORBIDDEN,
            "Verify your email and enter the correct password before sharing.",
        ));
    }
    Ok(redirect("/account?message=sharing-resumed"))
}

async fn revoke_session(
    State(state): State<AppState>,
    headers: HeaderMap,
    Form(form): Form<SessionForm>,
) -> Result<Response, ApiError> {
    let Some((account, raw)) = web_account(&state, &headers).await? else {
        return Ok(redirect("/login"));
    };
    if form.csrf != csrf_token(&raw) {
        return Ok(error_page(StatusCode::FORBIDDEN, "Invalid form token."));
    }
    let store = state.store.clone();
    run_db(move || {
        let current = auth::account_for_token(&store, &raw, "web")?
            .map(|(_, session)| session)
            .unwrap_or_default();
        store.connection()?.execute(
            "DELETE FROM auth_tokens WHERE user_id=?1 AND session_id=?2 AND session_id<>?3",
            rusqlite::params![account.id, form.session_id, current],
        )?;
        Ok(())
    })
    .await?;
    Ok(redirect("/account?message=sessions-revoked"))
}

async fn revoke_other_sessions(
    State(state): State<AppState>,
    headers: HeaderMap,
    Form(form): Form<CsrfForm>,
) -> Result<Response, ApiError> {
    let Some((account, raw)) = web_account(&state, &headers).await? else {
        return Ok(redirect("/login"));
    };
    if form.csrf != csrf_token(&raw) {
        return Ok(error_page(StatusCode::FORBIDDEN, "Invalid form token."));
    }
    let store = state.store.clone();
    run_db(move || {
        let current = auth::account_for_token(&store, &raw, "web")?
            .map(|(_, session)| session)
            .unwrap_or_default();
        store.connection()?.execute(
            "DELETE FROM auth_tokens WHERE user_id=?1 AND session_id<>?2",
            rusqlite::params![account.id, current],
        )?;
        Ok(())
    })
    .await?;
    Ok(redirect("/account?message=sessions-revoked"))
}

async fn close_account(
    State(state): State<AppState>,
    headers: HeaderMap,
    Form(form): Form<CloseForm>,
) -> Result<Response, ApiError> {
    let Some((account, raw)) = web_account(&state, &headers).await? else {
        return Ok(redirect("/login"));
    };
    if form.csrf != csrf_token(&raw) {
        return Ok(error_page(StatusCode::FORBIDDEN, "Invalid form token."));
    }
    if form.confirm != "DELETE ACCOUNT" {
        return Ok(error_page(
            StatusCode::BAD_REQUEST,
            "Type DELETE ACCOUNT to confirm.",
        ));
    }
    if account.admin {
        return Ok(error_page(
            StatusCode::FORBIDDEN,
            "Administrator accounts cannot be closed here.",
        ));
    }
    let _guard = state.write_gate.write().await;
    let store = state.store.clone();
    let policy = state.policy();
    let changes = run_db(move || {
        if !auth::verify_account_password(&store, account.id, &form.password)? {
            return Ok(None);
        }
        store.close_own_account(account.id, &policy)
    })
    .await?;
    let Some(changes) = changes else {
        return Ok(error_page(StatusCode::FORBIDDEN, "Password is incorrect."));
    };
    for change in changes {
        publish_change(&state, change);
    }
    let mut response = redirect("/login");
    clear_cookie(&mut response, "imu_nav_session");
    clear_cookie(&mut response, "imu_nav_admin_return");
    Ok(response)
}

async fn stop_impersonation(
    State(state): State<AppState>,
    uri: Uri,
    headers: HeaderMap,
    Form(form): Form<CsrfForm>,
) -> Result<Response, ApiError> {
    let Some(raw) = cookie_token(&headers, "imu_nav_session") else {
        return Ok(redirect("/login"));
    };
    if form.csrf != csrf_token(&raw) {
        return Ok(error_page(StatusCode::FORBIDDEN, "Invalid form token."));
    }
    let admin_raw = cookie_token(&headers, "imu_nav_admin_return");
    let store = state.store.clone();
    let target_token = raw.clone();
    let return_token = admin_raw.clone();
    let restored = run_db(move || {
        let impersonation = impersonation_for_token(&store, &target_token)?;
        let admin = return_token
            .as_deref()
            .map(|raw| auth::account_for_token(&store, raw, "web"))
            .transpose()?
            .flatten()
            .map(|(account, _)| account)
            .filter(|account| account.admin);
        let actor = match (impersonation, admin, return_token) {
            (Some(record), Some(admin), Some(return_token))
                if record.admin_token_hash == auth::digest(&return_token)
                    && admin.id == record.actor_id =>
            {
                Some((admin.id, record.target_id.to_string()))
            }
            (None, Some(admin), _) => Some((admin.id, "expired_session".to_owned())),
            _ => None,
        };
        store.connection()?.execute(
            "DELETE FROM auth_tokens WHERE token_hash=?1 AND kind='web_impersonated'",
            [auth::digest(&target_token)],
        )?;
        if let Some((actor_id, target)) = &actor {
            store.audit(*actor_id, "stop_impersonation", target)?;
        }
        Ok(actor.is_some())
    })
    .await?;
    let mut response = redirect(if restored { "/admin" } else { "/login" });
    if let (true, Some(admin_raw)) = (restored, admin_raw) {
        set_session_cookie(
            &mut response,
            "imu_nav_session",
            &admin_raw,
            WEB_LIFETIME_S,
            secure_cookie(&state, &uri, &headers),
        );
    } else {
        clear_cookie(&mut response, "imu_nav_session");
    }
    clear_cookie(&mut response, "imu_nav_admin_return");
    Ok(response)
}

async fn logout(
    State(state): State<AppState>,
    headers: HeaderMap,
    Form(form): Form<CsrfForm>,
) -> Result<Response, ApiError> {
    let Some((_, raw)) = web_account(&state, &headers).await? else {
        return Ok(redirect("/login"));
    };
    if form.csrf != csrf_token(&raw) {
        return Ok(error_page(StatusCode::FORBIDDEN, "Invalid form token."));
    }
    let store = state.store.clone();
    run_db(move || {
        if let Some(record) = impersonation_for_token(&store, &raw)? {
            store.connection()?.execute(
                "DELETE FROM auth_tokens WHERE token_hash=?1 AND kind='web'",
                [record.admin_token_hash],
            )?;
        }
        store.connection()?.execute(
            "DELETE FROM auth_tokens WHERE token_hash=?1 AND kind IN ('web','web_impersonated')",
            [auth::digest(&raw)],
        )?;
        Ok(())
    })
    .await?;
    let mut response = redirect("/login");
    clear_cookie(&mut response, "imu_nav_session");
    clear_cookie(&mut response, "imu_nav_admin_return");
    Ok(response)
}

/// Remove one exact row after both the browser token and account prefix pass validation.
async fn delete_one(
    State(state): State<AppState>,
    headers: HeaderMap,
    Form(form): Form<DeleteForm>,
) -> Result<Response, ApiError> {
    let Some((account, raw)) = web_account(&state, &headers).await? else {
        return Ok(redirect("/login"));
    };
    if form.csrf != csrf_token(&raw) {
        return Ok(error_page(StatusCode::FORBIDDEN, "Invalid form token."));
    }
    let Ok(radio) = Radio::from_str(&form.radio) else {
        return Ok(error_page(
            StatusCode::BAD_REQUEST,
            "Invalid tower identifier.",
        ));
    };
    let key = CellKey {
        radio,
        mcc: form.mcc,
        mnc: form.mnc,
        area: form.area,
        cid: form.cid,
    };
    let store = state.store.clone();
    let _write_guard = state.write_gate.write().await;
    let policy = state.policy();
    let change =
        run_db(move || store.delete_own_contribution(account.id, &key, &form.device, &policy))
            .await?;
    let Some(change) = change else {
        return Ok(error_page(StatusCode::NOT_FOUND, "Contribution not found."));
    };
    publish_change(&state, change);
    Ok(redirect("/account?message=deleted"))
}

/// Remove all account rows, including rows outside the current page.
async fn delete_all(
    State(state): State<AppState>,
    headers: HeaderMap,
    Form(form): Form<DeleteAllForm>,
) -> Result<Response, ApiError> {
    let Some((account, raw)) = web_account(&state, &headers).await? else {
        return Ok(redirect("/login"));
    };
    if form.csrf != csrf_token(&raw) {
        return Ok(error_page(StatusCode::FORBIDDEN, "Invalid form token."));
    }
    if form.confirm != "DELETE" {
        return Ok(error_page(
            StatusCode::BAD_REQUEST,
            "Type DELETE to confirm.",
        ));
    }
    let store = state.store.clone();
    let _write_guard = state.write_gate.write().await;
    let policy = state.policy();
    let changes = run_db(move || store.delete_all_own_contributions(account.id, &policy)).await?;
    for change in changes {
        publish_change(&state, change);
    }
    Ok(redirect("/account?message=all-deleted"))
}

fn publish_change(state: &AppState, change: OwnContributionChange) {
    let event = match change {
        OwnContributionChange::Updated(tower) => ServerEvent::TowerUpserted { tower },
        OwnContributionChange::Removed(key) => ServerEvent::TowerDeleted { key },
    };
    let _ = state.events.send(event);
}

pub(crate) fn csrf_token(raw: &str) -> String {
    auth::digest(&format!("web-csrf:{raw}"))
}

fn render(status: StatusCode, template: &impl Template) -> Result<Response, ApiError> {
    let body = template
        .render()
        .map_err(|error| ApiError::from(anyhow::Error::new(error)))?;
    Ok(html_response(status, body))
}

fn error_page(status: StatusCode, message: &'static str) -> Response {
    html_response(
        status,
        format!(
            "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Account</title><body><h1>{message}</h1><p><a href=\"/account\">Return to account</a></p><script src=\"/web-locale.js\" defer></script></body></html>"
        ),
    )
}

/// Serve browser translations from the same origin so account pages keep a strict CSP.
async fn locale_script() -> Response {
    (
        [
            (header::CONTENT_TYPE, "text/javascript; charset=utf-8"),
            (header::CACHE_CONTROL, "public, max-age=3600"),
        ],
        include_str!("web_locale.js"),
    )
        .into_response()
}

fn redirect(path: &'static str) -> Response {
    let mut response = StatusCode::SEE_OTHER.into_response();
    response
        .headers_mut()
        .insert(header::LOCATION, path.parse().expect("static path"));
    no_store(response)
}

fn html_response(status: StatusCode, body: String) -> Response {
    no_store((status, Html(body)).into_response())
}

fn no_store(mut response: Response) -> Response {
    let headers = response.headers_mut();
    headers.insert(
        header::CACHE_CONTROL,
        "no-store".parse().expect("static header"),
    );
    headers.insert(
        header::CONTENT_SECURITY_POLICY,
        CONTENT_SECURITY_POLICY.parse().expect("static header"),
    );
    headers.insert(
        "referrer-policy",
        "no-referrer".parse().expect("static header"),
    );
    headers.insert(
        "x-content-type-options",
        "nosniff".parse().expect("static header"),
    );
    headers.insert("x-frame-options", "DENY".parse().expect("static header"));
    response
}
