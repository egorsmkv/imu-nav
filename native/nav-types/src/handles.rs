//! Typed positive native handles. Tags are shared by every library, counters never wrap or reuse.
//!
//! ```compile_fail
//! use imu_nav_types::handles::{Handle, Route, Filter};
//! let route = Handle::<Route>::from_raw(1).unwrap();
//! let filter: Handle<Filter> = route;
//! ```

use std::collections::HashMap;
use std::fmt;
use std::marker::PhantomData;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{LockResult, Mutex, MutexGuard};

const COUNTER_BITS: u32 = 55;
const COUNTER_LIMIT: u64 = (1 << COUNTER_BITS) - 1;

mod sealed {
    pub trait Sealed {}
}

/// Each native object kind has a unique tag across all IMU Nav libraries.
pub trait Kind: sealed::Sealed + Copy + Eq + std::hash::Hash {
    /// Nonzero tag in the positive signed handle's upper eight bits.
    const TAG: u8;
}

macro_rules! kinds {
    ($($name:ident = $tag:literal),+ $(,)?) => { $(
        #[doc = concat!("Native ", stringify!($name), " handle marker.")]
        #[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
        pub enum $name {}
        impl sealed::Sealed for $name {}
        impl Kind for $name { const TAG: u8 = $tag; }
    )+ };
}
kinds!(
    Filter = 1,
    Route = 2,
    Trust = 3,
    Network = 4,
    Estimator = 5,
    RoutingPack = 6,
    RoutingRequest = 7,
    SearchIndex = 8,
    SearchRequest = 9,
    GraphicsIterator = 10
);

/// Opaque registry key. Constructing a key checks its kind, not whether it is still alive.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct Handle<K: Kind> {
    raw: i64,
    kind: PhantomData<K>,
}

impl<K: Kind> Handle<K> {
    /// Decode a foreign handle, rejecting zero, negative, wrong-kind and empty-counter values.
    #[must_use]
    pub fn from_raw(raw: i64) -> Option<Self> {
        let value = u64::try_from(raw).ok()?;
        (value >> COUNTER_BITS == u64::from(K::TAG) && value & COUNTER_LIMIT != 0).then_some(Self {
            raw,
            kind: PhantomData,
        })
    }

    /// Encode at the JNI boundary; Kotlin continues to see an opaque Long.
    #[must_use]
    pub const fn raw(self) -> i64 {
        self.raw
    }
}

/// Registry exhaustion is permanent: reusing a counter could resurrect a stale handle.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Exhausted;

impl fmt::Display for Exhausted {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("native handle space exhausted")
    }
}
impl std::error::Error for Exhausted {}

/// An allocator may be shared by registries; kind tags also separate independent libraries.
pub struct Allocator(AtomicU64);

impl Default for Allocator {
    fn default() -> Self {
        Self::new()
    }
}

impl Allocator {
    #[must_use]
    pub const fn new() -> Self {
        Self(AtomicU64::new(1))
    }

    /// # Errors
    /// Returns an error once all positive counters have been allocated.
    pub fn next<K: Kind>(&self) -> Result<Handle<K>, Exhausted> {
        let mut counter = self.0.load(Ordering::Relaxed);
        loop {
            if counter > COUNTER_LIMIT {
                return Err(Exhausted);
            }
            match self.0.compare_exchange_weak(
                counter,
                counter + 1,
                Ordering::Relaxed,
                Ordering::Relaxed,
            ) {
                Ok(_) => break,
                Err(current) => counter = current,
            }
        }
        let raw =
            i64::try_from((u64::from(K::TAG) << COUNTER_BITS) | counter).map_err(|_| Exhausted)?;
        Ok(Handle {
            raw,
            kind: PhantomData,
        })
    }
}

/// A registry cannot accept a key for another object kind. Locking stays explicit at call sites.
pub struct Registry<K: Kind, T>(Mutex<HashMap<Handle<K>, T>>);

/// Decode raw identifiers only at foreign-function boundaries.
pub trait RawHandleMap<T> {
    /// Look up an external identifier after checking the object-kind tag.
    fn get_raw(&self, raw: &i64) -> Option<&T>;
    /// Mutably look up an external identifier after checking its tag.
    fn get_mut_raw(&mut self, raw: &i64) -> Option<&mut T>;
    /// Remove an external identifier; wrong-kind, stale and repeated removals are absent.
    fn remove_raw(&mut self, raw: &i64) -> Option<T>;
}

impl<K: Kind, T, S: std::hash::BuildHasher> RawHandleMap<T> for HashMap<Handle<K>, T, S> {
    fn get_raw(&self, raw: &i64) -> Option<&T> {
        self.get(&Handle::from_raw(*raw)?)
    }
    fn get_mut_raw(&mut self, raw: &i64) -> Option<&mut T> {
        self.get_mut(&Handle::from_raw(*raw)?)
    }
    fn remove_raw(&mut self, raw: &i64) -> Option<T> {
        self.remove(&Handle::from_raw(*raw)?)
    }
}

impl<K: Kind, T> Default for Registry<K, T> {
    fn default() -> Self {
        Self::new()
    }
}

impl<K: Kind, T> Registry<K, T> {
    #[must_use]
    pub fn new() -> Self {
        Self(Mutex::new(HashMap::new()))
    }

    /// # Errors
    /// Returns the standard poisoning error if a previous mutation panicked.
    pub fn lock(&self) -> LockResult<MutexGuard<'_, HashMap<Handle<K>, T>>> {
        self.0.lock()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, atomic::AtomicBool};

    #[test]
    fn kind_and_lifetime_checks_do_not_reuse_handles() {
        let allocator = Allocator::new();
        let registry = Registry::<Route, ()>::new();
        let handle = allocator.next::<Route>().unwrap();
        assert!(Handle::<Filter>::from_raw(handle.raw()).is_none());
        assert!(Handle::<Route>::from_raw(0).is_none());
        assert!(Handle::<Route>::from_raw(-1).is_none());
        assert!(Handle::<Route>::from_raw(2 << COUNTER_BITS).is_none());
        let mut values = registry.lock().unwrap();
        values.insert(handle, ());
        assert!(values.remove(&handle).is_some());
        assert!(values.remove(&handle).is_none());
        assert_ne!(allocator.next::<Route>().unwrap(), handle);
    }

    #[test]
    fn exhaustion_never_wraps() {
        let allocator = Allocator(AtomicU64::new(COUNTER_LIMIT));
        assert!(allocator.next::<Route>().is_ok());
        assert_eq!(allocator.next::<Route>(), Err(Exhausted));
        assert_eq!(allocator.next::<Route>(), Err(Exhausted));
    }

    #[test]
    fn active_request_retains_cancellation_after_removal() {
        let allocator = Allocator::new();
        let registry = Registry::<RoutingRequest, Arc<AtomicBool>>::new();
        let handle = allocator.next().unwrap();
        registry
            .lock()
            .unwrap()
            .insert(handle, Arc::new(AtomicBool::new(false)));
        let active = Arc::clone(&registry.lock().unwrap()[&handle]);
        let cancelled = registry.lock().unwrap().remove(&handle).unwrap();
        std::thread::spawn(move || cancelled.store(true, Ordering::Release))
            .join()
            .unwrap();
        assert!(active.load(Ordering::Acquire));
    }
}
