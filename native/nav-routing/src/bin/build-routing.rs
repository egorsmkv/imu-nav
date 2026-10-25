//! Standalone Rust OSM-to-routing-pack command; no JVM or Android SDK is required.
use std::{collections::HashMap, path::PathBuf};
fn main() -> imu_nav_routing::Result<()> {
    let mut opts = HashMap::new();
    let mut args = std::env::args().skip(1);
    while let Some(key) = args.next() {
        if key == "--no-search" || key == "--no-addresses" {
            opts.insert(key, "true".to_string());
        } else {
            let value = args.next().ok_or("option needs a value")?;
            opts.insert(key, value);
        }
    }
    for key in opts.keys() {
        if ![
            "--osm",
            "--out",
            "--name",
            "--no-search",
            "--no-addresses",
            "--threads",
            "--min-network-size",
            "--elevation",
            "--elevation-cache",
        ]
        .contains(&key.as_str())
        {
            return Err(format!("unknown option: {key}").into());
        }
    }
    let input = PathBuf::from(opts.get("--osm").ok_or("--osm is required")?);
    let output = PathBuf::from(opts.get("--out").ok_or("--out is required")?);
    let name = opts.get("--name").map_or("Region", String::as_str);
    let threads: usize = opts.get("--threads").map_or(Ok(2), |v| v.parse())?;
    if !(1..=64).contains(&threads) {
        return Err("threads must be 1..64".into());
    }
    rayon::ThreadPoolBuilder::new()
        .num_threads(threads)
        .build_global()?;
    if opts
        .get("--elevation")
        .is_some_and(|v| v != "srtm" && v != "hgt")
    {
        return Err(
            "Rust elevation requires --elevation srtm and local --elevation-cache HGT tiles".into(),
        );
    }
    let terrain = opts.get("--elevation").map(|_| {
        PathBuf::from(
            opts.get("--elevation-cache")
                .map_or("elevation-cache", String::as_str),
        )
    });
    let started = std::time::Instant::now();
    let info = imu_nav_routing::builder::build_with_elevation(
        &input,
        &output,
        name,
        opts.get("--min-network-size")
            .map_or(Ok(200), |v| v.parse())?,
        !opts.contains_key("--no-search"),
        !opts.contains_key("--no-addresses"),
        terrain.as_deref(),
    )?;
    let zip_path = PathBuf::from(format!("{}.zip", output.display()));
    let archive_started = std::time::Instant::now();
    imu_nav_routing::builder::archive::write(&output, &zip_path, 6)?;
    println!(
        "[routing] archive_seconds={:.3}",
        archive_started.elapsed().as_secs_f64()
    );
    println!(
        "[routing] seconds={:.3} archive_bytes={} pack={info}",
        started.elapsed().as_secs_f64(),
        zip_path.metadata()?.len()
    );
    Ok(())
}
