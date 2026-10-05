//! One subscriber per loaded JNI library; Android events go to Logcat.

use std::ffi::c_void;
use std::sync::OnceLock;
use tracing_subscriber::EnvFilter;
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;

type FilterHandle = tracing_subscriber::reload::Handle<EnvFilter, tracing_subscriber::Registry>;
static FILTER: OnceLock<FilterHandle> = OnceLock::new();

#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(
    _vm: *mut jni::sys::JavaVM,
    _reserved: *mut c_void,
) -> jni::sys::jint {
    init_logging();
    jni::sys::JNI_VERSION_1_6
}

/// The app selects extra diagnostics after the library has been loaded.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeLogging_nativeConfigure(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    diagnostics: jni::sys::jboolean,
) {
    init_logging();
    if let Some(filter) = FILTER.get() {
        let directive = if diagnostics == 0 {
            "warn"
        } else {
            "warn,imu_nav_core=debug,imu_nav_jni=debug"
        };
        let _ = filter.reload(EnvFilter::new(directive));
        tracing::debug!(diagnostics = diagnostics != 0, "native logging configured");
    }
}

fn init_logging() {
    if FILTER.get().is_some() {
        return;
    }
    let (filter, handle) = tracing_subscriber::reload::Layer::new(
        EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("warn")),
    );
    #[cfg(target_os = "android")]
    let result = tracing_subscriber::registry()
        .with(filter)
        .with(
            tracing_subscriber::fmt::layer()
                .with_ansi(false)
                .with_writer(AndroidMakeWriter),
        )
        .try_init();
    #[cfg(not(target_os = "android"))]
    let result = tracing_subscriber::registry()
        .with(filter)
        .with(tracing_subscriber::fmt::layer().with_writer(std::io::stderr))
        .try_init();
    if result.is_ok() {
        let _ = FILTER.set(handle);
    }
}

#[cfg(target_os = "android")]
struct AndroidMakeWriter;

#[cfg(target_os = "android")]
impl<'a> tracing_subscriber::fmt::MakeWriter<'a> for AndroidMakeWriter {
    type Writer = AndroidLogWriter;

    fn make_writer(&'a self) -> Self::Writer {
        AndroidLogWriter::new(4)
    }

    fn make_writer_for(&'a self, metadata: &tracing::Metadata<'_>) -> Self::Writer {
        let priority = match *metadata.level() {
            tracing::Level::ERROR => 6,
            tracing::Level::WARN => 5,
            tracing::Level::INFO => 4,
            tracing::Level::DEBUG => 3,
            tracing::Level::TRACE => 2,
        };
        AndroidLogWriter::new(priority)
    }
}

#[cfg(target_os = "android")]
struct AndroidLogWriter {
    priority: i32,
    bytes: Vec<u8>,
}

#[cfg(target_os = "android")]
impl AndroidLogWriter {
    fn new(priority: i32) -> Self {
        Self {
            priority,
            bytes: Vec::new(),
        }
    }
}

#[cfg(target_os = "android")]
impl std::io::Write for AndroidLogWriter {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        self.bytes.extend_from_slice(bytes);
        Ok(bytes.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

#[cfg(target_os = "android")]
impl Drop for AndroidLogWriter {
    fn drop(&mut self) {
        use std::ffi::CString;
        let line = String::from_utf8_lossy(&self.bytes);
        let line = line.trim_end_matches('\n').replace('\0', " ");
        if let Ok(message) = CString::new(line) {
            // SAFETY: Both pointers are NUL-terminated and live for the duration of the call.
            unsafe { __android_log_write(self.priority, c"ImuNavRust".as_ptr(), message.as_ptr()) };
        }
    }
}

#[cfg(target_os = "android")]
#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(
        priority: i32,
        tag: *const std::ffi::c_char,
        text: *const std::ffi::c_char,
    ) -> i32;
}
