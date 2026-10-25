//! Parallel CH benchmark using the same directed turn-state export and rounding as the CCH trial.
use faster_paths::{
    contraction_hierarchy::{
        ContractionHierarchy, ContractionHierarchyPathfinder, contract_graph_parallel,
    },
    graph::WeightedEdge,
    path::Query,
    pathfinder::ShortestPathFinder,
    types::Vertex,
};
use imu_nav_cch_prototype::{Result, read_input};
use std::{
    fs::{self, File},
    io::{BufReader, BufWriter, Write},
    path::Path,
    time::Instant,
};

fn main() -> Result<()> {
    let args: Vec<_> = std::env::args().collect();
    if args.len() != 4 {
        return Err("build|measure INPUT_DIR OUTPUT_DIR_OR_CSV".into());
    }
    let input = read_input(&Path::new(&args[2]).join("graph.bin"))?;
    let graph = &input.graph;
    if args[1] == "build" {
        fs::create_dir(&args[3])?;
        let mut edges = Vec::with_capacity(graph.arc_count());
        for node in 0..graph.node_count() {
            for arc in graph.first_out[node]..graph.first_out[node + 1] {
                edges.push(WeightedEdge {
                    tail: Vertex::new(node as u32),
                    head: Vertex::new(graph.head[arc as usize]),
                    weight: u64::from(graph.weight[arc as usize]),
                });
            }
        }
        let start = Instant::now();
        let hierarchy = contract_graph_parallel(&edges);
        let seconds = start.elapsed().as_secs_f64();
        println!("contract_s={seconds:.3}");
        let mut writer = BufWriter::new(File::create(Path::new(&args[3]).join("hierarchy.bin"))?);
        bincode::serialize_into(&mut writer, &hierarchy)?;
        writer.flush()?;
        fs::write(
            Path::new(&args[3]).join("timing.csv"),
            format!(
                "states,arcs,contract_s\n{},{},{seconds}\n",
                graph.node_count(),
                graph.arc_count()
            ),
        )?;
        return Ok(());
    }
    if args[1] != "measure" {
        return Err("Unknown mode".into());
    }
    // Only our locally generated index is accepted in this experiment, not imported user archives.
    let hierarchy: ContractionHierarchy<u64> = bincode::deserialize_from(BufReader::new(
        File::open(Path::new(&args[2]).join("faster-index/hierarchy.bin"))?,
    ))?;
    let mut finder = ContractionHierarchyPathfinder::new(&hierarchy);
    let mut endpoints = vec![];
    for line in fs::read_to_string(Path::new(&args[2]).join("queries.csv"))?
        .lines()
        .skip(1)
    {
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
            let mut best = None;
            let mut best_pair = None;
            if *same {
                best = Some(0);
            } else {
                for &source in sources {
                    for &target in targets {
                        // Trailing isolated states may not occur in the edge-list-based CH. A direct road edge
                        // still has a valid zero-transition path, even when its state is outside the index.
                        let distance = if source == target {
                            Some(0)
                        } else if source as usize >= hierarchy.num_vertices()
                            || target as usize >= hierarchy.num_vertices()
                        {
                            None
                        } else {
                            finder.distance(&Query {
                                source: Vertex::new(source),
                                target: Vertex::new(target),
                            })
                        };
                        if let Some(distance) = distance {
                            let cost = distance
                                .checked_add(u64::from(input.initial[source as usize]))
                                .ok_or("Cost overflow")?;
                            if best.is_none_or(|old| cost < old) {
                                best = Some(cost);
                                best_pair = Some((source, target));
                            }
                        }
                    }
                }
            }
            let path = if let Some((source, target)) = best_pair {
                if source == target {
                    vec![source]
                } else {
                    finder
                        .path(&Query {
                            source: Vertex::new(source),
                            target: Vertex::new(target),
                        })
                        .ok_or("Missing path")?
                        .vertices
                        .into_iter()
                        .map(Vertex::as_u32)
                        .collect()
                }
            } else {
                vec![]
            };
            let elapsed = start.elapsed().as_secs_f64() * 1000.;
            // Independent path validation is excluded from the latency measurement.
            if let Some((source, target)) = best_pair {
                if path.first() != Some(&source) || path.last() != Some(&target) {
                    return Err("Wrong path endpoints".into());
                }
                let mut total = u64::from(input.initial[source as usize]);
                for pair in path.windows(2) {
                    let node = pair[0] as usize;
                    let weight = (graph.first_out[node]..graph.first_out[node + 1])
                        .filter(|&arc| graph.head[arc as usize] == pair[1])
                        .map(|arc| graph.weight[arc as usize])
                        .min()
                        .ok_or("Illegal transition")?;
                    if input.to[node] != input.from[pair[1] as usize] {
                        return Err("Disconnected path".into());
                    }
                    total += u64::from(weight);
                }
                if Some(total) != best {
                    return Err(format!("Path cost differs from query: {total} vs {best:?}").into());
                }
            }
            rows.push_str(&format!(
                "{id},{iteration},{elapsed:.6},{:.6},{},{}\n",
                best.unwrap_or(u64::MAX) as f64 / 1000.,
                path.len(),
                best.is_some()
            ));
        }
    }
    fs::write(&args[3], rows)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn directed_forbidden_and_unreachable_paths() {
        let edges = vec![
            WeightedEdge {
                tail: Vertex::new(0),
                head: Vertex::new(1),
                weight: 10u64,
            },
            WeightedEdge {
                tail: Vertex::new(1),
                head: Vertex::new(2),
                weight: 20,
            },
            WeightedEdge {
                tail: Vertex::new(2),
                head: Vertex::new(3),
                weight: 30,
            },
        ];
        let hierarchy = contract_graph_parallel(&edges);
        let mut query = ContractionHierarchyPathfinder::new(&hierarchy);
        assert_eq!(
            query.distance(&Query {
                source: Vertex::new(0),
                target: Vertex::new(3)
            }),
            Some(60)
        );
        assert_eq!(
            query.distance(&Query {
                source: Vertex::new(3),
                target: Vertex::new(0)
            }),
            None
        );
        assert_eq!(
            query
                .path(&Query {
                    source: Vertex::new(0),
                    target: Vertex::new(3)
                })
                .unwrap()
                .vertices
                .iter()
                .map(|v| v.as_u32())
                .collect::<Vec<_>>(),
            vec![0, 1, 2, 3]
        );
    }

    /// Check shortcuts and serialization against an independent all-pairs distance oracle.
    #[test]
    fn serialized_hierarchy_matches_all_pairs_oracle() {
        const COUNT: usize = 24;
        const UNREACHABLE: u64 = u64::MAX / 4;
        let mut costs = vec![vec![UNREACHABLE; COUNT]; COUNT];
        let mut edges = vec![];
        for (source, row) in costs.iter_mut().enumerate() {
            row[source] = 0;
            for (target, cost) in row.iter_mut().enumerate() {
                // Two disconnected, asymmetric components exercise unreachable and shortcut cases.
                if source != target
                    && source / 12 == target / 12
                    && (source * 7 + target * 13) % 5 < 2
                {
                    let weight = ((source * 31 + target * 17) % 97 + 1) as u64;
                    *cost = weight;
                    edges.push(WeightedEdge {
                        tail: Vertex::new(source as u32),
                        head: Vertex::new(target as u32),
                        weight,
                    });
                }
            }
        }
        for via in 0..COUNT {
            for source in 0..COUNT {
                for target in 0..COUNT {
                    costs[source][target] =
                        costs[source][target].min(costs[source][via] + costs[via][target]);
                }
            }
        }
        let hierarchy = contract_graph_parallel(&edges);
        let encoded = bincode::serialize(&hierarchy).unwrap();
        let restored: ContractionHierarchy<u64> = bincode::deserialize(&encoded).unwrap();
        let mut finder = ContractionHierarchyPathfinder::new(&restored);
        for (source, row) in costs.iter().enumerate() {
            for (target, &expected) in row.iter().enumerate() {
                let query = Query {
                    source: Vertex::new(source as u32),
                    target: Vertex::new(target as u32),
                };
                assert_eq!(
                    finder.distance(&query),
                    (expected != UNREACHABLE).then_some(expected)
                );
                if let Some(path) = finder.path(&query) {
                    let actual: u64 = path
                        .vertices
                        .windows(2)
                        .map(|pair| {
                            edges
                                .iter()
                                .filter(|edge| edge.tail == pair[0] && edge.head == pair[1])
                                .map(|edge| edge.weight)
                                .min()
                                .expect("Only original edges in unpacked paths")
                        })
                        .sum();
                    assert_eq!(actual, expected);
                } else {
                    assert_eq!(expected, UNREACHABLE);
                }
            }
        }
    }
}
