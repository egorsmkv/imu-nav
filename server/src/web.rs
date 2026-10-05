//! Browser account pages backed by a separate cookie session and account-scoped store queries.

use crate::api::{ApiError, AppState, run_db};
use crate::auth::{self, Account};
use crate::store::{OwnContributionChange, OwnContributionPage};
use crate::{CellKey, Radio, ServerEvent};
use askama::Template;
use axum::Router;
use axum::extract::{ConnectInfo, Form, Query, State};
use axum::http::{HeaderMap, StatusCode, Uri, header};
use axum::response::{Html, IntoResponse, Response};
use axum::routing::{get, post};
use rusqlite::params;
use serde::Deserialize;
use std::net::SocketAddr;
use std::str::FromStr;

const WEB_LIFETIME_S: i64 = 7 * 24 * 60 * 60;
const PAGE_SIZE: usize = 100;
const CONTENT_SECURITY_POLICY: &str = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";

/// Account pages use normal HTML forms; Android keeps the JSON bearer-token API.
pub(crate) fn router() -> Router<AppState> {
    Router::new()
        .route("/", get(home))
        .route("/login", get(login_page).post(login))
        .route("/signup", get(signup_page).post(signup))
        .route("/account", get(account_page))
        .route("/account/logout", post(logout))
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
}

/// Preformatted fields keep display logic and URL construction out of the template.
struct AccountRow {
    radio: String,
    mcc: i64,
    mnc: i64,
    area: i64,
    cid: i64,
    device: String,
    lat: String,
    lon: String,
    range_m: String,
    samples: i64,
    updated_s: i64,
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
                device: row.device,
                lat: format!("{:.7}", row.lat),
                lon: format!("{:.7}", row.lon),
                range_m: format!("{:.0}", row.range_m),
                samples: row.samples,
                updated_s: row.updated_s,
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
struct AccountQuery {
    page: Option<usize>,
}

#[derive(Deserialize)]
struct CsrfForm {
    csrf: String,
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
    let account = run_db(move || auth::register_account(&store, &lookup_email, &password)).await?;
    let Some(account) = account else {
        return form_page(
            true,
            email,
            "That email is already registered.",
            StatusCode::CONFLICT,
        );
    };
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
    let secure = uri.scheme_str() == Some("https")
        || state.config.trust_proxy
            && headers
                .get("x-forwarded-proto")
                .is_some_and(|value| value == "https");
    let mut response = redirect("/account");
    response.headers_mut().insert(
        header::SET_COOKIE,
        format!(
            "imu_nav_session={raw}; Path=/; HttpOnly; SameSite=Strict; Max-Age={WEB_LIFETIME_S}{}",
            if secure { "; Secure" } else { "" }
        )
        .parse()
        .expect("URL-safe token cookie"),
    );
    Ok(response)
}

/// Resolve only a valid, unexpired browser token from an `HttpOnly` cookie.
async fn web_account(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<Option<(Account, String)>, ApiError> {
    let raw = headers
        .get(header::COOKIE)
        .and_then(|value| value.to_str().ok())
        .and_then(|cookies| {
            cookies
                .split(';')
                .map(str::trim)
                .find_map(|cookie| cookie.strip_prefix("imu_nav_session="))
        })
        .filter(|value| {
            value.len() == 43
                && value
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
        })
        .map(str::to_owned);
    let Some(raw) = raw else {
        return Ok(None);
    };
    let store = state.store.clone();
    let token = raw.clone();
    Ok(
        run_db(move || auth::account_for_token(&store, &token, "web"))
            .await?
            .map(|(account, _)| (account, raw)),
    )
}

async fn account_page(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<AccountQuery>,
) -> Result<Response, ApiError> {
    let Some((account, raw)) = web_account(&state, &headers).await? else {
        return Ok(redirect("/login"));
    };
    let page = query.page.unwrap_or(0);
    let Some(offset) = page
        .checked_mul(PAGE_SIZE)
        .filter(|offset| i64::try_from(*offset).is_ok())
    else {
        return Ok(error_page(StatusCode::BAD_REQUEST, "Invalid page number."));
    };
    let store = state.store.clone();
    let contributions =
        run_db(move || store.own_contributions(account.id, PAGE_SIZE, offset)).await?;
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
        },
    )
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
        store.connection()?.execute(
            "DELETE FROM auth_tokens WHERE token_hash=?1 AND kind='web'",
            [auth::digest(&raw)],
        )?;
        Ok(())
    })
    .await?;
    let mut response = redirect("/login");
    response.headers_mut().insert(
        header::SET_COOKIE,
        "imu_nav_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"
            .parse()
            .expect("static cookie"),
    );
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
    let policy = state.config.policy.clone();
    let change =
        run_db(move || store.delete_own_contribution(account.id, &key, &form.device, &policy))
            .await?;
    let Some(change) = change else {
        return Ok(error_page(StatusCode::NOT_FOUND, "Contribution not found."));
    };
    publish_change(&state, change);
    Ok(redirect("/account"))
}

/// Remove all account rows, including rows outside the current page.
async fn delete_all(
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
    let policy = state.config.policy.clone();
    let changes = run_db(move || store.delete_all_own_contributions(account.id, &policy)).await?;
    for change in changes {
        publish_change(&state, change);
    }
    Ok(redirect("/account"))
}

fn publish_change(state: &AppState, change: OwnContributionChange) {
    let event = match change {
        OwnContributionChange::Updated(tower) => ServerEvent::TowerUpserted { tower },
        OwnContributionChange::Removed(key) => ServerEvent::TowerDeleted { key },
    };
    let _ = state.events.send(event);
}

fn csrf_token(raw: &str) -> String {
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
            "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Account</title><body><h1>{message}</h1><p><a href=\"/account\">Return to account</a></p></body></html>"
        ),
    )
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
