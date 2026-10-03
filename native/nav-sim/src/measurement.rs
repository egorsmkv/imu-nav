//! Allocation counts measure churn; jemalloc snapshots measure sampled live heap, never total RSS.

use std::alloc::{GlobalAlloc, Layout};
use std::cell::Cell;
use std::path::Path;
use std::time::{Duration, Instant};

use anyhow::{Result, anyhow, ensure};
use serde::{Deserialize, Serialize};
use tikv_jemallocator::Jemalloc;

thread_local! {
    // Constant-initialized TLS cannot allocate recursively in the allocator hook.
    static COUNTING: Cell<bool> = const { Cell::new(false) };
    static CALLS: Cell<u64> = const { Cell::new(0) };
    static BYTES: Cell<u64> = const { Cell::new(0) };
}

/// Counts successful allocation/reallocation requests only on the measured workload thread.
struct CountingAllocator;

#[global_allocator]
static ALLOCATOR: CountingAllocator = CountingAllocator;

// SAFETY: jemalloc's documented prefixed configuration symbol points to a static C string.
// Capture is explicitly activated for the heap pass; CPU and timing do not pay sampling costs.
#[unsafe(export_name = "_rjem_malloc_conf")]
pub static MALLOC_CONF: &[u8] = b"prof:true,prof_active:false,lg_prof_sample:16\0";

fn record(pointer: *mut u8, size: usize) {
    if !pointer.is_null() && COUNTING.try_with(Cell::get).unwrap_or(false) {
        let _ = CALLS.try_with(|counter| counter.set(counter.get().saturating_add(1)));
        let _ = BYTES.try_with(|counter| counter.set(counter.get().saturating_add(size as u64)));
    }
}

// SAFETY: Every operation forwards the exact pointer/layout contract to the same Jemalloc
// instance. Hooks use nonallocating TLS and never unwind, dereference, or own user pointers.
unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let pointer = unsafe { Jemalloc.alloc(layout) };
        record(pointer, layout.size());
        pointer
    }

    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        let pointer = unsafe { Jemalloc.alloc_zeroed(layout) };
        record(pointer, layout.size());
        pointer
    }

    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        unsafe { Jemalloc.dealloc(pointer, layout) };
    }

    unsafe fn realloc(&self, pointer: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        let result = unsafe { Jemalloc.realloc(pointer, layout, size) };
        record(result, size);
        result
    }
}

/// Exact request totals; requested bytes include the full new size of reallocations, not a delta.
#[derive(Clone, Copy, Debug, Default, Deserialize, Serialize)]
pub struct Allocations {
    pub calls: u64,
    pub requested_bytes: u64,
}

/// Ensures counters are disabled on errors as well as the normal end of a measured phase.
pub struct Region {
    started: Instant,
}

impl Region {
    pub fn start(count: bool) -> Self {
        CALLS.set(0);
        BYTES.set(0);
        COUNTING.set(count);
        Self {
            started: Instant::now(),
        }
    }

    pub fn finish(self) -> (Duration, Allocations) {
        COUNTING.set(false);
        (
            self.started.elapsed(),
            Allocations {
                calls: CALLS.get(),
                requested_bytes: BYTES.get(),
            },
        )
    }
}

impl Drop for Region {
    fn drop(&mut self) {
        COUNTING.set(false);
    }
}

/// Allocator-wide retained bytes, including the prebuilt fixture and harness. Not RSS or peak heap.
pub fn allocated_bytes() -> Result<usize> {
    tikv_jemalloc_ctl::epoch::advance().map_err(|error| anyhow!("jemalloc epoch: {error}"))?;
    tikv_jemalloc_ctl::stats::allocated::read().map_err(|error| anyhow!("jemalloc stats: {error}"))
}

/// Capture activation is separate from file generation so fixtures are not sampled.
pub fn activate_heap() -> Result<()> {
    let control = jemalloc_pprof::PROF_CTL
        .as_ref()
        .ok_or_else(|| anyhow!("jemalloc prof must be enabled"))?;
    control
        .blocking_lock()
        .activate()
        .map_err(|error| anyhow!("activate heap: {error}"))
}

pub fn snapshot(path: &Path) -> Result<()> {
    let control = jemalloc_pprof::PROF_CTL
        .as_ref()
        .ok_or_else(|| anyhow!("jemalloc prof must be enabled"))?;
    let mut control = control.blocking_lock();
    ensure!(control.activated(), "heap sampling is inactive");
    // SAFETY: prof.active is a documented boolean mallctl. Unlike controller.deactivate(),
    // toggling it does not reset existing live samples. Symbolization caches must not become
    // the dominant allocation in later profiles of this small native core.
    unsafe { tikv_jemalloc_ctl::raw::write(b"prof.active\0", false) }
        .map_err(|error| anyhow!("pause sampling: {error}"))?;
    let result = (|| {
        let bytes = control.dump_pprof()?;
        std::fs::write(path, bytes)?;
        Ok(())
    })();
    // Restore sampling even when capture or file output failed.
    // SAFETY: same documented boolean control, while holding the singleton controller lock.
    unsafe { tikv_jemalloc_ctl::raw::write(b"prof.active\0", true) }
        .map_err(|error| anyhow!("resume sampling: {error}"))?;
    result
}

/// Prevent environment overrides from silently contaminating CPU/timing runs with heap sampling.
pub fn require_sampling_disabled() -> Result<()> {
    let control = jemalloc_pprof::PROF_CTL
        .as_ref()
        .ok_or_else(|| anyhow!("jemalloc prof must be enabled"))?;
    ensure!(
        !control.blocking_lock().activated(),
        "unset _RJEM_MALLOC_CONF: heap sampling must start inactive"
    );
    Ok(())
}

#[cfg(test)]
mod tests;
