//! Isolated CCH experiment on directed edge-state graphs exported from GraphHopper.
use cch::{Cch, CchBundle, ElimTreeQuery, INF_WEIGHT, MetricBundle, PathQuery, inertial_order};
use std::{fs, path::Path, time::Instant};

use imu_nav_cch_prototype::{Result, read_input};

fn main() -> Result<()> {
    let args: Vec<_> = std::env::args().collect();
    if args.len() != 4 {
        return Err("build|measure INPUT_DIR OUTPUT_DIR_OR_CSV".into());
    }
    let input = read_input(&Path::new(&args[2]).join("graph.bin"))?;
    let graph = &input.graph;
    if args[1] == "build" {
        fs::create_dir(&args[3])?;
        let mut tail = Vec::with_capacity(graph.head.len());
        for node in 0..graph.node_count() {
            tail.extend(std::iter::repeat_n(
                node as u32,
                (graph.first_out[node + 1] - graph.first_out[node]) as usize,
            ));
        }
        let start = Instant::now();
        let order = inertial_order(
            graph.node_count() as u32,
            &tail,
            &graph.head,
            &input.lat,
            &input.lon,
        );
        let order_s = start.elapsed().as_secs_f64();
        println!("order_s={order_s:.3}");
        let start = Instant::now();
        let hierarchy = Cch::build(graph, &order);
        let build_s = start.elapsed().as_secs_f64();
        println!("build_s={build_s:.3}");
        let start = Instant::now();
        let metric = hierarchy.customize(&graph.weight);
        let customize_s = start.elapsed().as_secs_f64();
        println!("customize_s={customize_s:.3}");
        hierarchy.save_struct(&Path::new(&args[3]).join("structure.cch"))?;
        metric.save(&Path::new(&args[3]).join("metric.cch"))?;
        fs::write(
            Path::new(&args[3]).join("timing.csv"),
            format!(
                "states,arcs,order_s,build_s,customize_s\n{},{},{order_s},{build_s},{customize_s}\n",
                graph.node_count(),
                graph.arc_count()
            ),
        )?;
        return Ok(());
    }
    if args[1] != "measure" {
        return Err("Unknown command".into());
    }
    let hierarchy = CchBundle::open(&Path::new(&args[2]).join("index/structure.cch"))?;
    let metric = MetricBundle::open(&Path::new(&args[2]).join("index/metric.cch"))?;
    let view = hierarchy.view();
    let weights = metric.view();
    let mut path_query = PathQuery::new(&view);
    let contents = fs::read_to_string(Path::new(&args[2]).join("queries.csv"))?;
    let mut endpoints = Vec::new();
    for line in contents.lines().skip(1) {
        let row: Vec<u32> = line
            .split(',')
            .map(str::parse)
            .collect::<std::result::Result<_, _>>()?;
        let sources: Vec<_> = input
            .from
            .iter()
            .enumerate()
            .filter(|(_, n)| **n == row[1])
            .map(|(i, _)| i as u32)
            .collect();
        let targets: Vec<_> = input
            .to
            .iter()
            .enumerate()
            .filter(|(_, n)| **n == row[2])
            .map(|(i, _)| i as u32)
            .collect();
        endpoints.push((row[0], row[1] == row[2], sources, targets));
    }
    let mut rows = String::from("query,iteration,ms,weight,edges,found\n");
    for iteration in 0..6 {
        for (id, same, sources, targets) in &endpoints {
            let start = Instant::now();
            let mut best = INF_WEIGHT;
            let mut pair = None;
            if *same {
                best = 0;
            } else if !targets.is_empty() {
                let mut query = ElimTreeQuery::new(&view);
                query.pin_targets(targets);
                let mut distances = vec![INF_WEIGHT; targets.len()];
                for &source in sources {
                    query.reset_source();
                    query.add_source_and_run(&weights, source);
                    query.get_distances_to_targets(&mut distances);
                    for (&target, &distance) in targets.iter().zip(&distances) {
                        let value = distance.saturating_add(input.initial[source as usize]);
                        if value < best {
                            best = value;
                            pair = Some((source, target));
                        }
                    }
                }
            }
            let path = if let Some((source, target)) = pair {
                path_query
                    .path(&weights, source, target)
                    .ok_or("Missing path")?
            } else {
                vec![]
            };
            let elapsed = start.elapsed().as_secs_f64() * 1000.;
            // Verify every reconstructed transition and its weight against the original directed graph.
            if !path.is_empty() {
                let mut cost = input.initial[path[0] as usize] as u64;
                for step in path.windows(2) {
                    let from = step[0] as usize;
                    let weight = (graph.first_out[from]..graph.first_out[from + 1])
                        .filter(|&arc| graph.head[arc as usize] == step[1])
                        .map(|arc| graph.weight[arc as usize])
                        .min()
                        .ok_or("Illegal transition in path")?;
                    if input.to[from] != input.from[step[1] as usize] {
                        return Err("Disconnected path".into());
                    }
                    cost += u64::from(weight);
                }
                if cost != u64::from(best) {
                    return Err(format!("Path cost mismatch {cost} vs {best}").into());
                }
            }
            rows.push_str(&format!(
                "{id},{iteration},{elapsed:.6},{:.6},{},{}\n",
                f64::from(best) / 1000.,
                path.len(),
                best < INF_WEIGHT
            ));
        }
    }
    fs::write(&args[3], rows)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use cch::graph::Graph;
    #[test]
    fn one_way_and_unreachable_routes_stay_directed() {
        let graph = Graph {
            first_out: vec![0, 1, 2, 2],
            head: vec![1, 2],
            weight: vec![4, 7],
        };
        let hierarchy = Cch::build(&graph, &[0, 1, 2]);
        let metric = hierarchy.customize(&graph.weight);
        assert_eq!(cch::distance(&hierarchy.view(), &metric.view(), 0, 2), 11);
        assert_eq!(
            cch::distance(&hierarchy.view(), &metric.view(), 2, 0),
            INF_WEIGHT
        );
        assert_eq!(
            cch::node_path(&hierarchy.view(), &metric.view(), 0, 2),
            Some(vec![0, 1, 2])
        );
    }
    #[test]
    fn absent_turn_transition_cannot_be_reintroduced_by_shortcuts() {
        // States are directed road edges. The forbidden direct turn 0 -> 3 is absent.
        let graph = Graph {
            first_out: vec![0, 1, 2, 3, 3],
            head: vec![1, 2, 3],
            weight: vec![10, 20, 30],
        };
        let hierarchy = Cch::build(&graph, &[1, 2, 0, 3]);
        let metric = hierarchy.customize(&graph.weight);
        assert_eq!(cch::distance(&hierarchy.view(), &metric.view(), 0, 3), 60);
        assert_eq!(
            cch::node_path(&hierarchy.view(), &metric.view(), 0, 3),
            Some(vec![0, 1, 2, 3])
        );
    }
}
