use crate::CellKey;
use crate::api::{ApiError, AppState, path_key, run_db};
use crate::auth;
use crate::web;
use crate::{CellTower, Consensus, Policy, ServerEvent, StoreCounts};
use askama::Template;
use axum::body::Body;
use axum::extract::{DefaultBodyLimit, Multipart, Path, Query, State};
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{Html, IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Form, Router};
use futures_util::StreamExt;
use serde::Deserialize;
use std::collections::HashSet;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use tokio::io::AsyncWriteExt;
use tokio_util::io::ReaderStream;

#[derive(Default)]
pub(crate) struct JobState {
    pub id: Option<i64>,
    pub kind: String,
    pub status: String,
    pub processed: Arc<AtomicUsize>,
    pub cancel: Arc<AtomicBool>,
}

impl JobState {
    pub(crate) fn recovered(record: Option<crate::store::management::JobRecord>) -> Self {
        let Some(record) = record else {
            return Self::default();
        };
        Self {
            id: Some(record.id),
            kind: record.kind,
            status: record.status,
            processed: Arc::new(AtomicUsize::new(record.processed)),
            cancel: Arc::new(AtomicBool::new(false)),
        }
    }
}

const DEFAULT_LIMIT: usize = 100;
const MAX_LIMIT: usize = 1_000;
const CONTENT_SECURITY_POLICY: &str = "default-src 'none'; style-src https://cdn.jsdelivr.net; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";

/// Add authenticated operational pages and form actions.
pub(crate) fn router() -> Router<AppState> {
    Router::new()
        .route("/admin", get(dashboard))
        .route(
            "/admin/towers/{radio}/{mcc}/{mnc}/{area}/{cid}",
            get(tower_detail),
        )
        .route(
            "/admin/towers/{radio}/{mcc}/{mnc}/{area}/{cid}/correct",
            post(correct_tower),
        )
        .route(
            "/admin/towers/{radio}/{mcc}/{mnc}/{area}/{cid}/quarantine",
            post(quarantine_tower),
        )
        .route(
            "/admin/towers/{radio}/{mcc}/{mnc}/{area}/{cid}/restore",
            post(restore_tower),
        )
        .route(
            "/admin/towers/{radio}/{mcc}/{mnc}/{area}/{cid}/delete",
            post(delete_tower),
        )
        .route(
            "/admin/towers/{radio}/{mcc}/{mnc}/{area}/{cid}/observations/delete",
            post(delete_observation),
        )
        .route("/admin/accounts/{id}/suspend", post(suspend_account))
        .route("/admin/accounts/{id}/restore", post(restore_account))
        .route("/admin/policy", post(update_policy))
        .route("/admin/jobs/cancel", post(cancel_job))
        .route("/admin/export/{kind}", get(export))
        .route(
            "/admin/import",
            post(import).layer(DefaultBodyLimit::max(512 * 1024 * 1024)),
        )
}

/// Filters accepted by the management dashboard.
#[derive(Clone, Debug, Default, Deserialize)]
struct AdminQuery {
    mcc: Option<String>,
    limit: Option<usize>,
    key: Option<String>,
    email: Option<String>,
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
    quarantined: bool,
}

impl TowerRow {
    /// Convert domain data to escaped display values and a publication status.
    fn new(consensus: &Consensus, minimum_devices: usize, quarantined: bool) -> Self {
        let published = consensus.seeded || consensus.devices >= minimum_devices;
        let (status, status_class) = if quarantined {
            ("Quarantined", "text-bg-danger")
        } else if consensus.seeded {
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
            quarantined,
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
    email: String,
    towers: Vec<TowerRow>,
    csrf: String,
    accounts: Vec<crate::store::management::ManagedAccount>,
    audit: Vec<crate::store::management::AuditEntry>,
    policy: Policy,
    job_kind: String,
    job_status: String,
    job_processed: usize,
}

/// Askama context for tower details and moderation controls.
#[derive(Template)]
#[template(path = "tower.html")]
struct TowerTemplate {
    tower: TowerRow,
    minimum_devices: usize,
    csrf: String,
    observations: Vec<crate::store::OwnContribution>,
}

/// Render summary cards and the newest consensus rows with management controls.
async fn dashboard(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<AdminQuery>,
) -> Result<Response, AdminError> {
    let (_, raw) = admin_session(&state, &headers).await?;
    let _visibility = state.activation_gate.read().await;
    if let Some(value) = query
        .key
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        let fields = value.split(':').collect::<Vec<_>>();
        if fields.len() != 5 {
            return Err(AdminError::bad_request(
                "Use RADIO:MCC:MNC:AREA:CID for tower lookup.",
            ));
        }
        let numbers = fields[1..]
            .iter()
            .map(|item| item.parse::<i64>())
            .collect::<Result<Vec<_>, _>>()
            .map_err(|_| AdminError::bad_request("Tower identifiers must be numbers."))?;
        let key = path_key((
            fields[0].to_owned(),
            numbers[0],
            numbers[1],
            numbers[2],
            numbers[3],
        ))?;
        return Ok(redirect(&tower_path(&key)));
    }
    let mccs = query.mccs()?;
    let limit = query.limit();
    let store = state.store.clone();
    let policy = state.policy();
    let policy_for_query = policy.clone();
    let account_filter = query.email.clone().unwrap_or_default();
    let (counts, towers, accounts, audit) = run_db(move || {
        let counts = store.management_counts(&policy_for_query)?;
        let towers = store.query_recent_all(mccs.as_ref(), limit)?;
        let rows = towers
            .into_iter()
            .map(|tower| {
                let hidden = store.quarantined(&tower.tower.key)?;
                Ok(TowerRow::new(&tower, policy_for_query.min_devices, hidden))
            })
            .collect::<anyhow::Result<Vec<_>>>()?;
        Ok((
            counts,
            rows,
            store.accounts(&account_filter)?,
            store.recent_audit()?,
        ))
    })
    .await?;
    let minimum_devices = policy.min_devices;
    let mcc = query.mcc.unwrap_or_default();
    let has_mcc_filter = !mcc.trim().is_empty();
    let (job_kind, job_status, job_processed) = {
        let job = state.job.lock().map_err(|_| AdminError::internal())?;
        (
            job.kind.clone(),
            job.status.clone(),
            job.processed.load(Ordering::Relaxed),
        )
    };
    render(&AdminTemplate {
        counts,
        pending: counts
            .consensus
            .saturating_sub(counts.published + counts.quarantined),
        minimum_devices,
        mcc,
        has_mcc_filter,
        limit,
        email: query.email.unwrap_or_default(),
        towers,
        csrf: web::csrf_token(&raw),
        accounts,
        audit,
        policy,
        job_kind,
        job_status,
        job_processed,
    })
}

/// Render one tower, its observations, and available actions.
async fn tower_detail(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(path): Path<(String, i64, i64, i64, i64)>,
) -> Result<Response, AdminError> {
    let (_, raw) = admin_session(&state, &headers).await?;
    let _visibility = state.activation_gate.read().await;
    let key = path_key(path)?;
    let store = state.store.clone();
    let detail = run_db(move || {
        Ok((
            store.consensus(&key)?,
            store.quarantined(&key)?,
            store.tower_contributions(&key)?,
        ))
    })
    .await?;
    let (consensus, quarantined, observations) = detail;
    let consensus = consensus.ok_or_else(AdminError::not_found)?;
    let minimum_devices = state.policy().min_devices;
    render(&TowerTemplate {
        tower: TowerRow::new(&consensus, minimum_devices, quarantined),
        minimum_devices,
        csrf: web::csrf_token(&raw),
        observations,
    })
}

async fn admin_session(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<(auth::Account, String), AdminError> {
    let Some((account, raw)) = web::web_account(state, headers).await? else {
        return Err(AdminError::unauthorized());
    };
    if !account.admin {
        return Err(AdminError::forbidden());
    }
    Ok((account, raw))
}

fn check_csrf(raw: &str, supplied: &str) -> Result<(), AdminError> {
    if supplied == web::csrf_token(raw) {
        Ok(())
    } else {
        Err(AdminError::forbidden())
    }
}

fn tower_path(key: &CellKey) -> String {
    format!(
        "/admin/towers/{}/{}/{}/{}/{}",
        key.radio, key.mcc, key.mnc, key.area, key.cid
    )
}

fn redirect(path: &str) -> Response {
    let mut response = StatusCode::SEE_OTHER.into_response();
    response.headers_mut().insert(
        header::LOCATION,
        path.parse().expect("validated local path"),
    );
    response
}

#[derive(Deserialize)]
struct CsrfForm {
    csrf: String,
}

#[derive(Deserialize)]
struct CorrectionForm {
    csrf: String,
    lat: f64,
    lon: f64,
    range_m: f64,
    samples: i64,
}

#[derive(Deserialize)]
struct ObservationForm {
    csrf: String,
    device: String,
}

#[derive(Deserialize)]
struct ConfirmForm {
    csrf: String,
    confirm: String,
}

#[derive(Deserialize)]
struct PolicyForm {
    csrf: String,
    max_samples_per_device: i64,
    min_devices: usize,
    outlier_min_m: f64,
    max_jump_m: f64,
    max_range_m: f64,
    max_rows_per_upload: usize,
    max_uploads_per_hour_per_device: usize,
    max_uploads_per_hour_per_ip: usize,
    max_devices_per_ip_per_day: usize,
    ukraine_only: Option<String>,
}

async fn correct_tower(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(path): Path<(String, i64, i64, i64, i64)>,
    Form(form): Form<CorrectionForm>,
) -> Result<Response, AdminError> {
    let (account, raw) = admin_session(&state, &headers).await?;
    check_csrf(&raw, &form.csrf)?;
    let key = path_key(path)?;
    let tower = CellTower {
        key: key.clone(),
        lat: form.lat,
        lon: form.lon,
        range_m: form.range_m,
        samples: form.samples,
    };
    let _guard = state.write_gate.read().await;
    let policy = state.policy();
    let store = state.store.clone();
    let changed = run_db(move || store.correct_tower(account.id, &tower, &policy))
        .await?
        .ok_or_else(|| AdminError::bad_request("Tower coordinates or samples are invalid."))?;
    if !state
        .store
        .quarantined(&key)
        .map_err(|_| AdminError::internal())?
    {
        let _ = state
            .events
            .send(ServerEvent::TowerUpserted { tower: changed });
    }
    Ok(redirect(&tower_path(&key)))
}

async fn set_quarantine(
    state: AppState,
    headers: HeaderMap,
    path: (String, i64, i64, i64, i64),
    form: CsrfForm,
    hidden: bool,
) -> Result<Response, AdminError> {
    let (account, raw) = admin_session(&state, &headers).await?;
    check_csrf(&raw, &form.csrf)?;
    let key = path_key(path)?;
    let _guard = state.write_gate.read().await;
    let store = state.store.clone();
    let target = key.clone();
    let changed = run_db(move || store.set_quarantined(account.id, &target, hidden)).await?;
    if !changed {
        return Err(AdminError::not_found());
    }
    if hidden {
        let _ = state
            .events
            .send(ServerEvent::TowerDeleted { key: key.clone() });
    } else if let Some(tower) = state
        .store
        .consensus(&key)
        .map_err(|_| AdminError::internal())?
    {
        let _ = state.events.send(ServerEvent::TowerUpserted { tower });
    }
    Ok(redirect(&tower_path(&key)))
}

async fn quarantine_tower(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(path): Path<(String, i64, i64, i64, i64)>,
    Form(form): Form<CsrfForm>,
) -> Result<Response, AdminError> {
    set_quarantine(state, headers, path, form, true).await
}

async fn restore_tower(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(path): Path<(String, i64, i64, i64, i64)>,
    Form(form): Form<CsrfForm>,
) -> Result<Response, AdminError> {
    set_quarantine(state, headers, path, form, false).await
}

async fn delete_tower(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(path): Path<(String, i64, i64, i64, i64)>,
    Form(form): Form<ConfirmForm>,
) -> Result<Response, AdminError> {
    let (account, raw) = admin_session(&state, &headers).await?;
    check_csrf(&raw, &form.csrf)?;
    if form.confirm != "DELETE" {
        return Err(AdminError::bad_request("Type DELETE to confirm."));
    }
    let key = path_key(path)?;
    let _guard = state.write_gate.read().await;
    let store = state.store.clone();
    let removed = run_db(move || store.delete_quarantined(account.id, &key)).await?;
    if !removed {
        return Err(AdminError::conflict(
            "Quarantine this tower before deleting it.",
        ));
    }
    Ok(redirect("/admin"))
}

async fn delete_observation(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(path): Path<(String, i64, i64, i64, i64)>,
    Form(form): Form<ObservationForm>,
) -> Result<Response, AdminError> {
    let (account, raw) = admin_session(&state, &headers).await?;
    check_csrf(&raw, &form.csrf)?;
    let key = path_key(path)?;
    let _guard = state.write_gate.read().await;
    let policy = state.policy();
    let store = state.store.clone();
    let target = key.clone();
    let change =
        run_db(move || store.remove_tower_contribution(account.id, &target, &form.device, &policy))
            .await?
            .ok_or_else(AdminError::not_found)?;
    if !state
        .store
        .quarantined(&key)
        .map_err(|_| AdminError::internal())?
    {
        let event = match change {
            crate::store::OwnContributionChange::Updated(tower) => {
                ServerEvent::TowerUpserted { tower }
            }
            crate::store::OwnContributionChange::Removed(key) => ServerEvent::TowerDeleted { key },
        };
        let _ = state.events.send(event);
    }
    Ok(redirect(&tower_path(&key)))
}

async fn account_action(
    state: AppState,
    headers: HeaderMap,
    id: i64,
    form: CsrfForm,
    suspended: bool,
) -> Result<Response, AdminError> {
    let (account, raw) = admin_session(&state, &headers).await?;
    check_csrf(&raw, &form.csrf)?;
    let store = state.store.clone();
    let changed = run_db(move || store.set_suspended(account.id, id, suspended)).await?;
    if !changed {
        return Err(AdminError::conflict(
            "Account not found or last administrator cannot be suspended.",
        ));
    }
    Ok(redirect("/admin"))
}

async fn suspend_account(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    Form(form): Form<CsrfForm>,
) -> Result<Response, AdminError> {
    account_action(state, headers, id, form, true).await
}

async fn restore_account(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    Form(form): Form<CsrfForm>,
) -> Result<Response, AdminError> {
    account_action(state, headers, id, form, false).await
}

async fn begin_job(
    state: &AppState,
    kind: &str,
) -> Result<(Arc<AtomicBool>, Arc<AtomicUsize>), AdminError> {
    let (cancel, processed) = {
        let mut job = state.job.lock().map_err(|_| AdminError::internal())?;
        if job.status == "running" {
            return Err(AdminError::conflict("Another job is running."));
        }
        job.id = None;
        job.kind = kind.to_owned();
        job.status = "running".to_owned();
        job.cancel = Arc::new(AtomicBool::new(false));
        job.processed = Arc::new(AtomicUsize::new(0));
        (job.cancel.clone(), job.processed.clone())
    };
    let store = state.store.clone();
    let kind = kind.to_owned();
    let id = match run_db(move || store.start_admin_job(&kind)).await {
        Ok(id) => id,
        Err(error) => {
            state.job.lock().map_err(|_| AdminError::internal())?.status =
                "failed; check server logs".to_owned();
            return Err(error.into());
        }
    };
    state.job.lock().map_err(|_| AdminError::internal())?.id = Some(id);
    Ok((cancel, processed))
}

async fn finish_job(state: &AppState, result: Result<bool, ApiError>) {
    let status = match result {
        Ok(true) => "complete".to_owned(),
        Ok(false) => "cancelled".to_owned(),
        Err(error) => {
            tracing::error!(status = %error.0, "admin job failed");
            "failed; check server logs".to_owned()
        }
    };
    let (id, processed) = {
        let job = state.job.lock().expect("job lock");
        (job.id, job.processed.load(Ordering::Relaxed))
    };
    if let Some(id) = id {
        let store = state.store.clone();
        let saved_status = status.clone();
        if let Err(error) =
            run_db(move || store.finish_admin_job(id, &saved_status, processed)).await
        {
            tracing::error!(code = %error.0, "cannot save admin job status");
        }
    }
    state.job.lock().expect("job lock").status = status;
}

async fn update_policy(
    State(state): State<AppState>,
    headers: HeaderMap,
    Form(form): Form<PolicyForm>,
) -> Result<Response, AdminError> {
    let (account, raw) = admin_session(&state, &headers).await?;
    check_csrf(&raw, &form.csrf)?;
    let policy = Policy {
        max_samples_per_device: form.max_samples_per_device,
        min_devices: form.min_devices,
        outlier_min_m: form.outlier_min_m,
        max_jump_m: form.max_jump_m,
        max_range_m: form.max_range_m,
        max_rows_per_upload: form.max_rows_per_upload,
        max_uploads_per_hour_per_device: form.max_uploads_per_hour_per_device,
        max_uploads_per_hour_per_ip: form.max_uploads_per_hour_per_ip,
        max_devices_per_ip_per_day: form.max_devices_per_ip_per_day,
        ukraine_only: form.ukraine_only.is_some(),
    };
    policy
        .validate()
        .map_err(|_| AdminError::bad_request("Policy values must be finite and positive."))?;
    let (cancel, progress) = begin_job(&state, "policy recalculation").await?;
    tokio::spawn(async move {
        let _guard = state.write_gate.write().await;
        let store = state.store.clone();
        let applied_policy = policy.clone();
        let visibility = state.activation_gate.clone();
        let active = state.policy.clone();
        let result = run_db(move || {
            store.apply_policy_live(&applied_policy, &cancel, &progress, &visibility, &active)
        })
        .await;
        if matches!(result, Ok(true)) {
            if let Err(error) = state.store.audit(account.id, "update_policy", "server") {
                tracing::error!(%error, "policy audit failed");
            }
            let _ = state.events.send(ServerEvent::ResyncRequired { missed: 0 });
        }
        finish_job(&state, result).await;
    });
    Ok(redirect("/admin"))
}

async fn cancel_job(
    State(state): State<AppState>,
    headers: HeaderMap,
    Form(form): Form<CsrfForm>,
) -> Result<Response, AdminError> {
    let (_, raw) = admin_session(&state, &headers).await?;
    check_csrf(&raw, &form.csrf)?;
    state
        .job
        .lock()
        .map_err(|_| AdminError::internal())?
        .cancel
        .store(true, Ordering::Relaxed);
    Ok(redirect("/admin"))
}

async fn import(
    State(state): State<AppState>,
    headers: HeaderMap,
    mut multipart: Multipart,
) -> Result<Response, AdminError> {
    let (account, raw) = admin_session(&state, &headers).await?;
    let token = multipart
        .next_field()
        .await
        .map_err(|_| AdminError::bad_request("Invalid import form."))?
        .ok_or_else(|| AdminError::bad_request("Missing form token."))?;
    if token.name() != Some("csrf") {
        return Err(AdminError::forbidden());
    }
    check_csrf(
        &raw,
        &token.text().await.map_err(|_| AdminError::forbidden())?,
    )?;
    let mut field = multipart
        .next_field()
        .await
        .map_err(|_| AdminError::bad_request("Invalid import file."))?
        .ok_or_else(|| AdminError::bad_request("Missing import file."))?;
    if field.name() != Some("file") {
        return Err(AdminError::bad_request("Missing import file."));
    }
    let temporary = tempfile::NamedTempFile::new()
        .map_err(|_| AdminError::internal())?
        .into_temp_path();
    let mut file = tokio::fs::File::create(&temporary)
        .await
        .map_err(|_| AdminError::internal())?;
    let mut bytes = 0usize;
    while let Some(chunk) = field
        .chunk()
        .await
        .map_err(|_| AdminError::bad_request("Invalid import file."))?
    {
        bytes += chunk.len();
        if bytes > 512 * 1024 * 1024 {
            return Err(AdminError::bad_request("Import exceeds 512 MiB."));
        }
        file.write_all(&chunk)
            .await
            .map_err(|_| AdminError::internal())?;
    }
    file.flush().await.map_err(|_| AdminError::internal())?;
    drop(file);
    let (cancel, progress) = begin_job(&state, "seed import").await?;
    tokio::spawn(async move {
        let _guard = state.write_gate.write().await;
        let store = state.store.clone();
        let policy = state.policy();
        let result =
            run_db(move || store.import_seeds(&temporary, &policy, &cancel, &progress)).await;
        if let Ok(Some(result)) = &result {
            let _ = state.store.audit(
                account.id,
                "import_seeds",
                &format!("accepted={} rejected={}", result.accepted, result.rejected),
            );
            let _ = state.events.send(ServerEvent::ResyncRequired { missed: 0 });
        }
        finish_job(&state, result.map(|value| value.is_some())).await;
    });
    Ok(redirect("/admin"))
}

async fn export(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(kind): Path<String>,
) -> Result<Response, AdminError> {
    let (account, _) = admin_session(&state, &headers).await?;
    let observations = match kind.as_str() {
        "observations" => true,
        "consensus" => false,
        _ => return Err(AdminError::not_found()),
    };
    let temporary = tempfile::NamedTempFile::new()
        .map_err(|_| AdminError::internal())?
        .into_temp_path();
    let path = temporary.to_path_buf();
    let store = state.store.clone();
    run_db(move || store.export_to_path(&path, observations)).await?;
    let store = state.store.clone();
    run_db(move || {
        store.audit(
            account.id,
            "export",
            if observations {
                "observations"
            } else {
                "consensus"
            },
        )
    })
    .await?;
    let file = tokio::fs::File::open(&temporary)
        .await
        .map_err(|_| AdminError::internal())?;
    let stream = ReaderStream::new(file).map(move |chunk| {
        let _keep = &temporary;
        chunk
    });
    let mut response = Body::from_stream(stream).into_response();
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        "application/gzip".parse().expect("static header"),
    );
    response.headers_mut().insert(
        header::CONTENT_DISPOSITION,
        format!("attachment; filename=\"{kind}.csv.gz\"")
            .parse()
            .expect("known export name"),
    );
    response.headers_mut().insert(
        header::CACHE_CONTROL,
        "no-store".parse().expect("static header"),
    );
    Ok(response)
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

/// Browser-facing error with a safe message.
struct AdminError {
    status: StatusCode,
    message: &'static str,
}

impl AdminError {
    fn unauthorized() -> Self {
        Self {
            status: StatusCode::SEE_OTHER,
            message: "Sign in to administer the server.",
        }
    }

    fn forbidden() -> Self {
        Self {
            status: StatusCode::FORBIDDEN,
            message: "Administrator access or a valid form token is required.",
        }
    }

    fn conflict(message: &'static str) -> Self {
        Self {
            status: StatusCode::CONFLICT,
            message,
        }
    }

    fn bad_request(message: &'static str) -> Self {
        Self {
            status: StatusCode::BAD_REQUEST,
            message,
        }
    }

    fn not_found() -> Self {
        Self {
            status: StatusCode::NOT_FOUND,
            message: "Tower not found.",
        }
    }

    fn internal() -> Self {
        Self {
            status: StatusCode::INTERNAL_SERVER_ERROR,
            message: "The admin page could not be generated.",
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
        if self.status == StatusCode::SEE_OTHER {
            response
                .headers_mut()
                .insert(header::LOCATION, "/login".parse().expect("static header"));
        }
        response
    }
}
