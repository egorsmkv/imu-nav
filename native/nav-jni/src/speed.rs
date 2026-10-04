//! JNI exports for speed.

use super::{
    AssertUnwindSafe, JClass, JNIEnv, SpeedEstimate, catch_unwind, fuse_speed, jdouble, jint,
    jlong, optional,
};

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeSpeedFusion_nativeFuse(
    _env: JNIEnv,
    _class: JClass,
    last_gps_speed_mps: jdouble,
    gps_age_ms: jlong,
    route_prior_mps: jdouble,
    network_speed_mps: jdouble,
    network_sigma_mps: jdouble,
    network_samples: jint,
    network_span_s: jdouble,
) -> jdouble {
    catch_unwind(AssertUnwindSafe(|| {
        let network = optional(network_speed_mps).map(|speed_mps| SpeedEstimate {
            speed_mps,
            sigma_mps: network_sigma_mps,
            samples: usize::try_from(network_samples).unwrap_or(0),
            span_s: network_span_s,
        });
        fuse_speed(
            optional(last_gps_speed_mps),
            gps_age_ms,
            optional(route_prior_mps),
            network,
        )
        .map_or(0.0, |estimate| estimate.speed_mps)
    }))
    .unwrap_or(0.0)
}
