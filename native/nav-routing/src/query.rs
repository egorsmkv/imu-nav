//! Sparse CH search and road-segment snapping, cancellable without allocating region-sized scratch.
// Coordinates, grid cells, speeds and file indices have explicit bounds in the importer/loader.
// Integer-to-float conversions here stay below the exact-integer range of f64.
#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss
)]
use crate::ids::{ArcIndex, Direction, NodeId, StateId, VertexId};
use crate::{
    Result, check,
    geo::{self, Point},
    storage::{Network, cell_key, cell_of},
};
use std::{
    cmp::Reverse,
    collections::{BinaryHeap, HashMap, HashSet},
    sync::atomic::AtomicBool,
};
#[derive(Clone, Debug)]
pub struct Snap {
    pub state: StateId,
    pub fraction: f64,
    pub point: Point,
    pub distance: f64,
}
/// Search only nearby road segments, retaining both legal directions of the nearest physical road.
/// # Errors
/// Returns malformed input, unavailable graph data, I/O, or request cancellation errors.
pub fn snap(network: &Network, point: Point, cancel: &AtomicBool) -> Result<Vec<Snap>> {
    candidates(network, point, 0.05, cancel)
}
/// Track matching retains plausible parallel-road alternatives within the GPS uncertainty.
/// # Errors
/// Returns malformed input, unavailable graph data, I/O, or request cancellation errors.
pub fn candidates(
    network: &Network,
    point: Point,
    slack: f64,
    cancel: &AtomicBool,
) -> Result<Vec<Snap>> {
    if !geo::valid(point) {
        return Err("invalid route coordinates".into());
    }
    let (cy, cx) = cell_of(point);
    let rows = 4;
    let columns = ((5000. / (2223. * point.lat.to_radians().cos().abs().max(0.001))).ceil() as i32
        + 1)
    .min(9000);
    let mut seen = HashSet::new();
    let mut best = Vec::new();
    let mut nearest = 5000.;
    for dy in -rows..=rows {
        check(cancel)?;
        for dx in -columns..=columns {
            if !(0..9000).contains(&(cy + dy)) {
                continue;
            }
            for id in network.cell(cell_key(cy + dy, cx + dx)) {
                if !seen.insert(id) {
                    continue;
                }
                let state = network.state(id);
                let mut traversed = 0.;
                let mut candidate = None;
                for offset in 1..state.point_count {
                    let a = network.point(state, offset - 1);
                    let b = network.point(state, offset);
                    let (projected, t) = geo::project(point, a, b);
                    let distance = geo::distance(point, projected);
                    let length = geo::distance(a, b);
                    if candidate
                        .as_ref()
                        .is_none_or(|s: &Snap| distance < s.distance)
                    {
                        candidate = Some(Snap {
                            state: id,
                            fraction: if state.distance > 0. {
                                (traversed + t * length) / state.distance
                            } else {
                                0.
                            },
                            point: projected,
                            distance,
                        });
                    }
                    traversed += length;
                }
                if let Some(candidate) = candidate {
                    if candidate.distance < nearest {
                        nearest = candidate.distance;
                        best.retain(|s: &Snap| s.distance <= nearest + slack);
                    }
                    if candidate.distance <= nearest + slack {
                        best.push(candidate);
                    }
                }
            }
        }
    }
    if best.is_empty() {
        return Err("no accessible road within 5 km".into());
    }
    best.sort_by(|a, b| {
        a.distance
            .total_cmp(&b.distance)
            .then(a.state.cmp(&b.state))
    });
    if slack > 0.05 {
        best.truncate(8);
    }
    Ok(best)
}
#[derive(Clone, Copy)]
struct Visit {
    cost: u64,
    parent: Option<(VertexId, ArcIndex)>,
}
/// Bidirectional upward search; a request's sparse maps also own shortcut predecessor chains.
/// # Errors
/// Returns malformed input, unavailable graph data, I/O, or request cancellation errors.
pub fn path(
    network: &Network,
    source: VertexId,
    target: VertexId,
    cancel: &AtomicBool,
) -> Result<Option<(u64, Vec<VertexId>)>> {
    check(cancel)?;
    if source.get() as usize >= network.graph_count || target.get() as usize >= network.graph_count
    {
        return Err("invalid query vertex".into());
    }
    if source == target {
        return Ok(Some((0, vec![source])));
    }
    let mut states: [HashMap<VertexId, Visit>; 2] = [HashMap::new(), HashMap::new()];
    let mut queue = BinaryHeap::new();
    for (dir, node) in [(Direction::Forward, source), (Direction::Backward, target)] {
        states[dir.index()].insert(
            node,
            Visit {
                cost: 0,
                parent: None,
            },
        );
        queue.push(Reverse((0u64, dir, node)));
    }
    let mut best = None;
    let mut processed = 0usize;
    while let Some(Reverse((cost, dir, node))) = queue.pop() {
        processed += 1;
        if processed.is_multiple_of(256) {
            check(cancel)?;
        }
        if processed > 2_000_000 {
            return Err("route search limit exceeded".into());
        }
        if best.is_some_and(|(value, _)| cost > value) {
            break;
        }
        if states[dir.index()][&node].cost != cost {
            continue;
        }
        if let Some(other) = states[dir.opposite().index()].get(&node) {
            let total = cost.checked_add(other.cost).ok_or("route cost overflow")?;
            if best.is_none_or(|(value, _)| total < value) {
                best = Some((total, node));
            }
        }
        for index in network.outgoing(dir, node) {
            let arc = network.arc(dir, index);
            let distance = cost.checked_add(arc.weight).ok_or("route cost overflow")?;
            if states[dir.index()]
                .get(&arc.head)
                .is_none_or(|v| distance < v.cost)
            {
                states[dir.index()].insert(
                    arc.head,
                    Visit {
                        cost: distance,
                        parent: Some((node, index)),
                    },
                );
                queue.push(Reverse((distance, dir, arc.head)));
            }
        }
    }
    let Some((cost, meeting)) = best else {
        return Ok(None);
    };
    let mut shortcuts = vec![];
    let mut node = meeting;
    while let Some((tail, index)) = states[0][&node].parent {
        shortcuts.push((Direction::Forward, tail, index));
        node = tail;
    }
    shortcuts.reverse();
    node = meeting;
    while let Some((tail, index)) = states[1][&node].parent {
        shortcuts.push((Direction::Backward, tail, index));
        node = tail;
    }
    let result = unpack(network, shortcuts, source, target, cancel)?;
    Ok(Some((cost, result)))
}
/// Choose legal directed endpoint states, including a cycle when travelling backwards on a one-way edge.
/// # Errors
/// Returns malformed input, unavailable graph data, I/O, or request cancellation errors.
pub fn between(
    network: &Network,
    starts: &[Snap],
    ends: &[Snap],
    cancel: &AtomicBool,
) -> Result<(Vec<StateId>, Snap, Snap)> {
    let mut best: Option<(f64, Vec<StateId>, Snap, Snap)> = None;
    for source in starts {
        for target in ends {
            check(cancel)?;
            let answer = if network.node_based {
                walking_path(network, source, target, cancel)?
            } else if source.state == target.state && source.fraction > target.fraction {
                let state = network.state(source.state);
                let mut cycle = None;
                // States are sorted by their start node; no region-sized adjacency map is needed.
                for next in network.from_node(state.to) {
                    if next == source.state {
                        continue;
                    }
                    if let (Some((left, mut a)), Some((right, b))) = (
                        state_path(network, source.state, next, cancel)?,
                        state_path(network, next, target.state, cancel)?,
                    ) {
                        let cost = left + right;
                        if cycle
                            .as_ref()
                            .is_none_or(|(old, _): &(u64, Vec<StateId>)| cost < *old)
                        {
                            a.extend_from_slice(&b[1..]);
                            cycle = Some((cost, a));
                        }
                    }
                }
                cycle
            } else {
                state_path(network, source.state, target.state, cancel)?
            };
            if let Some((distance, states)) = answer {
                let cost = distance as f64
                    + (1. - source.fraction) * network.state(source.state).weight as f64
                    - (1. - target.fraction) * network.state(target.state).weight as f64;
                if best.as_ref().is_none_or(|(old, _, _, _)| cost < *old) {
                    best = Some((cost, states, source.clone(), target.clone()));
                }
            }
        }
    }
    best.map(|(_, p, s, t)| (p, s, t))
        .ok_or("no road connection".into())
}

/// Walking CH vertices are road junctions. Convert the winning node path back to directed roads.
fn walking_path(
    network: &Network,
    source: &Snap,
    target: &Snap,
    cancel: &AtomicBool,
) -> Result<Option<(u64, Vec<StateId>)>> {
    if source.state == target.state && source.fraction <= target.fraction {
        return Ok(Some((0, vec![source.state])));
    }
    let source_road = network.state(source.state);
    let target_road = network.state(target.state);
    let Some((cost, nodes)) = path(
        network,
        VertexId::new(source_road.to.get()),
        VertexId::new(target_road.from.get()),
        cancel,
    )?
    else {
        return Ok(None);
    };
    let mut states = vec![source.state];
    for pair in nodes.windows(2) {
        let road = network
            .from_node(NodeId::new(pair[0].get()))
            .filter(|&id| network.state(id).to.get() == pair[1].get())
            .min_by_key(|&id| network.state(id).weight)
            .ok_or("missing walking geometry")?;
        states.push(road);
    }
    states.push(target.state);
    Ok(Some((cost + target_road.weight, states)))
}

fn unpack(
    network: &Network,
    shortcuts: Vec<(Direction, VertexId, ArcIndex)>,
    source: VertexId,
    target: VertexId,
    cancel: &AtomicBool,
) -> Result<Vec<VertexId>> {
    let mut result = vec![source];
    let mut stack: Vec<_> = shortcuts.into_iter().rev().collect();
    let mut unpacked = 0;
    while let Some((dir, tail, index)) = stack.pop() {
        unpacked += 1;
        if unpacked % 256 == 0 {
            check(cancel)?;
        }
        if unpacked > 2_000_000 {
            return Err("route geometry limit exceeded".into());
        }
        let arc = network.arc(dir, index);
        if let Some(middle) = arc.skipped {
            let left = network
                .outgoing(dir.opposite(), middle)
                .filter(|&i| network.arc(dir.opposite(), i).head == tail)
                .min_by_key(|&i| network.arc(dir.opposite(), i).weight)
                .ok_or("missing shortcut")?;
            let right = network
                .outgoing(dir, middle)
                .filter(|&i| network.arc(dir, i).head == arc.head)
                .min_by_key(|&i| network.arc(dir, i).weight)
                .ok_or("missing shortcut")?;
            let a = (dir.opposite(), middle, left);
            let b = (dir, middle, right);
            if dir == Direction::Forward {
                stack.push(b);
                stack.push(a);
            } else {
                stack.push(a);
                stack.push(b);
            }
        } else {
            result.push(if dir == Direction::Forward {
                arc.head
            } else {
                tail
            });
        }
    }
    if result.last() != Some(&target) {
        return Err("invalid route endpoints".into());
    }
    Ok(result)
}

/// Edge-based profiles number graph vertices by directed road state.
fn state_path(
    network: &Network,
    source: StateId,
    target: StateId,
    cancel: &AtomicBool,
) -> Result<Option<(u64, Vec<StateId>)>> {
    Ok(path(
        network,
        VertexId::new(source.get()),
        VertexId::new(target.get()),
        cancel,
    )?
    .map(|(cost, vertices)| {
        (
            cost,
            vertices
                .into_iter()
                .map(|vertex| StateId::new(vertex.get()))
                .collect(),
        )
    }))
}
