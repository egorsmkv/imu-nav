#[test]
fn snapshot_reports_build_capability() {
    #[cfg(feature = "heap-profile")]
    {
        // Retain a large allocation through capture so sampling has real live heap to report.
        let allocation = std::hint::black_box(vec![42_u8; 8 * 1024 * 1024]);
        let profile = super::snapshot().expect("profiling must work without environment setup");
        assert!(profile.starts_with(&[0x1f, 0x8b]), "pprof must be gzip");
        assert!(profile.len() > 100);
        let stacks = jemalloc_pprof::PROF_CTL
            .as_ref()
            .unwrap()
            .blocking_lock()
            .dump_profile()
            .unwrap();
        assert!(
            stacks.iter().next().is_some(),
            "live allocations need sampled stacks"
        );
        std::hint::black_box(allocation);
    }
    #[cfg(not(feature = "heap-profile"))]
    assert!(
        super::snapshot()
            .unwrap_err()
            .contains("--features heap-profile")
    );
}
