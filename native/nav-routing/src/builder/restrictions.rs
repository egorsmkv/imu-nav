//! Turn restrictions become a finite-state graph, including history-dependent via-way restrictions.
// Coordinates, grid cells, speeds and file indices have explicit bounds in the importer/loader.
// Integer-to-float conversions here stay below the exact-integer range of f64.
#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss
)]
use super::{Road, osm::Tags};
use crate::Result;
use std::collections::{HashMap, HashSet};
#[derive(Clone)]
pub struct Restriction {
    pub members: Vec<(String, i64, String)>,
    pub tags: Tags,
}
/// Expand restriction relations to directed road sequences. Incomplete extract relations are ignored.
pub fn forbidden(
    roads: &[Road],
    outgoing: &HashMap<i64, Vec<u32>>,
    relations: &[Restriction],
) -> Result<Vec<Vec<u32>>> {
    let mut by_way: HashMap<i64, Vec<u32>> = HashMap::new();
    for (index, road) in roads.iter().enumerate() {
        by_way.entry(road.way).or_default().push(index as u32);
    }
    let mut result = Vec::new();
    for relation in relations {
        if relation.tags.get("except").is_some_and(|v| {
            v.split(';')
                .any(|s| matches!(s.trim(), "motorcar" | "motor_vehicle" | "vehicle"))
        }) {
            continue;
        }
        let kind = relation
            .tags
            .get("restriction:motorcar")
            .or_else(|| relation.tags.get("restriction:motor_vehicle"))
            .or_else(|| relation.tags.get("restriction"))
            .or_else(|| relation.tags.get("restriction:conditional"));
        let Some(kind) = kind else { continue };
        let kind = kind.split('@').next().unwrap_or("").trim();
        if !kind.starts_with("no_") && !kind.starts_with("only_") {
            continue;
        }
        let from: Vec<_> = relation
            .members
            .iter()
            .filter(|(_, _, role)| role == "from")
            .map(|(_, id, _)| *id)
            .collect();
        let to: HashSet<_> = relation
            .members
            .iter()
            .filter(|(_, _, role)| role == "to")
            .map(|(_, id, _)| *id)
            .collect();
        let vias: Vec<_> = relation
            .members
            .iter()
            .filter(|(_, _, role)| role == "via")
            .collect();
        let mut sequences = Vec::new();
        for from_way in from {
            for &first in by_way.get(&from_way).into_iter().flatten() {
                if vias.len() == 1 && vias[0].0 == "node" {
                    if roads[first as usize].to != vias[0].1 {
                        continue;
                    }
                    for &last in outgoing
                        .get(&roads[first as usize].to)
                        .into_iter()
                        .flatten()
                    {
                        if !to.contains(&roads[last as usize].way) {
                            continue;
                        }
                        if from_way == roads[last as usize].way {
                            let reverse = roads[first as usize].from == roads[last as usize].to;
                            if kind.ends_with("u_turn") != reverse {
                                continue;
                            }
                        }
                        sequences.push(vec![first, last]);
                    }
                } else if !vias.is_empty() && vias.iter().all(|(kind, _, _)| kind == "way") {
                    let via_ways: Vec<_> = vias.iter().map(|(_, id, _)| *id).collect();
                    sequences.extend(via_paths(roads, outgoing, first, &via_ways, &to)?);
                }
            }
        }
        if kind.starts_with("no_") {
            result.extend(sequences);
        } else {
            // At each entered restriction prefix only the next listed way/edge choices are legal.
            let mut choices: HashMap<Vec<u32>, HashSet<u32>> = HashMap::new();
            for sequence in sequences {
                for end in 1..sequence.len() {
                    choices
                        .entry(sequence[..end].to_vec())
                        .or_default()
                        .insert(sequence[end]);
                }
            }
            for (prefix, allowed) in choices {
                let last = *prefix.last().unwrap();
                for &next in outgoing.get(&roads[last as usize].to).into_iter().flatten() {
                    if !allowed.contains(&next) {
                        let mut blocked = prefix.clone();
                        blocked.push(next);
                        result.push(blocked);
                    }
                }
            }
        }
    }
    Ok(result)
}
/// A small prefix automaton stores only restriction history, not every road in the region.
pub struct Automaton {
    prefixes: Vec<Vec<u32>>,
    lookup: HashMap<Vec<u32>, usize>,
    forbidden: HashSet<Vec<u32>>,
    starts: HashSet<u32>,
}
impl Automaton {
    pub fn new(sequences: Vec<Vec<u32>>) -> Self {
        let mut prefixes = vec![vec![]];
        let mut lookup = HashMap::from([(vec![], 0)]);
        let mut starts = HashSet::new();
        for sequence in &sequences {
            if let Some(&first) = sequence.first() {
                starts.insert(first);
            }
            for end in 1..sequence.len() {
                let prefix = sequence[..end].to_vec();
                if !lookup.contains_key(&prefix) {
                    lookup.insert(prefix.clone(), prefixes.len());
                    prefixes.push(prefix);
                }
            }
        }
        Self {
            prefixes,
            lookup,
            forbidden: sequences.into_iter().collect(),
            starts,
        }
    }
    pub fn advance(&self, context: usize, state: u32) -> Option<usize> {
        if context == 0 && !self.starts.contains(&state) {
            return Some(0);
        }
        let mut sequence = self.prefixes[context].clone();
        sequence.push(state);
        for start in 0..sequence.len() {
            if self.forbidden.contains(&sequence[start..]) {
                return None;
            }
        }
        for start in 0..sequence.len() {
            if let Some(&context) = self.lookup.get(&sequence[start..]) {
                return Some(context);
            }
        }
        Some(0)
    }
}

fn via_paths(
    roads: &[Road],
    outgoing: &HashMap<i64, Vec<u32>>,
    first: u32,
    via_ways: &[i64],
    to: &HashSet<i64>,
) -> Result<Vec<Vec<u32>>> {
    let mut sequences = vec![];
    let mut stack = vec![(vec![first], 0usize)];
    let mut explored = 0;
    while let Some((prefix, stage)) = stack.pop() {
        explored += 1;
        if explored > 100_000 {
            return Err("turn restriction expansion exceeds limit".into());
        }
        let last = *prefix.last().unwrap();
        for &next in outgoing.get(&roads[last as usize].to).into_iter().flatten() {
            if prefix.contains(&next) {
                continue;
            }
            let way = roads[next as usize].way;
            let mut path = prefix.clone();
            path.push(next);
            if stage == via_ways.len() && to.contains(&way) {
                sequences.push(path);
                continue;
            }
            if stage < via_ways.len() && way == via_ways[stage] {
                stack.push((path, stage + 1));
            } else if stage > 0 && way == via_ways[stage - 1] {
                stack.push((path, stage));
            }
        }
    }
    Ok(sequences)
}
