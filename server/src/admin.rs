use crate::api::{ApiError, AppState, path_key, run_db};
use crate::{Consensus, StoreCounts};
use askama::Template;
use axum::Router;
use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{Html, IntoResponse, Response};
use axum::routing::get;
use base64::Engine as _;
use base64::engine::general_purpose::STANDARD;
use serde::Deserialize;
use std::collections::HashSet;

const DEFAULT_LIMIT: usize = 100;
const MAX_LIMIT: usize = 1_000;
const CONTENT_SECURITY_POLICY: &str = "default-src 'none'; style-src https://cdn.jsdelivr.net; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";

/// Add the server-rendered, read-only management pages.
pub(crate) fn router() -> Router<AppState> {
    Router::new().route("/admin", get(dashboard)).route(
        "/admin/towers/{radio}/{mcc}/{mnc}/{area}/{cid}",
        get(tower_detail),
    )
}

/// Filters accepted by the management dashboard.
#[derive(Clone, Debug, Default, Deserialize)]
struct AdminQuery {
    mcc: Option<String>,
    limit: Option<usize>,
}

impl AdminQuery {
    /// Parse and validate the optional comma-separated MCC filter.
    fn mccs(&self) -> Result<Option<HashSet<i64>>, AdminError> {
        let Some(value) = self
            .mcc
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty())
        else {
            return Ok(None);
        };
        let mccs = value
            .split(',')
            .map(str::trim)
            .map(str::parse::<i64>)
            .collect::<Result<HashSet<_>, _>>()
            .map_err(|_| AdminError::bad_request("MCC must contain comma-separated numbers"))?;
        if mccs.is_empty() || mccs.iter().any(|mcc| !(1..=999).contains(mcc)) {
            return Err(AdminError::bad_request(
                "MCC values must be between 1 and 999",
            ));
        }
        Ok(Some(mccs))
    }

    fn limit(&self) -> usize {
        self.limit.unwrap_or(DEFAULT_LIMIT).clamp(1, MAX_LIMIT)
    }
}

/// Fully formatted row passed to Askama so templates stay presentation-only.
struct TowerRow {
    detail_path: String,
    radio: String,
    mcc: i64,
    mnc: i64,
    area: i64,
    cid: i64,
    lat: String,
    lon: String,
    range_m: String,
    samples: i64,
    devices: usize,
    seeded: bool,
    status: &'static str,
    status_class: &'static str,
    updated_s: i64,
}

impl TowerRow {
    /// Convert domain data to escaped display values and a publication status.
    fn new(consensus: &Consensus, minimum_devices: usize) -> Self {
        let published = consensus.seeded || consensus.devices >= minimum_devices;
        let (status, status_class) = if consensus.seeded {
            ("Seeded", "text-bg-primary")
        } else if published {
            ("Published", "text-bg-success")
        } else {
            ("Pending", "text-bg-warning")
        };
        let radio = consensus.tower.key.radio.to_string();
        let detail_path = format!(
            "/admin/towers/{}/{}/{}/{}/{}",
            radio,
            consensus.tower.key.mcc,
            consensus.tower.key.mnc,
            consensus.tower.key.area,
            consensus.tower.key.cid
        );
        Self {
            detail_path,
            radio,
            mcc: consensus.tower.key.mcc,
            mnc: consensus.tower.key.mnc,
            area: consensus.tower.key.area,
            cid: consensus.tower.key.cid,
            lat: format!("{:.7}", consensus.tower.lat),
            lon: format!("{:.7}", consensus.tower.lon),
            range_m: format!("{:.0}", consensus.tower.range_m),
            samples: consensus.tower.samples,
            devices: consensus.devices,
            seeded: consensus.seeded,
            status,
            status_class,
            updated_s: consensus.updated_s,
        }
    }
}

/// Askama context for the dashboard and recent tower table.
#[derive(Template)]
#[template(path = "admin.html")]
struct AdminTemplate {
    counts: StoreCounts,
    pending: usize,
    minimum_devices: usize,
    mcc: String,
    has_mcc_filter: bool,
    limit: usize,
    towers: Vec<TowerRow>,
}

/// Askama context for an individual tower's read-only details.
#[derive(Template)]
#[template(path = "tower.html")]
struct TowerTemplate {
    tower: TowerRow,
    minimum_devices: usize,
}

/// Render summary cards and the newest consensus rows without exposing mutation controls.
async fn dashboard(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<AdminQuery>,
) -> Result<Response, AdminError> {
    authorize(&headers, state.config.api_key.as_deref())?;
    let mccs = query.mccs()?;
    let limit = query.limit();
    let store = state.store.clone();
    let policy = state.config.policy.clone();
    let (counts, towers) = run_db(move || {
        let counts = store.management_counts(&policy)?;
        let towers = store.query_recent_all(mccs.as_ref(), limit)?;
        Ok((counts, towers))
    })
    .await?;
    let minimum_devices = state.config.policy.min_devices;
    let mcc = query.mcc.unwrap_or_default();
    let has_mcc_filter = !mcc.trim().is_empty();
    render(&AdminTemplate {
        counts,
        pending: counts.consensus.saturating_sub(counts.published),
        minimum_devices,
        mcc,
        has_mcc_filter,
        limit,
        towers: towers
            .iter()
            .map(|tower| TowerRow::new(tower, minimum_devices))
            .collect(),
    })
}

/// Render one tower and its publication metadata without edit or delete actions.
async fn tower_detail(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(path): Path<(String, i64, i64, i64, i64)>,
) -> Result<Response, AdminError> {
    authorize(&headers, state.config.api_key.as_deref())?;
    let key = path_key(path)?;
    let store = state.store.clone();
    let consensus = run_db(move || store.consensus(&key))
        .await?
        .ok_or_else(AdminError::not_found)?;
    let minimum_devices = state.config.policy.min_devices;
    render(&TowerTemplate {
        tower: TowerRow::new(&consensus, minimum_devices),
        minimum_devices,
    })
}

/// Accept existing bearer credentials or browser-native Basic authentication.
fn authorize(headers: &HeaderMap, api_key: Option<&str>) -> Result<(), AdminError> {
    let Some(api_key) = api_key.filter(|value| !value.is_empty()) else {
        return Ok(());
    };
    let Some(value) = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
    else {
        return Err(AdminError::unauthorized());
    };
    if value.strip_prefix("Bearer ") == Some(api_key) {
        return Ok(());
    }
    let valid_basic = value
        .strip_prefix("Basic ")
        .and_then(|encoded| STANDARD.decode(encoded).ok())
        .and_then(|decoded| String::from_utf8(decoded).ok())
        .and_then(|credentials| {
            let (username, password) = credentials.split_once(':')?;
            Some(username == "admin" && password == api_key)
        })
        .unwrap_or(false);
    valid_basic
        .then_some(())
        .ok_or_else(AdminError::unauthorized)
}

/// Render an Askama page and attach restrictive browser security headers.
fn render(template: &impl Template) -> Result<Response, AdminError> {
    let body = template.render().map_err(|error| {
        tracing::error!(error = %error, "admin template failed");
        AdminError::internal()
    })?;
    Ok(html_response(StatusCode::OK, body))
}

fn html_response(status: StatusCode, body: String) -> Response {
    let mut response = (status, Html(body)).into_response();
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

/// Browser-facing error with a safe message and optional authentication challenge.
struct AdminError {
    status: StatusCode,
    message: &'static str,
    authentication_challenge: bool,
}

impl AdminError {
    fn unauthorized() -> Self {
        Self {
            status: StatusCode::UNAUTHORIZED,
            message: "Authentication is required to view the admin interface.",
            authentication_challenge: true,
        }
    }

    fn bad_request(message: &'static str) -> Self {
        Self {
            status: StatusCode::BAD_REQUEST,
            message,
            authentication_challenge: false,
        }
    }

    fn not_found() -> Self {
        Self {
            status: StatusCode::NOT_FOUND,
            message: "Tower not found.",
            authentication_challenge: false,
        }
    }

    fn internal() -> Self {
        Self {
            status: StatusCode::INTERNAL_SERVER_ERROR,
            message: "The admin page could not be generated.",
            authentication_challenge: false,
        }
    }
}

impl From<ApiError> for AdminError {
    fn from(error: ApiError) -> Self {
        match error.0 {
            StatusCode::BAD_REQUEST => Self::bad_request("Invalid tower identifier."),
            StatusCode::NOT_FOUND => Self::not_found(),
            _ => Self::internal(),
        }
    }
}

impl IntoResponse for AdminError {
    fn into_response(self) -> Response {
        let body = format!(
            "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>{}</title><body><h1>{}</h1><p><a href=\"/admin\">Return to dashboard</a></p></body></html>",
            self.status.as_str(),
            self.message
        );
        let mut response = html_response(self.status, body);
        if self.authentication_challenge {
            response.headers_mut().insert(
                header::WWW_AUTHENTICATE,
                "Basic realm=\"IMU Nav admin\", charset=\"UTF-8\""
                    .parse()
                    .expect("static header"),
            );
        }
        response
    }
}
