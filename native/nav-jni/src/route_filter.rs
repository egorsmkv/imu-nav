//! JNI exports for route filter.

use super::{
    Arc, AssertUnwindSafe, ERROR_INTERNAL, ERROR_INVALID_HANDLE, GeoPoint, JClass, JDoubleArray,
    JNIEnv, NEXT_HANDLE, NativeRouteState, OK, Ordering, REJECTED, RouteFilter, RouteGeometry,
    catch_unwind, error_code, estimate_values, filters, guarded_code, jdouble, jdoubleArray, jint,
    jlong, new_double_array, ptr, routes, usize_as_f64, with_filter, with_route_state,
};

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
    position_m: jdouble,
    speed_mps: jdouble,
    position_sigma_m: jdouble,
    speed_sigma_mps: jdouble,
    systematic_drift_m: jdouble,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let filter = RouteFilter::new(
            position_m,
            speed_mps,
            position_sigma_m,
            speed_sigma_mps,
            systematic_drift_m,
        )
        .ok()?;
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        if handle <= 0 {
            return None;
        }
        filters().lock().ok()?.insert(
            handle,
            NativeRouteState {
                filter,
                route: None,
            },
        );
        Some(handle)
    }))
    .ok()
    .flatten()
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(|| {
        let Ok(mut registry) = filters().lock() else {
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
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativePredict(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    dt_s: jdouble,
    acceleration_sigma_mps2: jdouble,
    systematic_drift_per_m: jdouble,
) -> jint {
    guarded_code(|| {
        match with_filter(handle, |filter| {
            filter.predict(dt_s, acceleration_sigma_mps2, systematic_drift_per_m)
        }) {
            Ok(Ok(())) => OK,
            Ok(Err(error)) => error_code(error),
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativeUpdatePosition(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    position_m: jdouble,
    sigma_m: jdouble,
    nis_gate: jdouble,
) -> jint {
    guarded_code(|| {
        match with_filter(handle, |filter| {
            filter.update_position(position_m, sigma_m, nis_gate)
        }) {
            Ok(Ok(outcome)) => {
                if outcome.accepted {
                    OK
                } else {
                    REJECTED
                }
            }
            Ok(Err(error)) => error_code(error),
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativeUpdateSpeed(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    speed_mps: jdouble,
    sigma_mps: jdouble,
    nis_gate: jdouble,
) -> jint {
    guarded_code(|| {
        match with_filter(handle, |filter| {
            filter.update_speed(speed_mps, sigma_mps, nis_gate)
        }) {
            Ok(Ok(outcome)) => {
                if outcome.accepted {
                    OK
                } else {
                    REJECTED
                }
            }
            Ok(Err(error)) => error_code(error),
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativeAnchorPosition(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    position_m: jdouble,
    sigma_m: jdouble,
) -> jint {
    guarded_code(|| {
        match with_filter(handle, |filter| filter.anchor_position(position_m, sigma_m)) {
            Ok(Ok(())) => OK,
            Ok(Err(error)) => error_code(error),
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativeResetSystematicDrift(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(
        || match with_filter(handle, RouteFilter::reset_systematic_drift) {
            Ok(()) => OK,
            Err(code) => code,
        },
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteGeometry_nativeCreate(
    env: JNIEnv,
    _class: JClass,
    coordinates: JDoubleArray,
) -> jlong {
    let _profile = crate::device_profile::measure(crate::device_profile::ROUTE_CREATE);
    catch_unwind(AssertUnwindSafe(|| {
        let Ok(length) = env.get_array_length(&coordinates) else {
            return None;
        };
        if length < 4 || length % 2 != 0 {
            return None;
        }
        let mut values = vec![0.0; usize::try_from(length).ok()?];
        if env
            .get_double_array_region(&coordinates, 0, &mut values)
            .is_err()
        {
            return None;
        }
        let points = values
            .as_chunks::<2>()
            .0
            .iter()
            .map(|&[latitude_deg, longitude_deg]| GeoPoint {
                latitude_deg,
                longitude_deg,
            })
            .collect();
        let route = Arc::new(RouteGeometry::new(points).ok()?);
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        if handle <= 0 {
            return None;
        }
        routes().lock().ok()?.insert(handle, route);
        Some(handle)
    }))
    .ok()
    .flatten()
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteGeometry_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(|| {
        let Ok(mut registry) = routes().lock() else {
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
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteGeometry_nativeProject(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    latitude_deg: jdouble,
    longitude_deg: jdouble,
    around_m: jdouble,
    behind_m: jdouble,
    ahead_m: jdouble,
    global_if_farther_m: jdouble,
) -> jdoubleArray {
    let _profile = crate::device_profile::measure(crate::device_profile::ROUTE_PROJECT);
    catch_unwind(AssertUnwindSafe(|| {
        let route = {
            let registry = routes().lock().ok()?;
            Arc::clone(registry.get(&handle)?)
        };
        let projection = route
            .project(
                GeoPoint {
                    latitude_deg,
                    longitude_deg,
                },
                around_m,
                behind_m,
                ahead_m,
                global_if_farther_m,
            )
            .ok()?;
        let values = [
            projection.position_m,
            projection.offset_m,
            usize_as_f64(projection.segment)?,
            projection.point.latitude_deg,
            projection.point.longitude_deg,
        ];
        new_double_array(&env, &values)
    }))
    .ok()
    .flatten()
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativeInstallRoute(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    route_handle: jlong,
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
        match with_route_state(handle, |state| state.route = Some(route)) {
            Ok(()) => OK,
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativeProject(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    latitude_deg: jdouble,
    longitude_deg: jdouble,
    around_m: jdouble,
    behind_m: jdouble,
    ahead_m: jdouble,
    global_if_farther_m: jdouble,
) -> jdoubleArray {
    catch_unwind(AssertUnwindSafe(|| {
        let projection = with_route_state(handle, |state| {
            state
                .route
                .as_ref()?
                .project(
                    GeoPoint {
                        latitude_deg,
                        longitude_deg,
                    },
                    around_m,
                    behind_m,
                    ahead_m,
                    global_if_farther_m,
                )
                .ok()
        })
        .ok()
        .flatten()?;
        let values = [
            projection.position_m,
            projection.offset_m,
            usize_as_f64(projection.segment)?,
            projection.point.latitude_deg,
            projection.point.longitude_deg,
        ];
        new_double_array(&env, &values)
    }))
    .ok()
    .flatten()
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativeGetState(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jdoubleArray {
    catch_unwind(AssertUnwindSafe(|| {
        let estimate = with_filter(handle, |filter| filter.estimate()).ok()?;
        let values = estimate_values(estimate)?;
        new_double_array(&env, &values)
    }))
    .ok()
    .flatten()
    .unwrap_or(ptr::null_mut())
}
