//! Routing-pack JNI surface. Handles own Arcs, so close/cancel cannot free an active search.
use crate::{Request, SearchIndex};
use imu_nav_types::handles::{self, Allocator, Kind, RawHandleMap, Registry as HandleRegistry};
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};

type Registry<K, T> = OnceLock<HandleRegistry<K, Arc<T>>>;
static INDEXES: Registry<handles::SearchIndex, SearchIndex> = OnceLock::new();
static REQUESTS: Registry<handles::SearchRequest, AtomicBool> = OnceLock::new();
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
                    "search cancelled"
                } else {
                    "Offline search failed"
                },
            );
            T::default()
        }
    }
}

#[unsafe(no_mangle)]
extern "system" fn Java_org_imunav_core_search_TantivyNative_open(
    mut env: JNIEnv,
    _: JClass,
    path: JString,
) -> jlong {
    guarded(&mut env, |env| {
        let path: String = env.get_string(&path)?.into();
        let index = Arc::new(SearchIndex::open(std::path::Path::new(&path))?);
        let handle = NEXT.next::<handles::SearchIndex>()?;
        registry(&INDEXES)
            .lock()
            .map_err(|_| "index registry")?
            .insert(handle, index);
        Ok(handle.raw())
    })
}
#[unsafe(no_mangle)]
extern "system" fn Java_org_imunav_core_search_TantivyNative_close(
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
extern "system" fn Java_org_imunav_core_search_TantivyNative_begin(
    mut env: JNIEnv,
    _: JClass,
) -> jlong {
    guarded(&mut env, |_| {
        let handle = NEXT.next::<handles::SearchRequest>()?;
        registry(&REQUESTS)
            .lock()
            .map_err(|_| "request registry")?
            .insert(handle, Arc::new(AtomicBool::new(false)));
        Ok(handle.raw())
    })
}
#[unsafe(no_mangle)]
extern "system" fn Java_org_imunav_core_search_TantivyNative_cancel(
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
extern "system" fn Java_org_imunav_core_search_TantivyNative_finish(
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
extern "system" fn Java_org_imunav_core_search_TantivyNative_search(
    mut env: JNIEnv,
    _: JClass,
    index: jlong,
    request: jlong,
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
        let json: String = env.get_string(&json)?.into();
        if json.len() > 128 * 1024 {
            return Err("request too large".into());
        }
        let request: Request = serde_json::from_str(&json)?;
        let result = serde_json::to_string(&index.search(&request, &flag)?)?;
        crate::check(&flag)?;
        Ok(env.new_string(result)?.into_raw())
    })
}

/// The writer is available in desktop builds only, keeping it out of Android libraries.
#[cfg(feature = "builder")]
#[unsafe(no_mangle)]
extern "system" fn Java_org_imunav_core_search_TantivyNative_build(
    mut env: JNIEnv,
    _: JClass,
    input: JString,
    directory: JString,
) {
    guarded(&mut env, |env| {
        let input: String = env.get_string(&input)?.into();
        let directory: String = env.get_string(&directory)?.into();
        crate::build(
            std::io::BufReader::new(std::fs::File::open(input)?),
            std::path::Path::new(&directory),
        )
    });
}
