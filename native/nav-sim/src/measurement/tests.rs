use super::*;

#[test]
fn counts_requested_bytes_and_stops_at_region_boundary() {
    let region = Region::start(true);
    let allocation = std::hint::black_box(vec![7_u8; 4096]);
    let (_, counts) = region.finish();
    assert_eq!(counts.calls, 1);
    assert_eq!(counts.requested_bytes, 4096);
    drop(allocation);
    assert!(!COUNTING.get());
}

#[test]
fn zeroed_allocations_and_full_reallocation_sizes_are_counted() {
    let region = Region::start(true);
    // SAFETY: the exact allocated layout is used for reallocation and deallocation.
    unsafe {
        let layout = Layout::from_size_align(64, 8).unwrap();
        let pointer = std::alloc::alloc_zeroed(layout);
        assert!(!pointer.is_null());
        assert_eq!(*pointer, 0);
        let grown = std::alloc::realloc(pointer, layout, 128);
        assert!(!grown.is_null());
        std::alloc::dealloc(grown, Layout::from_size_align(128, 8).unwrap());
    }
    let (_, counts) = region.finish();
    assert_eq!(counts.calls, 2);
    assert_eq!(counts.requested_bytes, 192);
    let region = Region::start(true);
    drop(region);
    assert!(!COUNTING.get());
    record(std::ptr::null_mut(), 999);
    assert_eq!(CALLS.get(), 0);
}
