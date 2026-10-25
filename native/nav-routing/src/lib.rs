//! Versioned offline road packs, with no Java routing or OSM dependency.
mod bridge;
#[cfg(feature = "builder")]
pub mod builder;
mod file_data;
pub mod geo;
pub mod matching;
pub mod pack;
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
        Err("routing cancelled".into())
    } else {
        Ok(())
    }
}
