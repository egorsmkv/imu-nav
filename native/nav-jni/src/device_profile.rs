//! Bounded, opt-in timing of selected Android JNI operations.

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use std::fmt::Write;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::Instant;

pub const ESTIMATOR_TICK: usize = 0;
pub const ROUTE_CREATE: usize = 1;
pub const ROUTE_PROJECT: usize = 2;
pub const TRUST_EVALUATE: usize = 3;
pub const NETWORK_GATE: usize = 4;
pub const NETWORK_ESTIMATE: usize = 5;
pub const SPEED_FUSE: usize = 6;
const NAMES: [&str; 7] = [
    "estimator_tick",
    "route_create",
    "route_project",
    "trust_evaluate",
    "network_gate",
    "network_estimate",
    "speed_fuse",
];

struct Counter {
    calls: AtomicU64,
    total_ns: AtomicU64,
    max_ns: AtomicU64,
}

impl Counter {
    fn new() -> Self {
        Self {
            calls: AtomicU64::new(0),
            total_ns: AtomicU64::new(0),
            max_ns: AtomicU64::new(0),
        }
    }

    fn reset(&self) {
        self.calls.store(0, Ordering::Relaxed);
        self.total_ns.store(0, Ordering::Relaxed);
        self.max_ns.store(0, Ordering::Relaxed);
    }
}

static ENABLED: AtomicBool = AtomicBool::new(false);
static COUNTERS: OnceLock<[Counter; NAMES.len()]> = OnceLock::new();

fn counters() -> &'static [Counter; NAMES.len()] {
    COUNTERS.get_or_init(|| std::array::from_fn(|_| Counter::new()))
}

pub struct Guard {
    operation: usize,
    started: Instant,
}

impl Drop for Guard {
    fn drop(&mut self) {
        let elapsed = u64::try_from(self.started.elapsed().as_nanos()).unwrap_or(u64::MAX);
        let counter = &counters()[self.operation];
        counter.calls.fetch_add(1, Ordering::Relaxed);
        counter.total_ns.fetch_add(elapsed, Ordering::Relaxed);
        counter.max_ns.fetch_max(elapsed, Ordering::Relaxed);
    }
}

/// The inactive path is one atomic read; no heap allocation or file write happens in a JNI call.
pub fn measure(operation: usize) -> Option<Guard> {
    ENABLED.load(Ordering::Relaxed).then(|| Guard {
        operation,
        started: Instant::now(),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeProfiler_nativeStart(
    _env: JNIEnv,
    _class: JClass,
) {
    ENABLED.store(false, Ordering::SeqCst);
    for counter in counters() {
        counter.reset();
    }
    ENABLED.store(true, Ordering::SeqCst);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeProfiler_nativeStop(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    ENABLED.store(false, Ordering::SeqCst);
    let mut output = String::from("{\"unit\":\"ns\",\"operations\":{");
    for (index, name) in NAMES.iter().enumerate() {
        if index != 0 {
            output.push(',');
        }
        let counter = &counters()[index];
        let _ = write!(
            output,
            "\"{name}\":{{\"calls\":{},\"total\":{},\"max\":{}}}",
            counter.calls.load(Ordering::Relaxed),
            counter.total_ns.load(Ordering::Relaxed),
            counter.max_ns.load(Ordering::Relaxed),
        );
    }
    output.push_str("}}");
    match env.new_string(output) {
        Ok(value) => JString::into_raw(value),
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inactive_calls_are_ignored_and_active_calls_are_counted() {
        ENABLED.store(false, Ordering::SeqCst);
        counters()[ESTIMATOR_TICK].reset();
        assert!(measure(ESTIMATOR_TICK).is_none());
        ENABLED.store(true, Ordering::SeqCst);
        drop(measure(ESTIMATOR_TICK));
        ENABLED.store(false, Ordering::SeqCst);
        assert_eq!(counters()[ESTIMATOR_TICK].calls.load(Ordering::Relaxed), 1);
    }
}
