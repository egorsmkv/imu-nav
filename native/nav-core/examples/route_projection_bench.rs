//! Focused geometry benchmark: cargo run --release -p imu-nav-core --example route_projection_bench -- winding 100000 7200
//! Scenarios: winding, parallel, crossing, reacquisition. Output: scenario, points, queries, ns, checksum.
//! Setup is excluded; one warm-up precedes seven samples. Uses the platform default allocator.

use imu_nav_core::route::{GeoPoint, RouteGeometry};
use std::{hint::black_box, time::Instant};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = std::env::args().collect();
    if args.len() != 4 {
        return Err("usage: route_projection_bench <winding|parallel|crossing|reacquisition> <points: 2..=1000000> <queries: 1..=1000000>".into());
    }
    let scenario = args[1].as_str();
    if !matches!(
        scenario,
        "winding" | "parallel" | "crossing" | "reacquisition"
    ) {
        return Err("unknown scenario".into());
    }
    let count: u32 = args[2].parse()?;
    let queries: u32 = args[3].parse()?;
    if !(2..=1_000_000).contains(&count) || !(1..=1_000_000).contains(&queries) {
        return Err("point or query count is outside the supported range".into());
    }
    let points: Vec<_> = (0..count)
        .map(|index| {
            let fraction = f64::from(index) / f64::from(count - 1);
            let (north, east) = match scenario {
                "parallel" if fraction <= 0.5 => (fraction * 2.0 * 10_000.0, 0.0),
                "parallel" => ((1.0 - fraction) * 2.0 * 10_000.0, 40.0),
                "crossing" => {
                    let angle = fraction * std::f64::consts::TAU;
                    (4000.0 * angle.sin(), 2000.0 * (2.0 * angle).sin())
                }
                _ => (
                    fraction * 20_000.0,
                    250.0 * (fraction * 12.0 * std::f64::consts::TAU).sin(),
                ),
            };
            GeoPoint {
                latitude_deg: 50.0 + north / 111_195.0,
                longitude_deg: 30.0 + east / 71_500.0,
            }
        })
        .collect();
    let inputs: Vec<_> = (0..queries)
        .map(|index| {
            let vertex = ((u64::from(index) * 7919 + 1) % u64::from(count)) as usize;
            let mut point = points[vertex];
            if scenario == "crossing" && index % 3 == 0 {
                point = GeoPoint {
                    latitude_deg: 50.0,
                    longitude_deg: 30.0,
                };
            }
            if scenario == "reacquisition" {
                point.longitude_deg += 0.02;
            }
            (point, if index % 2 == 0 { 40.0 } else { 2.0 })
        })
        .collect();
    let route = RouteGeometry::new(points).unwrap();
    let run = || {
        let mut checksum = 0xcbf2_9ce4_8422_2325_u64;
        for &(point, accuracy) in &inputs {
            let projection = if scenario == "reacquisition" {
                Some(route.project(point, 0.0, 0.0, 0.0, 120.0).unwrap())
            } else {
                route.project_unambiguous(point, accuracy).unwrap()
            };
            if let Some(p) = projection {
                for value in [
                    1,
                    p.segment as u64,
                    p.position_m.to_bits(),
                    p.offset_m.to_bits(),
                    p.point.latitude_deg.to_bits(),
                    p.point.longitude_deg.to_bits(),
                ] {
                    checksum = checksum.wrapping_mul(0x0000_0100_0000_01b3) ^ value;
                }
            } else {
                checksum = checksum.wrapping_mul(0x0000_0100_0000_01b3);
            }
        }
        black_box(checksum)
    };
    black_box(run());
    for _ in 0..7 {
        let started = Instant::now();
        let checksum = run();
        println!(
            "{scenario},{count},{queries},{},{checksum:016x}",
            started.elapsed().as_nanos()
        );
    }
    Ok(())
}
