//! Compare owned JNI-style snapshots with borrowed host iteration without timing-dependent limits.
use androidx_graphics_path::iterator::{ConicEvaluation, PathIterator, VerbDirection};
use androidx_graphics_path::{Point, Verb};
use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;
use std::hint::black_box;

thread_local! {
    static ENABLED: Cell<bool> = const { Cell::new(false) };
    static COUNTS: Cell<(usize, usize)> = const { Cell::new((0, 0)) };
}

struct CountingAllocator;

fn count(bytes: usize) {
    if ENABLED.try_with(Cell::get).unwrap_or(false) {
        COUNTS.with(|counts| {
            let (allocations, total) = counts.get();
            counts.set((allocations + 1, total + bytes));
        });
    }
}

// SAFETY: every allocation/deallocation is forwarded unchanged to the system allocator.
unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        count(layout.size());
        // SAFETY: the allocator caller supplies a valid layout, forwarded unchanged.
        unsafe { System.alloc(layout) }
    }
    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        count(layout.size());
        // SAFETY: the allocator caller supplies a valid layout, forwarded unchanged.
        unsafe { System.alloc_zeroed(layout) }
    }
    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        // SAFETY: the allocator caller supplies the matching allocation and layout.
        unsafe { System.dealloc(pointer, layout) }
    }
    unsafe fn realloc(&self, pointer: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        count(size);
        // SAFETY: the allocator caller supplies a live allocation and valid new size.
        unsafe { System.realloc(pointer, layout, size) }
    }
}

#[global_allocator]
static ALLOCATOR: CountingAllocator = CountingAllocator;

fn measure<T>(work: impl FnOnce() -> T) -> (T, (usize, usize)) {
    COUNTS.with(|counts| counts.set((0, 0)));
    ENABLED.with(|enabled| enabled.set(true));
    let result = work();
    ENABLED.with(|enabled| enabled.set(false));
    (result, COUNTS.with(Cell::get))
}

fn walk(iterator: &mut PathIterator<'_>) -> u64 {
    let mut output = [Point::default(); 4];
    let mut checksum = 0_u64;
    while iterator.has_next() {
        let verb = iterator.next(&mut output);
        checksum = checksum.wrapping_mul(31).wrapping_add(verb as u64);
        for point in black_box(output) {
            checksum = checksum
                .wrapping_mul(31)
                .wrapping_add(u64::from(point.x.to_bits()));
            checksum = checksum
                .wrapping_mul(31)
                .wrapping_add(u64::from(point.y.to_bits()));
        }
    }
    black_box(checksum)
}

/// Report lifecycle cost without making correctness depend on host scheduling or clock speed.
fn lifecycle_ns<T>(mut create: impl FnMut() -> T) -> u128 {
    const REPETITIONS: u32 = 20_000;
    let start = std::time::Instant::now();
    for _ in 0..REPETITIONS {
        drop(black_box(create()));
    }
    start.elapsed().as_nanos() / u128::from(REPETITIONS)
}

#[test]
fn snapshots_allocate_only_at_creation_and_match_borrowed_walks() {
    let points = [
        Point { x: 0.0, y: 0.0 },
        Point { x: 1.0, y: 2.0 },
        Point { x: 3.0, y: 0.0 },
    ];
    let verbs = [Verb::Move as u8, Verb::Conic as u8, Verb::Close as u8];
    let weights = [std::f32::consts::FRAC_1_SQRT_2];
    let (mut borrowed, borrowed_creation) = measure(|| {
        PathIterator::new(
            &points,
            &verbs,
            &weights,
            VerbDirection::Forward,
            ConicEvaluation::AsQuadratics,
            0.25,
        )
    });
    let (mut owned, snapshot_creation) = measure(|| {
        black_box(PathIterator::from_owned(
            points.to_vec(),
            verbs.to_vec(),
            weights.to_vec(),
            VerbDirection::Forward,
            ConicEvaluation::AsQuadratics,
            0.25,
        ))
    });
    let (borrowed_result, borrowed_walk) = measure(|| walk(&mut borrowed));
    let (owned_result, owned_walk) = measure(|| walk(&mut owned));
    assert_eq!(borrowed_result, owned_result);
    assert_eq!(borrowed_creation, (0, 0));
    assert_eq!(snapshot_creation, (3, 31));
    assert_eq!(borrowed_walk, (0, 0));
    assert_eq!(owned_walk, (0, 0));
    let borrowed_lifecycle = lifecycle_ns(|| {
        PathIterator::new(
            &points,
            &verbs,
            &weights,
            VerbDirection::Forward,
            ConicEvaluation::AsQuadratics,
            0.25,
        )
    });
    let owned_lifecycle = lifecycle_ns(|| {
        PathIterator::from_owned(
            points.to_vec(),
            verbs.to_vec(),
            weights.to_vec(),
            VerbDirection::Forward,
            ConicEvaluation::AsQuadratics,
            0.25,
        )
    });
    println!(
        "borrowed_create={borrowed_creation:?} snapshot_create={snapshot_creation:?} borrowed_walk={borrowed_walk:?} owned_walk={owned_walk:?}"
    );
    println!("borrowed_lifecycle_ns={borrowed_lifecycle} snapshot_lifecycle_ns={owned_lifecycle}");
}
