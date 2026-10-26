//! Versioned offline road packs, with no Java routing or OSM dependency.
mod bridge;
#[cfg(feature = "builder")]
pub mod builder;
mod file_data;
pub mod geo;
pub mod ids;
pub mod matching;
pub mod pack;
pub mod profile;
pub mod query;
pub mod route;
mod shared;
pub mod storage;
pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error + Send + Sync>>;
pub const FORMAT: &str = "rust-ch-2";
/// # Errors
/// Returns malformed input, unavailable graph data, I/O, or request cancellation errors.
pub fn check(cancel: &std::sync::atomic::AtomicBool) -> Result<()> {
    if cancel.load(std::sync::atomic::Ordering::Relaxed) {
        Err(Cancelled.into())
    } else {
        Ok(())
    }
}

/// Unsupported routing profile. The untrusted input is deliberately not retained.
#[derive(Clone, Copy, Debug)]
pub struct ProfileError;
impl std::fmt::Display for ProfileError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("invalid profile")
    }
}
impl std::error::Error for ProfileError {}

/// Request-scoped cancellation, distinguishable without inspecting an error message.
#[derive(Clone, Copy, Debug)]
pub struct Cancelled;
impl std::fmt::Display for Cancelled {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("routing cancelled")
    }
}
impl std::error::Error for Cancelled {}
