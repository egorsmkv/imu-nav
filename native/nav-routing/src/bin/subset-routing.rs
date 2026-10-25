//! Build car-only or walking-only downloads from an existing combined pack.
use std::{path::Path, time::Instant};
fn main() -> imu_nav_routing::Result<()> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args.len() != 3 {
        return Err("usage: subset-routing SOURCE_PACK OUTPUT_PACK car|walk".into());
    }
    let profile = match args[2].as_str() {
        "car" => "car",
        "walk" => "foot",
        _ => return Err("mode must be car or walk".into()),
    };
    let started = Instant::now();
    let info = imu_nav_routing::builder::subset::create(
        Path::new(&args[0]),
        Path::new(&args[1]),
        profile,
    )?;
    let archive = format!("{}.zip", args[1]);
    imu_nav_routing::builder::archive::write(Path::new(&args[1]), Path::new(&archive), 6)?;
    println!(
        "mode={} seconds={:.3} archive_bytes={} pack={info}",
        args[2],
        started.elapsed().as_secs_f64(),
        std::fs::metadata(archive)?.len()
    );
    Ok(())
}
