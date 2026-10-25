//! Viterbi matching chooses a consistent road sequence using GPS emissions and routed transition lengths.
// Coordinates, grid cells, speeds and file indices have explicit bounds in the importer/loader.
// Integer-to-float conversions here stay below the exact-integer range of f64.
#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss
)]
use crate::{
    Result, check,
    geo::{self, Point},
    query,
    route::{Route, append},
    storage::Network,
};
use serde::Deserialize;
use std::sync::atomic::AtomicBool;
#[derive(Deserialize)]
pub struct Request {
    pub points: Vec<[f64; 2]>,
    pub accuracy: f64,
}
/// # Errors
/// Returns malformed input, unavailable graph data, I/O, or request cancellation errors.
pub fn match_track(network: &Network, request: &Request, cancel: &AtomicBool) -> Result<Route> {
    if !(2..=10000).contains(&request.points.len())
        || !request.accuracy.is_finite()
        || !(1.0..=1000.).contains(&request.accuracy)
    {
        return Err("invalid matching request".into());
    }
    let observations: Vec<_> = request
        .points
        .iter()
        .map(|p| Point {
            lat: p[0],
            lon: p[1],
            elevation: 0.,
        })
        .collect();
    let mut candidates = Vec::with_capacity(observations.len());
    for &point in &observations {
        let mut snaps = query::candidates(network, point, request.accuracy * 3., cancel)?;
        if candidates.is_empty() {
            snaps.retain(|s| network.state(s.state).flags & 16 == 0);
        }
        if snaps.is_empty() {
            return Err("no matching candidates".into());
        }
        candidates.push(snaps);
    }
    let mut costs: Vec<_> = candidates[0]
        .iter()
        .map(|s| s.distance.powi(2) / (2. * request.accuracy.powi(2)))
        .collect();
    let mut parents = vec![];
    for index in 1..candidates.len() {
        check(cancel)?;
        let straight = geo::distance(observations[index - 1], observations[index]);
        let mut next_costs = vec![f64::INFINITY; candidates[index].len()];
        let mut previous = vec![0; candidates[index].len()];
        for (target_id, target) in candidates[index].iter().enumerate() {
            for (source_id, source) in candidates[index - 1].iter().enumerate() {
                if !costs[source_id].is_finite() {
                    continue;
                }
                let answer = query::between(
                    network,
                    std::slice::from_ref(source),
                    std::slice::from_ref(target),
                    cancel,
                );
                check(cancel)?;
                if let Ok((path, _, _)) = answer {
                    let distance = path
                        .iter()
                        .map(|&id| network.state(id).distance)
                        .sum::<f64>()
                        - source.fraction * network.state(source.state).distance
                        - (1. - target.fraction) * network.state(target.state).distance;
                    let cost = costs[source_id]
                        + target.distance.powi(2) / (2. * request.accuracy.powi(2))
                        + (distance - straight).abs() / request.accuracy.max(50.);
                    if cost < next_costs[target_id] {
                        next_costs[target_id] = cost;
                        previous[target_id] = source_id;
                    }
                }
            }
        }
        if !next_costs.iter().any(|c| c.is_finite()) {
            return Err("track has no connected road match".into());
        }
        costs = next_costs;
        parents.push(previous);
    }
    let mut selected = vec![0; candidates.len()];
    selected[candidates.len() - 1] = costs
        .iter()
        .enumerate()
        .min_by(|a, b| a.1.total_cmp(b.1))
        .ok_or("no matching candidates")?
        .0;
    for index in (1..candidates.len()).rev() {
        selected[index - 1] = parents[index - 1][selected[index]];
    }
    let mut result = Route {
        elevation_m: Some(vec![]),
        ..Route::default()
    };
    for index in 1..candidates.len() {
        let source = &candidates[index - 1][selected[index - 1]];
        let target = &candidates[index][selected[index]];
        let (path, _, _) = query::between(
            network,
            std::slice::from_ref(source),
            std::slice::from_ref(target),
            cancel,
        )?;
        append(network, &mut result, &path, source, target, cancel)?;
    }
    result.lengths();
    Ok(result)
}
