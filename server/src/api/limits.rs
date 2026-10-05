//! Upload rate limits and per-IP device limits.

use super::{ApiError, AppState};
use axum::http::StatusCode;
use std::collections::{HashMap, VecDeque};
use std::time::{Duration, Instant};

pub(super) const HOUR: Duration = Duration::from_secs(60 * 60);
pub(super) const DAY: Duration = Duration::from_secs(24 * 60 * 60);

#[derive(Default)]
pub(super) struct Limits {
    pub(super) by_device: HashMap<String, VecDeque<Instant>>,
    pub(super) by_ip: HashMap<String, VecDeque<Instant>>,
    pub(super) devices_by_ip: HashMap<String, HashMap<String, Instant>>,
    pub(super) last_cleanup: Option<Instant>,
}

impl Limits {
    pub(super) fn cleanup_if_due(&mut self, now: Instant) {
        let cleanup_due = self
            .last_cleanup
            .is_none_or(|last| now.saturating_duration_since(last) >= HOUR);
        if !cleanup_due {
            return;
        }
        self.by_device.retain(|_, queue| {
            prune(queue, now, HOUR);
            !queue.is_empty()
        });
        self.by_ip.retain(|_, queue| {
            prune(queue, now, HOUR);
            !queue.is_empty()
        });
        self.devices_by_ip.retain(|_, devices| {
            devices.retain(|_, seen| now.saturating_duration_since(*seen) <= DAY);
            !devices.is_empty()
        });
        self.last_cleanup = Some(now);
    }
}

pub(super) fn enforce_limits(state: &AppState, ip: &str, device: &str) -> Result<(), ApiError> {
    enforce_limits_at(state, ip, device, Instant::now())
}

// Explicit time keeps expiry boundaries deterministic in tests.
pub(super) fn enforce_limits_at(
    state: &AppState,
    ip: &str,
    device: &str,
    now: Instant,
) -> Result<(), ApiError> {
    let mut limits = state
        .limits
        .lock()
        .map_err(|_| ApiError(StatusCode::INTERNAL_SERVER_ERROR, "SERVER_ERROR"))?;
    limits.cleanup_if_due(now);
    let policy = state.policy();
    let devices = limits.devices_by_ip.entry(ip.to_owned()).or_default();
    devices.retain(|_, seen| now.saturating_duration_since(*seen) <= DAY);
    if !devices.contains_key(device) && devices.len() >= policy.max_devices_per_ip_per_day {
        return Err(ApiError(StatusCode::TOO_MANY_REQUESTS, "TOO_MANY_DEVICES"));
    }
    devices.insert(device.to_owned(), now);
    if !allow(
        &mut limits.by_ip,
        ip,
        now,
        policy.max_uploads_per_hour_per_ip,
    ) || !allow(
        &mut limits.by_device,
        device,
        now,
        policy.max_uploads_per_hour_per_device,
    ) {
        return Err(ApiError(StatusCode::TOO_MANY_REQUESTS, "RATE_LIMITED"));
    }
    Ok(())
}

fn allow(
    hits: &mut HashMap<String, VecDeque<Instant>>,
    key: &str,
    now: Instant,
    maximum: usize,
) -> bool {
    let queue = hits.entry(key.to_owned()).or_default();
    prune(queue, now, HOUR);
    if queue.len() >= maximum {
        return false;
    }
    queue.push_back(now);
    true
}

fn prune(queue: &mut VecDeque<Instant>, now: Instant, window: Duration) {
    while queue
        .front()
        .is_some_and(|time| now.saturating_duration_since(*time) > window)
    {
        queue.pop_front();
    }
}
