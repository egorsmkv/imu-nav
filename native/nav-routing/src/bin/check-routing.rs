//! Host smoke test for the exact runtime used on Android, without a JVM or emulator.
use imu_nav_routing::{
    Result,
    route::{Request, route},
    storage::Network,
};
use std::{path::PathBuf, sync::atomic::AtomicBool, time::Instant};
fn main() -> Result<()> {
    let dir = PathBuf::from(
        std::env::args()
            .nth(1)
            .ok_or("usage: check-routing PACK_DIRECTORY")?,
    );
    let info: serde_json::Value = serde_json::from_slice(&std::fs::read(dir.join("pack.json"))?)?;
    if info["routingFormat"] != imu_nav_routing::FORMAT {
        return Err("unsupported pack".into());
    }
    let queries = std::env::args().nth(2).map(std::fs::read).transpose()?;
    for profile in ["car", "foot"] {
        let opened = Instant::now();
        let network = Network::open(&dir.join(format!("{profile}.routing")))?;
        println!(
            "profile={profile} open_ms={:.3}",
            opened.elapsed().as_secs_f64() * 1000.
        );
        if let Some(data) = &queries {
            let requests: Vec<Request> = serde_json::from_slice(data)?;
            for (index, request) in requests.iter().enumerate() {
                let mut timings = Vec::new();
                let mut geometry_points = 0;
                for _ in 0..11 {
                    let started = Instant::now();
                    let result = route(&network, request, &AtomicBool::new(false))?;
                    timings.push(started.elapsed().as_secs_f64() * 1000.);
                    geometry_points = result.geometry.len();
                }
                let first = timings.remove(0);
                timings.sort_by(f64::total_cmp);
                println!(
                    "profile={profile} query={index} first_ms={first:.3} warm_p50_ms={:.3} warm_p95_ms={:.3} geometry_points={geometry_points}",
                    timings[4], timings[9]
                );
            }
        }
        let mut successes = 0;
        for index in 0..32 {
            let id = (index * 7919) % network.count;
            let road = network.state(u32::try_from(id)?);
            let from = network.point(road.point_start);
            let to = network.point(road.point_start + road.point_count - 1);
            let request = Request {
                points: vec![[from.lat, from.lon], [to.lat, to.lon]],
            };
            let result = route(&network, &request, &AtomicBool::new(false))?;
            if result.geometry.is_empty() {
                return Err("empty route".into());
            }
            successes += 1;
        }
        println!(
            "profile={profile} states={} routes={successes} validated=true",
            network.count
        );
    }
    Ok(())
}
