//! Compare standard routing ZIP levels without rebuilding graphs or modifying the Tantivy index.
use std::{path::Path, time::Instant};
fn main() -> imu_nav_routing::Result<()> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args.len() != 3 {
        return Err("usage: archive-routing PACK_DIRECTORY OUTPUT_ZIP ROUTING_LEVEL_6_OR_9".into());
    }
    let started = Instant::now();
    imu_nav_routing::builder::archive::write(
        Path::new(&args[0]),
        Path::new(&args[1]),
        args[2].parse()?,
    )?;
    println!(
        "level={} seconds={:.3} bytes={}",
        args[2],
        started.elapsed().as_secs_f64(),
        std::fs::metadata(&args[1])?.len()
    );
    Ok(())
}
