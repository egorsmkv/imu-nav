//! Published operator notice and independently revocable upload permissions.

use crate::api::{ApiError, AppState, run_db};
use crate::auth;
use crate::store::OwnContributionChange;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::get;
use axum::{Json, Router};
use serde::{Deserialize, Serialize};

/// Public deployment metadata supplied by the operator, not by an app build.
#[derive(Clone, Debug, Serialize)]
pub struct PrivacyNotice {
    pub version: String,
    pub controller: String,
    pub contact: String,
    pub rights_contact: String,
    pub region: String,
    pub recipients: String,
    pub transfers: String,
    pub account_basis: String,
    pub security_basis: String,
    pub notice_en: String,
    pub notice_uk: String,
    pub notice_ru: String,
    pub tower_retention_months: i64,
    pub diagnostics_retention_days: i64,
}

#[derive(Serialize)]
struct PrivacyState {
    notice: PrivacyNotice,
    tower_upload: bool,
    diagnostics: bool,
}

#[derive(Deserialize)]
struct ConsentRequest {
    notice_version: String,
}

pub(crate) fn router() -> Router<AppState> {
    Router::new()
        .route("/v1/privacy", get(notice))
        .route("/v1/privacy/me", get(mine))
        .route(
            "/v1/privacy/consents/{purpose}",
            axum::routing::put(grant).delete(withdraw),
        )
}

fn configured(state: &AppState) -> Result<PrivacyNotice, ApiError> {
    state
        .config
        .privacy
        .clone()
        .ok_or(ApiError(StatusCode::NOT_FOUND, "PRIVACY_NOT_CONFIGURED"))
}

async fn notice(State(state): State<AppState>) -> Result<Json<PrivacyNotice>, ApiError> {
    configured(&state).map(Json)
}

async fn mine(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<PrivacyState>, ApiError> {
    let account = auth::bearer_account(&state, &headers).await?;
    let notice = configured(&state)?;
    let store = state.store.clone();
    let version = notice.version.clone();
    let (tower_upload, diagnostics) = run_db(move || {
        Ok((
            store.has_privacy_consent(account.id, "tower_upload", &version)?,
            store.has_privacy_consent(account.id, "diagnostics", &version)?,
        ))
    })
    .await?;
    Ok(Json(PrivacyState {
        notice,
        tower_upload,
        diagnostics,
    }))
}

fn valid_purpose(purpose: &str) -> Result<(), ApiError> {
    if matches!(purpose, "tower_upload" | "diagnostics") {
        Ok(())
    } else {
        Err(ApiError(StatusCode::NOT_FOUND, "NOT_FOUND"))
    }
}

async fn grant(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(purpose): Path<String>,
    Json(request): Json<ConsentRequest>,
) -> Result<StatusCode, ApiError> {
    valid_purpose(&purpose)?;
    let account = auth::bearer_account(&state, &headers).await?;
    let notice = configured(&state)?;
    if request.notice_version != notice.version {
        return Err(ApiError(StatusCode::CONFLICT, "NOTICE_CHANGED"));
    }
    let _gate = state.write_gate.write().await;
    let store = state.store.clone();
    run_db(move || store.grant_privacy_consent(account.id, &purpose, &request.notice_version))
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn withdraw(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(purpose): Path<String>,
) -> Result<StatusCode, ApiError> {
    valid_purpose(&purpose)?;
    let account = auth::bearer_account(&state, &headers).await?;
    configured(&state)?;
    let _gate = state.write_gate.write().await;
    let store = state.store.clone();
    let policy = state.policy();
    let purpose_for_log = purpose.clone();
    let changes =
        run_db(move || store.withdraw_privacy_consent(account.id, &purpose, &policy)).await?;
    for change in changes {
        match change {
            OwnContributionChange::Updated(tower) => {
                let _ = state
                    .events
                    .send(crate::ServerEvent::TowerUpserted { tower });
            }
            OwnContributionChange::Removed(key) => {
                let _ = state.events.send(crate::ServerEvent::TowerDeleted { key });
            }
        }
    }
    tracing::info!(account_id=account.id, purpose=%purpose_for_log, "privacy withdrawal committed");
    Ok(StatusCode::NO_CONTENT)
}
