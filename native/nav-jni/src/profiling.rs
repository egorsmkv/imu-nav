//! Optional Linux host profiling of Rust allocations, including the linked navigation core.

use jni::JNIEnv;
use jni::objects::{JByteArray, JObject};
use jni::sys::jbyteArray;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;

#[cfg(all(feature = "heap-profile", not(target_os = "linux")))]
compile_error!("heap-profile supports Linux host replay only");

#[cfg(all(feature = "heap-profile", target_os = "linux"))]
#[global_allocator]
static ALLOCATOR: tikv_jemallocator::Jemalloc = tikv_jemallocator::Jemalloc;

// SAFETY: This is jemalloc's documented configuration symbol, with a static NUL-terminated value.
// Prefixed symbols isolate Rust allocations from JVM/system allocations. Sampling starts at load.
#[cfg(all(feature = "heap-profile", target_os = "linux"))]
#[unsafe(export_name = "_rjem_malloc_conf")]
pub static MALLOC_CONF: &[u8] = b"prof:true,prof_active:true,lg_prof_sample:19\0";

/// Capture live sampled allocations; the caller owns file I/O and must run off the UI thread.
#[cfg(all(feature = "heap-profile", target_os = "linux"))]
fn snapshot() -> Result<Vec<u8>, String> {
    let controller = jemalloc_pprof::PROF_CTL
        .as_ref()
        .ok_or("jemalloc profiling disabled by allocator configuration")?;
    let mut controller = controller.blocking_lock();
    if !controller.activated() {
        return Err("jemalloc heap profiling is inactive".into());
    }
    controller.dump_pprof().map_err(|error| error.to_string())
}

#[cfg(not(all(feature = "heap-profile", target_os = "linux")))]
fn snapshot() -> Result<Vec<u8>, String> {
    Err("heap profiling requires a Linux JNI build with --features heap-profile".into())
}

/// Never unwind across JNI; report unsupported builds and capture failures to the JVM.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_replay_NativeHeapProfiler_snapshot(
    mut env: JNIEnv,
    _object: JObject,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let bytes = snapshot()?;
        env.byte_array_from_slice(&bytes)
            .map(JByteArray::into_raw)
            .map_err(|error| error.to_string())
    }));
    match result {
        Ok(Ok(bytes)) => bytes,
        failure => {
            let message = match failure {
                Ok(Err(message)) => message,
                _ => "panic while capturing native heap profile".into(),
            };
            // Preserve pending JVM failures, particularly allocation errors creating the byte array.
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new("java/lang/IllegalStateException", message);
            }
            ptr::null_mut()
        }
    }
}

#[cfg(test)]
mod tests;
