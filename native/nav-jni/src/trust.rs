//! JNI exports for trust.

use super::{
    AssertUnwindSafe, ERROR_INTERNAL, ERROR_INVALID_HANDLE, JClass, JDoubleArray, JLongArray,
    JNIEnv, JamDetector, LocationFix, NEXT_HANDLE, NativeTrustState, OK, Ordering, ReceiverHealth,
    TrustClassifier, TrustConfig, TrustInput, catch_unwind, guarded_code, jdouble, jint, jintArray,
    jlong, new_int_array, optional, ptr, reason_code, trust_classifiers, trust_level_code,
};

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeTrustEvaluator_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        if handle <= 0 {
            return None;
        }
        trust_classifiers().lock().ok()?.insert(
            handle,
            NativeTrustState {
                classifier: TrustClassifier::new(TrustConfig::default()),
                jam_detector: JamDetector::default(),
            },
        );
        Some(handle)
    }))
    .ok()
    .flatten()
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeTrustEvaluator_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(|| {
        let Ok(mut registry) = trust_classifiers().lock() else {
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
pub extern "system" fn Java_org_imunav_app_nativecore_NativeTrustEvaluator_nativeReset(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(|| {
        let Ok(mut registry) = trust_classifiers().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(state) = registry.get_mut(&handle) else {
            return ERROR_INVALID_HANDLE;
        };
        state.classifier.reset();
        state.jam_detector.reset();
        OK
    })
}

/// Returns bit 0 as the current jam state and bit 1 when the state changed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeTrustEvaluator_nativeUpdateAgc(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    agc_db: jdouble,
    now_ms: jlong,
) -> jint {
    guarded_code(|| {
        let Ok(mut registry) = trust_classifiers().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(state) = registry.get_mut(&handle) else {
            return ERROR_INVALID_HANDLE;
        };
        let changed = state.jam_detector.update(optional(agc_db), now_ms);
        jint::from(state.jam_detector.jammed()) | (jint::from(changed) << 1)
    })
}

/// Evaluate arrays documented by `NativeTrustEvaluator`. The first returned integer is the trust
/// level (0 GOOD, 1 SUSPECT, 2 BAD); remaining integers are stable reason codes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeTrustEvaluator_nativeEvaluate(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    doubles: JDoubleArray,
    longs: JLongArray,
) -> jintArray {
    let _profile = crate::device_profile::measure(crate::device_profile::TRUST_EVALUATE);
    catch_unwind(AssertUnwindSafe(|| {
        if env.get_array_length(&doubles).ok()? < 14 || env.get_array_length(&longs).ok()? < 11 {
            return None;
        }
        let mut d = [0.0; 14];
        let mut l = [0_i64; 11];
        env.get_double_array_region(&doubles, 0, &mut d).ok()?;
        env.get_long_array_region(&longs, 0, &mut l).ok()?;
        let fix = LocationFix {
            wall_time_ms: l[0],
            elapsed_ms: l[1],
            latitude_deg: d[0],
            longitude_deg: d[1],
            altitude_m: optional(d[2]),
            speed_mps: optional(d[3]),
            bearing_deg: optional(d[4]),
            horizontal_accuracy_m: optional(d[5]),
            vertical_accuracy_m: optional(d[6]),
            is_mock: l[2] != 0,
        };
        let network_fix = if l[4] != 0 {
            Some(LocationFix {
                wall_time_ms: fix.wall_time_ms,
                elapsed_ms: l[5],
                latitude_deg: d[7],
                longitude_deg: d[8],
                altitude_m: None,
                speed_mps: None,
                bearing_deg: None,
                horizontal_accuracy_m: optional(d[9]),
                vertical_accuracy_m: None,
                is_mock: false,
            })
        } else {
            None
        };
        let mut input = TrustInput {
            fix,
            network_fix,
            receiver: ReceiverHealth {
                satellites_visible: u16::try_from(l[6]).ok()?,
                satellites_used: u16::try_from(l[7]).ok()?,
                mean_cn0_used: optional(d[10]),
                cn0_spread_used: optional(d[11]),
                agc_db: optional(d[12]),
                dual_frequency_used: u16::try_from(l[8]).ok()?,
                elapsed_ms: l[9],
            },
            wall_now_ms: l[10],
            compass_deg: optional(d[13]),
            jammed: false,
            inside_service_area: l[3] != 0,
        };
        let verdict = {
            let mut registry = trust_classifiers().lock().ok()?;
            let state = registry.get_mut(&handle)?;
            input.jammed = state.jam_detector.jammed();
            state.classifier.evaluate(input)
        };
        let mut result = Vec::with_capacity(verdict.reasons.len() + 1);
        result.push(trust_level_code(verdict.level));
        result.extend(verdict.reasons.into_iter().map(reason_code));
        new_int_array(&env, &result)
    }))
    .ok()
    .flatten()
    .unwrap_or(ptr::null_mut())
}
