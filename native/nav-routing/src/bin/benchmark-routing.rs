//! Reproducible public-coordinate timings and fixed-vertex path evidence; excludes JNI and networking.
use imu_nav_routing::{
    Result, query,
    route::{Request, route},
    storage::Network,
};
use std::{path::PathBuf, sync::atomic::AtomicBool, time::Instant};
fn main() -> Result<()> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args.len() != 3 {
        return Err("usage: benchmark-routing PACK QUERIES_JSON OUTPUT_JSON".into());
    }
    let requests: Vec<Request> = serde_json::from_slice(&std::fs::read(&args[1])?)?;
    let metadata: serde_json::Value =
        serde_json::from_slice(&std::fs::read(PathBuf::from(&args[0]).join("pack.json"))?)?;
    let mut output = vec![];
    for profile in metadata["profiles"].as_array().ok_or("missing profiles")? {
        let profile = profile.as_str().ok_or("invalid profile")?;
        let started = Instant::now();
        let network = Network::open(&PathBuf::from(&args[0]).join(format!("{profile}.routing")))?;
        let open_ms = started.elapsed().as_secs_f64() * 1000.;
        let cancel = AtomicBool::new(false);
        let mut measurements = vec![];
        for (index, request) in requests.iter().enumerate() {
            let mut samples = vec![];
            let mut result = None;
            let mut failure = None;
            for _ in 0..31 {
                let started = Instant::now();
                match route(&network, request, &cancel) {
                    Ok(route) => {
                        samples.push(started.elapsed().as_secs_f64() * 1000.);
                        result = Some(route);
                    }
                    Err(error) => {
                        failure = Some(error.to_string());
                        break;
                    }
                }
            }
            let first = if samples.is_empty() {
                None
            } else {
                Some(samples.remove(0))
            };
            samples.sort_by(f64::total_cmp);
            measurements.push(serde_json::json!({"query":index,"firstMs":first,"warmMs":samples,"route":result,"error":failure}));
        }
        let mut paths = vec![];
        for index in 0..64usize {
            let source = u32::try_from(index * 7919 % network.graph_count)?;
            let target =
                u32::try_from((index * 104_729 + network.graph_count / 2) % network.graph_count)?;
            let result = query::path(
                &network,
                imu_nav_routing::ids::VertexId::new(source),
                imu_nav_routing::ids::VertexId::new(target),
                &cancel,
            )?;
            paths.push(serde_json::json!({"source":source,"target":target,"result":result}));
        }
        output.push(serde_json::json!({
            "profile": profile,
            "openMs": open_ms,
            "states": network.count,
            "vertices": network.graph_count,
            "queries": measurements,
            "paths": paths,
        }));
    }
    std::fs::write(&args[2], serde_json::to_vec(&output)?)?;
    Ok(())
}
