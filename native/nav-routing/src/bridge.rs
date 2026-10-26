//! Routing-pack JNI surface. Handles own Arcs, so close/cancel cannot free an active search.
use crate::{pack::Pack, route::Request};
use imu_nav_types::handles::{self, Allocator, Kind, RawHandleMap, Registry as HandleRegistry};
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jstring};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};

type Registry<K, T> = OnceLock<HandleRegistry<K, Arc<T>>>;
static INDEXES: Registry<handles::RoutingPack, Pack> = OnceLock::new();
static REQUESTS: Registry<handles::RoutingRequest, AtomicBool> = OnceLock::new();
static NEXT: Allocator = Allocator::new();
fn registry<K: Kind, T>(value: &Registry<K, T>) -> &HandleRegistry<K, Arc<T>> {
    value.get_or_init(HandleRegistry::new)
}

fn guarded<T: Default>(env: &mut JNIEnv, work: impl FnOnce(&mut JNIEnv) -> crate::Result<T>) -> T {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| work(env))) {
        Ok(Ok(value)) => value,
        error => {
            let cancelled = matches!(&error, Ok(Err(e)) if e.is::<crate::Cancelled>());
            let class = if cancelled {
                "java/util/concurrent/CancellationException"
            } else {
                "java/io/IOException"
            };
            // Do not expose paths, queries, coordinates or arbitrary panic payloads across JNI.
            let _ = env.throw_new(
                class,
                if cancelled {
                    "routing cancelled"
                } else {
                    "Offline routing failed"
                },
            );
            T::default()
        }
    }
}

#[unsafe(no_mangle)]
extern "system" fn Java_org_imunav_routing_RoutingNative_open(
    mut env: JNIEnv,
    _: JClass,
    path: JString,
    validate: jboolean,
) -> jlong {
    guarded(&mut env, |env| {
        let path: String = env.get_string(&path)?.into();
        let directory = std::path::Path::new(&path);
        // The app skips the full scan only for an installed pack it validated and that is unchanged.
        let pack = if validate == 0 {
            Pack::open_trusted(directory)?
        } else {
            Pack::open(directory)?
        };
        let index = Arc::new(pack);
        let handle = NEXT.next::<handles::RoutingPack>()?;
        registry(&INDEXES)
            .lock()
            .map_err(|_| "index registry")?
            .insert(handle, index);
        Ok(handle.raw())
    })
}
#[unsafe(no_mangle)]
extern "system" fn Java_org_imunav_routing_RoutingNative_close(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    guarded(&mut env, |_| {
        registry(&INDEXES)
            .lock()
            .map_err(|_| "index registry")?
            .remove_raw(&handle);
        Ok(())
    });
}
#[unsafe(no_mangle)]
extern "system" fn Java_org_imunav_routing_RoutingNative_begin(
    mut env: JNIEnv,
    _: JClass,
) -> jlong {
    guarded(&mut env, |_| {
        let handle = NEXT.next::<handles::RoutingRequest>()?;
        registry(&REQUESTS)
            .lock()
            .map_err(|_| "request registry")?
            .insert(handle, Arc::new(AtomicBool::new(false)));
        Ok(handle.raw())
    })
}
#[unsafe(no_mangle)]
extern "system" fn Java_org_imunav_routing_RoutingNative_cancel(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    guarded(&mut env, |_| {
        if let Some(flag) = registry(&REQUESTS)
            .lock()
            .map_err(|_| "request registry")?
            .get_raw(&handle)
        {
            flag.store(true, Ordering::Relaxed);
        }
        Ok(())
    });
}
#[unsafe(no_mangle)]
extern "system" fn Java_org_imunav_routing_RoutingNative_finish(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    guarded(&mut env, |_| {
        registry(&REQUESTS)
            .lock()
            .map_err(|_| "request registry")?
            .remove_raw(&handle);
        Ok(())
    });
}
#[unsafe(no_mangle)]
extern "system" fn Java_org_imunav_routing_RoutingNative_route(
    mut env: JNIEnv,
    _: JClass,
    index: jlong,
    request: jlong,
    profile: JString,
    json: JString,
) -> jstring {
    guarded(&mut env, |env| {
        let index = registry(&INDEXES)
            .lock()
            .map_err(|_| "index registry")?
            .get_raw(&index)
            .cloned()
            .ok_or("closed index")?;
        let flag = registry(&REQUESTS)
            .lock()
            .map_err(|_| "request registry")?
            .get_raw(&request)
            .cloned()
            .ok_or("closed request")?;
        crate::check(&flag)?;
        let profile: String = env.get_string(&profile)?.into();
        let index = index.profile(profile.parse()?)?;
        let json: String = env.get_string(&json)?.into();
        if json.len() > 128 * 1024 {
            return Err("request too large".into());
        }
        let request: Request = serde_json::from_str(&json)?;
        let result = serde_json::to_string(&crate::route::route(index, &request, &flag)?)?;
        crate::check(&flag)?;
        Ok(env.new_string(result)?.into_raw())
    })
}

#[unsafe(no_mangle)]
extern "system" fn Java_org_imunav_routing_RoutingNative_mapMatch(
    mut env: JNIEnv,
    _: JClass,
    index: jlong,
    request: jlong,
    profile: JString,
    json: JString,
) -> jstring {
    guarded(&mut env, |env| {
        let index = registry(&INDEXES)
            .lock()
            .map_err(|_| "index registry")?
            .get_raw(&index)
            .cloned()
            .ok_or("closed index")?;
        let flag = registry(&REQUESTS)
            .lock()
            .map_err(|_| "request registry")?
            .get_raw(&request)
            .cloned()
            .ok_or("closed request")?;
        crate::check(&flag)?;
        let profile: String = env.get_string(&profile)?.into();
        let index = index.profile(profile.parse()?)?;
        let json: String = env.get_string(&json)?.into();
        if json.len() > 1024 * 1024 {
            return Err("request too large".into());
        }
        let request: crate::matching::Request = serde_json::from_str(&json)?;
        let result = serde_json::to_string(&crate::matching::match_track(index, &request, &flag)?)?;
        crate::check(&flag)?;
        Ok(env.new_string(result)?.into_raw())
    })
}

/// The desktop JNI writer is excluded from Android builds.
#[cfg(feature = "builder")]
#[unsafe(no_mangle)]
extern "system" fn Java_org_imunav_routing_RoutingNative_build(
    mut env: JNIEnv,
    _: JClass,
    input: JString,
    output: JString,
    name: JString,
    minimum: jni::sys::jint,
    search: jni::sys::jboolean,
    addresses: jni::sys::jboolean,
) {
    guarded(&mut env, |env| {
        let input: String = env.get_string(&input)?.into();
        let output: String = env.get_string(&output)?.into();
        let name: String = env.get_string(&name)?.into();
        if minimum < 0 {
            return Err("invalid network size".into());
        }
        crate::builder::build(
            std::path::Path::new(&input),
            std::path::Path::new(&output),
            &name,
            usize::try_from(minimum)?,
            search != 0,
            addresses != 0,
        )?;
        Ok(())
    });
}
