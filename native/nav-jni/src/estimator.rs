//! JNI exports for estimator.

use super::{
    Arc, AssertUnwindSafe, ERROR_INTERNAL, ERROR_INVALID_HANDLE, EstimatorTravelMode, GeoPoint,
    GpsObservation, InitialEstimate, JClass, JDoubleArray, JLongArray, JNIEnv, MotionObservation,
    NEXT_HANDLE, NavigationEstimator, NetworkObservation, OK, ObservationTrust, Ordering, REJECTED,
    TurnObservation, WalkingObservation, catch_unwind, error_code, estimate_values, estimators,
    guarded_code, jdouble, jdoubleArray, jint, jlong, new_double_array, optional, ptr, routes,
    with_estimator,
};

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNavigationEstimator_nativeCreate(
    env: JNIEnv,
    _class: JClass,
    route_handle: jlong,
    initial_values: JDoubleArray,
    mode: jint,
    now_ms: jlong,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        if env.get_array_length(&initial_values).ok()? != 5 {
            return None;
        }
        let mut values = [0.0; 5];
        env.get_double_array_region(&initial_values, 0, &mut values)
            .ok()?;
        let route = {
            let registry = routes().lock().ok()?;
            Arc::clone(registry.get(&route_handle)?)
        };
        let travel_mode = match mode {
            0 => EstimatorTravelMode::Car,
            1 => EstimatorTravelMode::Foot,
            _ => return None,
        };
        let estimator = NavigationEstimator::new(
            route,
            InitialEstimate {
                position_m: values[0],
                speed_mps: values[1],
                position_sigma_m: values[2],
                speed_sigma_mps: values[3],
                systematic_drift_m: values[4],
            },
            travel_mode,
            now_ms,
        )
        .ok()?;
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        if handle <= 0 {
            return None;
        }
        estimators().lock().ok()?.insert(handle, estimator);
        Some(handle)
    }))
    .ok()
    .flatten()
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNavigationEstimator_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(|| {
        let Ok(mut registry) = estimators().lock() else {
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
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNavigationEstimator_nativeSetNetworkSpeedEnabled(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    enabled: jint,
) -> jint {
    guarded_code(|| {
        if !(0..=1).contains(&enabled) {
            return ERROR_INTERNAL;
        }
        match with_estimator(handle, |estimator| {
            estimator.set_network_speed_enabled(enabled == 1);
        }) {
            Ok(()) => OK,
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNavigationEstimator_nativeOnVehicleSpeed(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    speed_kmh: jdouble,
    elapsed_ms: jlong,
) -> jint {
    guarded_code(|| {
        match with_estimator(handle, |estimator| {
            estimator.on_vehicle_speed(speed_kmh, elapsed_ms)
        }) {
            Ok(Ok(false)) => REJECTED,
            Ok(Ok(true)) => OK,
            Ok(Err(error)) => error_code(error),
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNavigationEstimator_nativeReplaceRoute(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    route_handle: jlong,
    position_m: jdouble,
    position_sigma_m: jdouble,
) -> jint {
    guarded_code(|| {
        let route = {
            let Ok(registry) = routes().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(route) = registry.get(&route_handle) else {
                return ERROR_INVALID_HANDLE;
            };
            Arc::clone(route)
        };
        match with_estimator(handle, |estimator| {
            estimator.replace_route(route, position_m, position_sigma_m)
        }) {
            Ok(Ok(())) => OK,
            Ok(Err(error)) => error_code(error),
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNavigationEstimator_nativeTick(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    now_ms: jlong,
    doubles: JDoubleArray,
    longs: JLongArray,
) -> jdoubleArray {
    catch_unwind(AssertUnwindSafe(|| {
        let double_count = env.get_array_length(&doubles).ok()?;
        let long_count = env.get_array_length(&longs).ok()?;
        let (double_len, long_len) = match (double_count, long_count) {
            (11, 11) => (11, 11),
            (10, 8) => (10, 8),
            (7, 6) => (7, 6),
            (5, 3) => (5, 3),
            _ => return None,
        };
        let has_turn_fields = double_len == 11;
        let has_network_fields = double_len >= 10;
        let has_motion_fields = double_len >= 7;
        let mut values = [0.0; 11];
        let mut flags = [0_i64; 11];
        env.get_double_array_region(&doubles, 0, &mut values[..double_len])
            .ok()?;
        env.get_long_array_region(&longs, 0, &mut flags[..long_len])
            .ok()?;
        let gps = if flags[0] == 0 {
            None
        } else {
            Some(GpsObservation {
                point: GeoPoint {
                    latitude_deg: values[0],
                    longitude_deg: values[1],
                },
                elapsed_ms: flags[1],
                position_accuracy_m: optional(values[2]),
                speed_mps: optional(values[3]),
                speed_accuracy_mps: optional(values[4]),
                trust: match flags[2] {
                    0 => ObservationTrust::Good,
                    1 => ObservationTrust::Suspect,
                    _ => return None,
                },
            })
        };
        let motion = if has_motion_fields && flags[3] == 1 {
            Some(MotionObservation {
                factor: values[5],
                cruise_speed_mps: values[6],
                valid_until_ms: flags[4],
                network_moving: match flags[5] {
                    0 => false,
                    1 => true,
                    _ => return None,
                },
            })
        } else {
            None
        };
        let network = if has_network_fields && flags[6] == 1 {
            Some(NetworkObservation {
                point: GeoPoint {
                    latitude_deg: values[7],
                    longitude_deg: values[8],
                },
                accuracy_m: values[9],
                elapsed_ms: flags[7],
            })
        } else {
            None
        };
        let walking = if has_motion_fields && flags[3] == 2 {
            Some(WalkingObservation {
                speed_mps: values[5] * values[6],
                valid_until_ms: flags[4],
            })
        } else {
            None
        };
        let turn = if has_turn_fields && flags[8] == 1 {
            Some(TurnObservation {
                start_ms: flags[9],
                end_ms: flags[10],
                angle_deg: values[10],
            })
        } else {
            None
        };
        let outcome = with_estimator(handle, |estimator| {
            estimator.tick_with_walking(now_ms, gps, motion, network, turn, walking)
        })
        .ok()?
        .ok()?;
        let mut result = estimate_values(outcome.estimate)?.to_vec();
        result.push(if outcome.position_accepted { 1.0 } else { 0.0 });
        result.push(if outcome.speed_accepted { 1.0 } else { 0.0 });
        new_double_array(&env, &result)
    }))
    .ok()
    .flatten()
    .unwrap_or(ptr::null_mut())
}
