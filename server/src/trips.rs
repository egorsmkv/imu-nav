//! Owner-only playback archives, independent of diagnostic recordings.
use crate::api::{ApiError, AppState, run_db};
use crate::{auth, db::params, store::CellStore};
use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Redirect, Response},
    routing::get,
};
use rusqlite::OptionalExtension;
use serde::{Deserialize, Serialize};

/// Explicit storage limits; existing trips are never evicted to make room.
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(default, deny_unknown_fields)]
pub struct TripArchiveLimits {
    pub account_bytes: usize,
    pub account_trips: usize,
    pub upload_bytes: usize,
}
impl Default for TripArchiveLimits {
    fn default() -> Self {
        Self {
            account_bytes: 256 * 1024 * 1024,
            account_trips: 1000,
            upload_bytes: 16 * 1024 * 1024,
        }
    }
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Summary {
    start_ms: i64,
    end_ms: i64,
    mode: String,
    arrived: bool,
    distance_m: f64,
    duration_s: f64,
    moving_s: f64,
    blind_s: f64,
    blind_m: f64,
    max_uncertainty_m: f64,
    route_length_m: f64,
    reroutes: u32,
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Position {
    time_ms: i64,
    segment: u32,
    lat: f64,
    lon: f64,
    uncertainty_m: f64,
    source: String,
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Route {
    time_ms: i64,
    segment: u32,
    points: Vec<[f64; 2]>,
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Document {
    version: u32,
    incomplete: bool,
    summary: Summary,
    positions: Vec<Position>,
    routes: Vec<Route>,
}
fn coordinate(lat: f64, lon: f64) -> bool {
    lat.is_finite()
        && lon.is_finite()
        && (-90.0..=90.0).contains(&lat)
        && (-180.0..=180.0).contains(&lon)
}
impl Document {
    #[cfg_attr(feature = "profiling", hotpath::measure)]
    fn valid(&self) -> bool {
        let summary = &self.summary;
        self.version == 1
            && summary.start_ms >= 0
            && summary.end_ms >= summary.start_ms
            && summary.end_ms <= 253_402_300_799_999
            && matches!(summary.mode.as_str(), "CAR" | "FOOT")
            && [
                summary.distance_m,
                summary.duration_s,
                summary.moving_s,
                summary.blind_s,
                summary.blind_m,
                summary.max_uncertainty_m,
                summary.route_length_m,
            ]
            .iter()
            .all(|v| v.is_finite() && *v >= 0.0)
            && !self.positions.is_empty()
            && self.positions.len() <= 200_000
            && self.routes.len() <= 1000
            && self.routes.iter().map(|r| r.points.len()).sum::<usize>() <= 200_000
            && self.positions.iter().all(|p| {
                p.time_ms >= 0
                    && p.time_ms <= 31_536_000_000
                    && coordinate(p.lat, p.lon)
                    && p.uncertainty_m.is_finite()
                    && p.uncertainty_m >= 0.0
                    && !p.source.is_empty()
                    && p.source.len() <= 32
                    && p.source
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || b == b'_')
            })
            && self
                .positions
                .windows(2)
                .all(|p| p[1].time_ms >= p[0].time_ms && p[1].segment >= p[0].segment)
            && self.routes.iter().all(|r| {
                r.time_ms >= 0
                    && r.time_ms <= 31_536_000_000
                    && r.points.len() >= 2
                    && r.points.iter().all(|p| coordinate(p[1], p[0]))
            })
            && self
                .routes
                .windows(2)
                .all(|r| r[1].time_ms >= r[0].time_ms && r[1].segment >= r[0].segment)
    }
}

pub(crate) fn router(limits: &TripArchiveLimits) -> Router<AppState> {
    Router::new()
        .route("/v1/trips", get(list))
        .route("/v1/trips/{id}", get(read).put(upload).delete(delete))
        .route("/trips", get(page))
        .route("/trips/{id}", get(page))
        .route("/trip-map/{asset}", get(map_asset))
        .route(
            "/trip-player.css",
            get(|| async {
                (
                    [("content-type", "text/css")],
                    include_str!("../static/trip-player.css"),
                )
            }),
        )
        .route(
            "/trip-player.js",
            get(|| async {
                (
                    [("content-type", "text/javascript")],
                    include_str!("../static/trip-player.js"),
                )
            }),
        )
        .layer(DefaultBodyLimit::max(limits.upload_bytes))
        .layer(axum::middleware::map_response(
            |mut response: Response| async move {
                response
                    .headers_mut()
                    .insert("cache-control", "no-store".parse().unwrap());
                response
            },
        ))
}
/// Browser sessions must be genuine owner sessions, never administrator impersonation.
pub(crate) async fn owner(
    state: &AppState,
    headers: &HeaderMap,
    mutation: bool,
) -> Result<i64, ApiError> {
    if headers.contains_key("authorization") {
        return Ok(auth::bearer_account(state, headers).await?.id);
    }
    let token = crate::web::cookie_token(headers, "imu_nav_session")
        .ok_or(ApiError(StatusCode::UNAUTHORIZED, "UNAUTHORIZED"))?;
    if mutation
        && headers.get("x-csrf-token").and_then(|v| v.to_str().ok())
            != Some(crate::web::csrf_token(&token).as_str())
    {
        return Err(ApiError(StatusCode::FORBIDDEN, "CSRF"));
    }
    let store = state.store.clone();
    run_db(move || Ok(auth::account_for_token(&store, &token, "web")?.map(|(a, _)| a.id)))
        .await?
        .ok_or(ApiError(StatusCode::UNAUTHORIZED, "UNAUTHORIZED"))
}
fn valid_id(id: &str) -> Result<(), ApiError> {
    if id.is_empty()
        || id.len() > 128
        || !id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_-".contains(&b))
    {
        Err(ApiError(StatusCode::BAD_REQUEST, "INVALID_TRIP_ID"))
    } else {
        Ok(())
    }
}
impl CellStore {
    #[cfg_attr(feature = "profiling", hotpath::measure)]
    fn trip(&self, account: i64, id: &str) -> anyhow::Result<Option<String>> {
        Ok(self
            .connection()?
            .query_row(
                "SELECT document FROM trip_archive WHERE account_id=?1 AND id=?2",
                params![account, id],
                |row| row.get(0),
            )
            .optional()?)
    }
    #[cfg_attr(feature = "profiling", hotpath::measure)]
    fn save_trip(
        &self,
        account: i64,
        id: &str,
        document: &str,
        summary: &str,
        start: i64,
        permission: (&TripArchiveLimits, &str),
    ) -> anyhow::Result<&'static str> {
        let (limits, version) = permission;
        let mut connection = self.connection()?;
        let transaction =
            connection.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        // Lock the owner row as well on PostgreSQL; SQLite's immediate transaction serializes writers.
        transaction.execute("UPDATE users SET id=id WHERE id=?1", [account])?;
        let consent: Option<(String,bool)> = transaction.query_row(
            "SELECT notice_version,granted FROM privacy_consents WHERE account_id=?1 AND purpose='trip_archive' ORDER BY id DESC LIMIT 1",
            [account], |row| Ok((row.get(0)?,row.get(1)?)),
        ).optional()?;
        if !consent.is_some_and(|(accepted, granted)| granted && accepted == version) {
            return Ok("TRIP_CONSENT_REQUIRED");
        }

        let previous: Option<String> = transaction
            .query_row(
                "SELECT document FROM trip_archive WHERE account_id=?1 AND id=?2",
                params![account, id],
                |row| row.get(0),
            )
            .optional()?;
        if let Some(previous) = previous {
            return Ok(if previous == document {
                "OK"
            } else {
                "TRIP_CONFLICT"
            });
        }
        let (bytes, count): (i64, i64) = transaction.query_row(
            "SELECT CAST(COALESCE(SUM(bytes),0) AS BIGINT),COUNT(*) FROM trip_archive WHERE account_id=?1",
            [account],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;
        if usize::try_from(bytes)?.saturating_add(document.len()) > limits.account_bytes
            || usize::try_from(count)? >= limits.account_trips
        {
            return Ok("TRIP_QUOTA_EXCEEDED");
        }
        transaction.execute("INSERT INTO trip_archive(account_id,id,start_ms,bytes,summary,document) VALUES (?1,?2,?3,?4,?5,?6)",params![account,id,start,i64::try_from(document.len())?,summary,document])?;
        transaction.commit()?;
        Ok("OK")
    }
}
#[cfg_attr(feature = "profiling", hotpath::measure)]
async fn upload(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Json(document): Json<Document>,
) -> Result<StatusCode, ApiError> {
    let account = owner(&state, &headers, true).await?;
    valid_id(&id)?;
    let upload_bytes = state.config.trip_archive.upload_bytes;
    let prepared = run_db(move || Ok(prepare_trip(&document, upload_bytes))).await??;
    let _gate = crate::api::write_gate_wait(&state).await;
    let store = state.store.clone();
    let version = crate::account_sync::notice_version(&state);
    let limits = state.config.trip_archive.clone();
    let result = run_db(move || {
        store.save_trip(
            account,
            &id,
            &prepared.encoded,
            &prepared.summary,
            prepared.start_ms,
            (&limits, &version),
        )
    })
    .await?;
    match result {
        "OK" => Ok(StatusCode::NO_CONTENT),
        "TRIP_QUOTA_EXCEEDED" => Err(ApiError(StatusCode::PAYLOAD_TOO_LARGE, result)),
        _ => Err(ApiError(StatusCode::CONFLICT, result)),
    }
}
/// Own the encoded upload so no document copy crosses the database boundary.
struct PreparedTrip {
    encoded: String,
    summary: String,
    start_ms: i64,
}

/// Large coordinate arrays must not monopolize a Tokio executor thread.
#[cfg_attr(feature = "profiling", hotpath::measure)]
fn prepare_trip(document: &Document, upload_bytes: usize) -> Result<PreparedTrip, ApiError> {
    if !document.valid() {
        return Err(ApiError(StatusCode::BAD_REQUEST, "INVALID_TRIP"));
    }
    let encoded = serde_json::to_string(document).map_err(anyhow::Error::from)?;
    if encoded.len() > upload_bytes {
        return Err(ApiError(StatusCode::PAYLOAD_TOO_LARGE, "TRIP_TOO_LARGE"));
    }
    let summary = serde_json::to_string(
        &serde_json::json!({"summary":document.summary,"incomplete":document.incomplete}),
    )
    .map_err(anyhow::Error::from)?;
    Ok(PreparedTrip {
        encoded,
        summary,
        start_ms: document.summary.start_ms,
    })
}

#[derive(Default, Deserialize)]
struct Filter {
    offset: Option<usize>,
    from_ms: Option<i64>,
    to_ms: Option<i64>,
}
#[cfg_attr(feature = "profiling", hotpath::measure)]
async fn list(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(filter): Query<Filter>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let account = owner(&state, &headers, false).await?;
    let store = state.store.clone();
    let version = crate::account_sync::notice_version(&state);
    let notice = state.config.privacy.clone();
    let limits = state.config.trip_archive.clone();
    let _gate = crate::api::read_gate_wait(&state).await;
    run_db(move || {
        let enabled = store.has_privacy_consent(account,"trip_archive",&version)?;
        let connection = store.connection()?;
        let (bytes,count):(i64,i64) = connection.query_row("SELECT CAST(COALESCE(SUM(bytes),0) AS BIGINT),COUNT(*) FROM trip_archive WHERE account_id=?1",[account],|row|Ok((row.get(0)?,row.get(1)?)))?;
        let mut statement = connection.prepare("SELECT id,summary FROM trip_archive WHERE account_id=?1 AND start_ms>=?2 AND start_ms<=?3 ORDER BY start_ms DESC,id LIMIT 51 OFFSET ?4")?;
        let mut trips = statement.query_map(params![account,filter.from_ms.unwrap_or(0),filter.to_ms.unwrap_or(i64::MAX),i64::try_from(filter.offset.unwrap_or(0).min(1_000_000))?],|row| Ok((row.get::<_,String>(0)?,row.get::<_,String>(1)?)))?.map(|row| { let (id,summary) = row?; Ok(serde_json::json!({"id":id,"data":serde_json::from_str::<serde_json::Value>(&summary)?})) }).collect::<anyhow::Result<Vec<_>>>()?;
        let more = trips.len() > 50; trips.truncate(50);
        Ok(Json(serde_json::json!({"trips":trips,"more":more,"bytes":bytes,"count":count,"limits":limits,"enabled":enabled,"notice_version":version,"notice":notice})))
    }).await
}
#[cfg_attr(feature = "profiling", hotpath::measure)]
async fn read(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Result<Response, ApiError> {
    let account = owner(&state, &headers, false).await?;
    valid_id(&id)?;
    let _gate = crate::api::read_gate_wait(&state).await;
    let store = state.store.clone();
    let document = run_db(move || store.trip(account, &id))
        .await?
        .ok_or(ApiError(StatusCode::NOT_FOUND, "NOT_FOUND"))?;
    Ok((
        [
            ("content-type", "application/json"),
            ("content-disposition", "attachment; filename=trip.json"),
        ],
        document,
    )
        .into_response())
}
#[cfg_attr(feature = "profiling", hotpath::measure)]
async fn delete(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    let account = owner(&state, &headers, true).await?;
    valid_id(&id)?;
    let _gate = crate::api::write_gate_wait(&state).await;
    let store = state.store.clone();
    run_db(move || {
        store.connection()?.execute(
            "DELETE FROM trip_archive WHERE account_id=?1 AND id=?2",
            params![account, id],
        )?;
        Ok(())
    })
    .await?;
    Ok(StatusCode::NO_CONTENT)
}
async fn page(State(state): State<AppState>, headers: HeaderMap) -> Result<Response, ApiError> {
    if let Err(error) = owner(&state, &headers, false).await {
        if error.0 == StatusCode::UNAUTHORIZED {
            return Ok(Redirect::to("/login").into_response());
        }
        return Err(error);
    }
    let token = crate::web::cookie_token(&headers, "imu_nav_session").unwrap_or_default();
    let html = include_str!("../templates/trips.html")
        .replace("CSRF_TOKEN", &crate::web::csrf_token(&token));
    Ok(([("content-type","text/html; charset=utf-8"),("referrer-policy","no-referrer"),("content-security-policy","default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self' https://tiles.openfreemap.org; img-src 'self' data: blob:; worker-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")],html).into_response())
}

async fn map_asset(Path(asset): Path<String>) -> Result<Response, ApiError> {
    let (kind, body): (&str, &'static [u8]) = match asset.as_str() {
        "maplibre-gl-csp.js" => (
            "text/javascript",
            include_bytes!("../static/maplibre/maplibre-gl-csp.js"),
        ),
        "maplibre-gl-csp-worker.js" => (
            "text/javascript",
            include_bytes!("../static/maplibre/maplibre-gl-csp-worker.js"),
        ),
        "license.txt" => (
            "text/plain; charset=utf-8",
            include_bytes!("../static/maplibre/license.txt"),
        ),
        "maplibre-gl.css" => (
            "text/css",
            include_bytes!("../static/maplibre/maplibre-gl.css"),
        ),
        _ => return Err(ApiError(StatusCode::NOT_FOUND, "NOT_FOUND")),
    };
    Ok(([("content-type", kind)], body).into_response())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{Value, json};
    use std::net::{Ipv4Addr, SocketAddr};

    struct Stop(tokio::task::JoinHandle<()>);
    impl Drop for Stop {
        fn drop(&mut self) {
            self.0.abort();
        }
    }

    fn example() -> Value {
        json!({"version":1,"incomplete":false,"summary":{"start_ms":1000,"end_ms":5000,"mode":"CAR","arrived":true,"distance_m":300.0,"duration_s":4.0,"moving_s":4.0,"blind_s":2.0,"blind_m":100.0,"max_uncertainty_m":10.0,"route_length_m":350.0,"reroutes":1},"positions":[{"time_ms":0,"segment":0,"lat":50.0,"lon":30.0,"uncertainty_m":5.0,"source":"GPS"},{"time_ms":1000,"segment":0,"lat":50.001,"lon":30.001,"uncertainty_m":10.0,"source":"DR"}],"routes":[{"time_ms":0,"segment":0,"points":[[30.0,50.0],[30.001,50.001]]}]})
    }

    #[test]
    fn prepared_upload_preserves_validation_and_size_errors() {
        let document = || serde_json::from_value::<Document>(example()).unwrap();
        let prepared = prepare_trip(&document(), usize::MAX).unwrap();
        assert_eq!(
            serde_json::from_str::<Value>(&prepared.encoded).unwrap(),
            example()
        );
        assert!(prepare_trip(&document(), prepared.encoded.len()).is_ok());
        let error = prepare_trip(&document(), prepared.encoded.len() - 1)
            .err()
            .unwrap();
        assert_eq!(error.0, StatusCode::PAYLOAD_TOO_LARGE);
        assert_eq!(error.1, "TRIP_TOO_LARGE");
        let mut invalid = document();
        invalid.positions.clear();
        let error = prepare_trip(&invalid, 0).err().unwrap();
        assert_eq!(error.0, StatusCode::BAD_REQUEST);
        assert_eq!(error.1, "INVALID_TRIP");
    }

    #[test]
    fn validation_rejects_untrusted_shapes_and_timelines() {
        assert!(
            serde_json::from_value::<Document>(example())
                .unwrap()
                .valid()
        );
        for (pointer, value) in [
            ("/version", json!(2)),
            ("/summary/start_ms", json!(-1)),
            ("/summary/end_ms", json!(0)),
            ("/summary/mode", json!("BOAT")),
            ("/summary/distance_m", json!(-1)),
            ("/positions", json!([])),
            ("/positions/0/lat", json!(91)),
            ("/positions/0/lon", json!(181)),
            ("/positions/0/uncertainty_m", json!(-1)),
            ("/positions/0/source", json!("<script>")),
            ("/positions/1/time_ms", json!(-1)),
            ("/positions/0/segment", json!(1)),
            ("/routes/0/points", json!([[30, 50]])),
            ("/routes/0/points/0", json!([181, 50])),
            ("/routes/0/time_ms", json!(-1)),
        ] {
            let mut value_to_test = example();
            *value_to_test.pointer_mut(pointer).unwrap() = value;
            assert!(
                !serde_json::from_value::<Document>(value_to_test)
                    .unwrap()
                    .valid(),
                "{pointer}"
            );
        }
        let mut unknown = example();
        unknown["raw_gps"] = json!([]);
        assert!(serde_json::from_value::<Document>(unknown).is_err());
        for id in ["", "../x", "bad/id", "bad?", &"a".repeat(129)] {
            assert!(valid_id(id).is_err());
        }
        assert!(valid_id("trip_1-2").is_ok());
    }

    /// The same HTTP contract runs on both databases, with synthetic accounts and location samples.
    // A sequential HTTP contract keeps stateful consent, quota and erasure assertions together.
    #[allow(clippy::too_many_lines)]
    async fn exercise(store: CellStore) -> anyhow::Result<()> {
        tokio::task::block_in_place(|| -> anyhow::Result<()> {
            store.connection()?.execute("INSERT INTO users(id,email,password_hash,email_verified) VALUES (100,'trip-owner@example.org','unused',1),(101,'other@example.org','unused',1)",params![])?;
            for (raw, account, kind) in [
                ("trip-access", 100, "access"),
                ("other-access", 101, "access"),
                ("wwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwww", 100, "web"),
                (
                    "iiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiii",
                    100,
                    "web_impersonated",
                ),
            ] {
                store.connection()?.execute("INSERT INTO auth_tokens(token_hash,user_id,session_id,kind,expires_s) VALUES (?1,?2,?3,?4,?5)",params![auth::digest(raw),account,raw,kind,auth::now_s()+3600])?;
            }
            Ok(())
        })?;
        let state = tokio::task::block_in_place(|| {
            AppState::new(
                store.clone(),
                crate::ServerConfig {
                    trip_archive: TripArchiveLimits {
                        account_trips: 2,
                        ..Default::default()
                    },
                    mail: None,
                    policy: crate::Policy::default(),
                    trust_proxy: false,
                    secure_cookies: false,
                    privacy: None,
                },
            )
        })?;
        let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
        let base = format!("http://{}", listener.local_addr()?);
        let task = tokio::spawn(async move {
            axum::serve(
                listener,
                crate::router(state).into_make_service_with_connect_info::<SocketAddr>(),
            )
            .await
            .unwrap();
        });
        let _stop = Stop(task);
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .build()?;
        let anonymous = client.get(format!("{base}/trips")).send().await?;
        assert_eq!(anonymous.status(), StatusCode::SEE_OTHER);
        assert_eq!(anonymous.headers()["location"], "/login");
        let url = format!("{base}/v1/trips/one");
        let put = |url: String, data: Value| {
            client
                .put(url)
                .bearer_auth("trip-access")
                .json(&data)
                .send()
        };
        assert_eq!(
            client.get(&url).send().await?.status(),
            StatusCode::UNAUTHORIZED
        );
        assert_eq!(
            put(url.clone(), example()).await?.status(),
            StatusCode::CONFLICT
        );
        let meta: Value = client
            .get(format!("{base}/v1/trips"))
            .bearer_auth("trip-access")
            .send()
            .await?
            .json()
            .await?;
        assert_eq!(meta["enabled"], false);
        assert_eq!(meta["notice_version"], "local");
        assert_eq!(
            client
                .put(format!("{base}/v1/privacy/consents/trip_archive"))
                .bearer_auth("trip-access")
                .json(&json!({"notice_version":"wrong"}))
                .send()
                .await?
                .status(),
            StatusCode::CONFLICT
        );
        assert_eq!(
            client
                .put(format!("{base}/v1/privacy/consents/trip_archive"))
                .bearer_auth("trip-access")
                .json(&json!({"notice_version":"local"}))
                .send()
                .await?
                .status(),
            StatusCode::NO_CONTENT
        );
        assert_eq!(
            put(url.clone(), example()).await?.status(),
            StatusCode::NO_CONTENT
        );
        assert_eq!(
            put(url.clone(), example()).await?.status(),
            StatusCode::NO_CONTENT
        );
        let response = client.get(&url).bearer_auth("trip-access").send().await?;
        assert_eq!(response.headers()["cache-control"], "no-store");
        assert_eq!(response.json::<Value>().await?, example());
        assert_eq!(
            client
                .get(&url)
                .bearer_auth("other-access")
                .send()
                .await?
                .status(),
            StatusCode::NOT_FOUND
        );
        assert_eq!(
            client
                .get(&url)
                .header(
                    "cookie",
                    "imu_nav_session=iiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiii"
                )
                .send()
                .await?
                .status(),
            StatusCode::UNAUTHORIZED
        );
        assert_eq!(
            client
                .get(format!("{base}/trips/one"))
                .header(
                    "cookie",
                    "imu_nav_session=wwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwww"
                )
                .send()
                .await?
                .status(),
            StatusCode::OK
        );
        assert_eq!(
            client
                .delete(&url)
                .header(
                    "cookie",
                    "imu_nav_session=wwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwww"
                )
                .send()
                .await?
                .status(),
            StatusCode::FORBIDDEN
        );
        let mut changed = example();
        changed["incomplete"] = json!(true);
        assert_eq!(
            put(url.clone(), changed).await?.status(),
            StatusCode::CONFLICT
        );
        let mut invalid = example();
        invalid["positions"] = json!([]);
        assert_eq!(
            put(url.clone(), invalid).await?.status(),
            StatusCode::BAD_REQUEST
        );
        let (first, second) = tokio::join!(
            put(format!("{base}/v1/trips/two"), example()),
            put(format!("{base}/v1/trips/three"), example())
        );
        let mut statuses = [first?.status().as_u16(), second?.status().as_u16()];
        statuses.sort_unstable();
        assert_eq!(statuses, [204, 413]);
        let meta: Value = client
            .get(format!("{base}/v1/trips?from_ms=1001"))
            .bearer_auth("trip-access")
            .send()
            .await?
            .json()
            .await?;
        assert_eq!(meta["count"], 2);
        assert!(meta["bytes"].as_i64().unwrap() > 0);
        assert_eq!(meta["trips"], json!([]));
        for path in [
            "/trip-player.js",
            "/trip-player.css",
            "/trip-map/maplibre-gl-csp.js",
            "/trip-map/maplibre-gl-csp-worker.js",
            "/trip-map/maplibre-gl.css",
            "/trip-map/license.txt",
        ] {
            assert_eq!(
                client.get(format!("{base}{path}")).send().await?.status(),
                StatusCode::OK
            );
        }
        assert_eq!(
            client
                .get(format!("{base}/trip-map/missing"))
                .send()
                .await?
                .status(),
            StatusCode::NOT_FOUND
        );
        let export = tempfile::NamedTempFile::new()?;
        tokio::task::block_in_place(|| store.export_account_to_path(100, export.path()))?;
        let mut exported = String::new();
        std::io::Read::read_to_string(
            &mut flate2::read::GzDecoder::new(std::fs::File::open(export.path())?),
            &mut exported,
        )?;
        assert!(exported.contains("trip_archive"));
        assert_eq!(
            client
                .delete(&url)
                .header(
                    "cookie",
                    "imu_nav_session=wwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwww"
                )
                .header(
                    "x-csrf-token",
                    crate::web::csrf_token("wwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwww")
                )
                .send()
                .await?
                .status(),
            StatusCode::NO_CONTENT
        );
        assert!(tokio::task::block_in_place(|| store.trip(100, "one"))?.is_none());
        assert_eq!(
            client
                .delete(format!("{base}/v1/privacy/consents/trip_archive"))
                .bearer_auth("trip-access")
                .send()
                .await?
                .status(),
            StatusCode::NO_CONTENT
        );
        assert!(tokio::task::block_in_place(|| store.trip(100, "two"))?.is_none());
        assert!(tokio::task::block_in_place(|| store.trip(100, "three"))?.is_none());
        assert_eq!(
            put(url.clone(), example()).await?.status(),
            StatusCode::CONFLICT
        );
        tokio::task::block_in_place(|| store.grant_privacy_consent(100, "trip_archive", "local"))?;
        assert_eq!(
            put(url.clone(), example()).await?.status(),
            StatusCode::NO_CONTENT
        );
        tokio::task::block_in_place(|| {
            store.replay_withdrawal(100, "trip_archive", &crate::Policy::default())
        })?;
        assert!(tokio::task::block_in_place(|| store.trip(100, "one"))?.is_none());
        tokio::task::block_in_place(|| store.grant_privacy_consent(100, "trip_archive", "local"))?;
        assert_eq!(
            put(url.clone(), example()).await?.status(),
            StatusCode::NO_CONTENT
        );
        tokio::task::block_in_place(|| store.close_own_account(100, &crate::Policy::default()))?;
        assert!(tokio::task::block_in_place(|| store.trip(100, "one"))?.is_none());
        Ok(())
    }
    #[tokio::test(flavor = "multi_thread")]
    async fn sqlite_archive_contract() -> anyhow::Result<()> {
        let file = tempfile::NamedTempFile::new()?;
        exercise(CellStore::open(file.path())?).await
    }
    #[test]
    fn postgres_archive_contract() -> anyhow::Result<()> {
        let Ok(base) = std::env::var("TEST_POSTGRES_URL") else {
            return Ok(());
        };
        let schema = format!("trip_archive_test_{}", rand::random::<u64>());
        let tls = postgres_native_tls::MakeTlsConnector::new(
            native_tls::TlsConnector::builder().build()?,
        );
        let mut client = postgres::Client::connect(&base, tls)?;
        client.batch_execute(&format!("CREATE SCHEMA {schema}"))?;
        let separator = if base.contains('?') { '&' } else { '?' };
        let store = CellStore::open_postgres(&format!(
            "{base}{separator}options=-csearch_path%3D{schema}"
        ))?;
        // Keep a store owner outside Tokio so synchronous PostgreSQL connections also close there.
        let runtime = tokio::runtime::Runtime::new()?;
        let result = runtime.block_on(exercise(store.clone()));
        drop(runtime);
        drop(store);
        client.batch_execute(&format!("DROP SCHEMA {schema} CASCADE"))?;
        result
    }
}
