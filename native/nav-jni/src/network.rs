//! JNI exports for network.

use super::{
    AssertUnwindSafe, ERROR_INTERNAL, ERROR_INVALID_HANDLE, ERROR_INVALID_INPUT, JClass,
    JDoubleArray, JNIEnv, NEXT_HANDLE, NetworkGateResult, NetworkSample, NetworkTracker, OK,
    Ordering, catch_unwind, elapsed_milliseconds_as_f64, guarded_code, jdouble, jdoubleArray, jint,
    jlong, network_trackers, new_double_array, ptr, usize_as_f64, with_network_tracker,
};

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        if handle <= 0 {
            return None;
        }
        network_trackers()
            .lock()
            .ok()?
            .insert(handle, NetworkTracker::default());
        Some(handle)
    }))
    .ok()
    .flatten()
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(|| {
        let Ok(mut registry) = network_trackers().lock() else {
            return ERROR_INTERNAL;
        };
        if registry.remove(&handle).is_some() {
            OK
        } else {
            ERROR_INVALID_HANDLE
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativeReset(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(
        || match with_network_tracker(handle, NetworkTracker::reset) {
            Ok(()) => OK,
            Err(code) => code,
        },
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativeClearSamples(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(
        || match with_network_tracker(handle, NetworkTracker::clear_samples) {
            Ok(()) => OK,
            Err(code) => code,
        },
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativeGate(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    elapsed_ms: jlong,
    position_m: jdouble,
    accuracy_m: jdouble,
) -> jint {
    guarded_code(|| {
        match with_network_tracker(handle, |tracker| {
            tracker.gate(elapsed_ms, position_m, accuracy_m)
        }) {
            Ok(NetworkGateResult::Accepted) => 0,
            Ok(NetworkGateResult::Reanchored) => 1,
            Ok(NetworkGateResult::Rejected) => 2,
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativeRecord(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    elapsed_ms: jlong,
    values: JDoubleArray,
) -> jint {
    guarded_code(|| {
        if env.get_array_length(&values).ok() != Some(5) {
            return ERROR_INVALID_INPUT;
        }
        let mut data = [0.0; 5];
        if env.get_double_array_region(&values, 0, &mut data).is_err() {
            return ERROR_INVALID_INPUT;
        }
        match with_network_tracker(handle, |tracker| {
            tracker.record(
                NetworkSample {
                    elapsed_ms,
                    position_m: data[0],
                    accuracy_m: data[1],
                    offset_m: data[2],
                },
                data[3],
                data[4],
            );
        }) {
            Ok(()) => OK,
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativePruneHistory(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    now_ms: jlong,
) -> jint {
    guarded_code(
        || match with_network_tracker(handle, |tracker| tracker.prune_history(now_ms)) {
            Ok(()) => OK,
            Err(code) => code,
        },
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativeLastTwoConsistent(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(
        || match with_network_tracker(handle, |tracker| tracker.last_two_consistent()) {
            Ok(false) => 0,
            Ok(true) => 1,
            Err(code) => code,
        },
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativeEstimate(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    now_ms: jlong,
    strict: jint,
) -> jdoubleArray {
    catch_unwind(AssertUnwindSafe(|| {
        let estimate = with_network_tracker(handle, |tracker| {
            if strict == 0 {
                tracker.speed_estimate(now_ms)
            } else {
                tracker.strict_speed_estimate(now_ms)
            }
        })
        .ok()
        .flatten()?;
        let values = [
            estimate.speed_mps,
            estimate.sigma_mps,
            usize_as_f64(estimate.samples)?,
            estimate.span_s,
        ];
        new_double_array(&env, &values)
    }))
    .ok()
    .flatten()
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativeSamples(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    history: jint,
) -> jdoubleArray {
    catch_unwind(AssertUnwindSafe(|| {
        let samples = with_network_tracker(handle, |tracker| {
            if history == 0 {
                tracker.recent().to_vec()
            } else {
                tracker.history().to_vec()
            }
        })
        .ok()?;
        let values: Vec<f64> = samples
            .iter()
            .flat_map(|sample| {
                [
                    elapsed_milliseconds_as_f64(sample.elapsed_ms),
                    sample.position_m,
                    sample.accuracy_m,
                    sample.offset_m,
                ]
            })
            .collect();
        new_double_array(&env, &values)
    }))
    .ok()
    .flatten()
    .unwrap_or(ptr::null_mut())
}
