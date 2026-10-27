//! Serialize the public `faster_paths` hierarchy into the bounded, mmap-friendly app format.
// Coordinates, grid cells, speeds and file indices have explicit bounds in the importer/loader.
// Integer-to-float conversions here stay below the exact-integer range of f64.
#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss
)]
use super::{Road, Variant};
use crate::{
    Result, geo,
    storage::{NONE, cell_key, cell_of},
};
use faster_paths::{
    contraction_hierarchy::ContractionHierarchy,
    graph::{GraphLike, WeightedEdge},
    types::Vertex,
};
use std::{
    collections::{HashMap, hash_map::Entry},
    fs::File,
    io::{BufReader, BufWriter, Read, Seek, SeekFrom, Write},
    path::Path,
};
pub fn stage(
    path: &Path,
    roads: &[Road],
    variants: &[Variant],
    order: &[usize],
    edges: &[WeightedEdge<u64>],
    node_based: bool,
) -> Result<()> {
    let nodes = node_ids(roads);
    let mut names = Vec::new();
    let mut name_offsets = HashMap::new();
    let mut point_count = 0u64;
    for &old in order {
        let road = &roads[variants[old].physical as usize];
        point_count += road.geometry.len() as u64;
        if let Entry::Vacant(entry) = name_offsets.entry(road.name.as_str()) {
            let offset = u32::try_from(names.len())?;
            entry.insert(offset);
            names.extend_from_slice(road.name.as_bytes());
        }
    }
    if point_count > u64::from(u32::MAX) {
        return Err("too many geometry points".into());
    }
    let originals: HashMap<_, _> = order
        .iter()
        .enumerate()
        .filter(|(_, old)| variants[**old].initial)
        .map(|(id, &old)| {
            let road = &roads[variants[old].physical as usize];
            ((road.from, road.to, road.way), id as u32)
        })
        .collect();
    let mut out = BufWriter::new(File::create(path)?);
    out.write_all(b"IMUST001")?;
    let graph_count = if node_based { nodes.len() } else { order.len() };
    for count in [
        order.len() as u64,
        point_count,
        names.len() as u64,
        0,
        0,
        graph_count as u64,
        u64::from(node_based),
    ] {
        out.write_all(&count.to_le_bytes())?;
    }
    let mut point_offset = 0u32;
    for &old in order {
        let road = &roads[variants[old].physical as usize];
        for value in [
            nodes[&road.from],
            nodes[&road.to],
            originals
                .get(&(road.to, road.from, road.way))
                .copied()
                .unwrap_or(NONE),
            point_offset,
            road.geometry.len() as u32,
            name_offsets[road.name.as_str()],
            road.name.len() as u32,
            road.speed.to_bits(),
        ] {
            out.write_all(&value.to_le_bytes())?;
        }
        out.write_all(&road.maxspeed.to_le_bytes())?;
        out.write_all(
            &(road.flags
                | if variants[old].initial { 0 } else { 16 }
                | if variants[old].local_exit {
                    crate::storage::LOCAL_EXIT
                } else {
                    0
                })
            .to_le_bytes(),
        )?;
        out.write_all(&0u32.to_le_bytes())?;
        out.write_all(&road.weight.to_le_bytes())?;
        out.write_all(
            &((road.distance / f64::from(road.speed) * 1000.).round() as u64).to_le_bytes(),
        )?;
        out.write_all(&road.distance.to_le_bytes())?;
        point_offset += road.geometry.len() as u32;
    }
    for &old in order {
        for point in &roads[variants[old].physical as usize].geometry {
            for value in [point.lat, point.lon, point.elevation] {
                out.write_all(&value.to_le_bytes())?;
            }
        }
    }
    out.write_all(&names)?;
    out.flush()?;
    let mut out = BufWriter::new(File::create(path.with_extension("snap"))?);
    let grid = snap_grid(roads, variants, order);
    for (key, id) in grid {
        out.write_all(&key.to_le_bytes())?;
        out.write_all(&id.to_le_bytes())?;
    }
    out.flush()?;
    let mut edges_out = BufWriter::new(File::create(path.with_extension("transitions"))?);
    for edge in edges {
        edges_out.write_all(&edge.tail.as_u32().to_le_bytes())?;
        edges_out.write_all(&edge.head.as_u32().to_le_bytes())?;
        edges_out.write_all(&edge.weight.to_le_bytes())?;
    }
    edges_out.flush()?;
    Ok(())
}

/// Stable compact road-node identifiers also used by the walking hierarchy.
pub fn node_ids(roads: &[Road]) -> HashMap<i64, u32> {
    let mut ids: Vec<_> = roads.iter().flat_map(|road| [road.from, road.to]).collect();
    ids.sort_unstable();
    ids.dedup();
    ids.into_iter()
        .enumerate()
        .map(|(index, id)| (id, index as u32))
        .collect()
}

fn snap_grid(roads: &[Road], variants: &[Variant], order: &[usize]) -> Vec<(u64, u32)> {
    let mut grid = vec![];
    for (id, &old) in order.iter().enumerate() {
        let road = &roads[variants[old].physical as usize];
        for pair in road.geometry.windows(2) {
            let count = ((pair[1].lat - pair[0].lat)
                .abs()
                .max(geo::longitude(pair[1].lon - pair[0].lon).abs())
                / 0.01)
                .ceil()
                .max(1.) as usize;
            for sample in 0..=count {
                let point = geo::interpolate(pair[0], pair[1], sample as f64 / count as f64);
                let (lat, lon) = cell_of(point);
                grid.push((cell_key(lat, lon), id as u32));
            }
        }
    }
    grid.sort_unstable();
    grid.dedup();
    grid
}

/// Preprocess after all OSM objects and road geometry have been released, keeping CI peak RAM bounded.
pub fn contract(path: &Path) -> Result<()> {
    let mut input = BufReader::new(File::open(path.with_extension("transitions"))?);
    let length = usize::try_from(input.get_ref().metadata()?.len())?;
    if !length.is_multiple_of(16) {
        return Err("invalid staged transitions".into());
    }
    let mut edges = Vec::with_capacity(length / 16);
    let mut record = [0u8; 16];
    for _ in 0..length / 16 {
        input.read_exact(&mut record)?;
        edges.push(WeightedEdge {
            tail: Vertex::new(u32::from_le_bytes(record[..4].try_into()?)),
            head: Vertex::new(u32::from_le_bytes(record[4..8].try_into()?)),
            weight: u64::from_le_bytes(record[8..].try_into()?),
        });
    }
    let hierarchy = faster_paths::contraction_hierarchy::contract_graph_parallel(&edges);
    drop(edges);
    append_hierarchy(path, &hierarchy)?;
    std::fs::remove_file(path.with_extension("transitions"))?;
    std::fs::remove_file(path.with_extension("snap"))?;
    Ok(())
}
fn append_hierarchy(path: &Path, hierarchy: &ContractionHierarchy<u64>) -> Result<()> {
    let mut file = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open(path)?;
    file.seek(SeekFrom::Start(48))?;
    let mut bytes = [0; 8];
    file.read_exact(&mut bytes)?;
    let graph_count = usize::try_from(u64::from_le_bytes(bytes))?;
    let graphs = [hierarchy.up_graph(), hierarchy.down_graph()];
    file.seek(SeekFrom::Start(32))?;
    for graph in graphs {
        file.write_all(&(graph.num_edges() as u64).to_le_bytes())?;
    }
    file.seek(SeekFrom::End(0))?;
    let mut out = BufWriter::new(file);
    for graph in graphs {
        let mut offset = 0u64;
        out.write_all(&offset.to_le_bytes())?;
        for id in 0..graph_count {
            if id < graph.num_vertices() {
                offset += graph.outgoing_edges(Vertex::new(id as u32)).len() as u64;
            }
            out.write_all(&offset.to_le_bytes())?;
        }
    }
    for graph in graphs {
        for id in 0..graph.num_vertices() {
            for edge in graph.outgoing_edges(Vertex::new(id as u32)) {
                out.write_all(&edge.head.as_u32().to_le_bytes())?;
                out.write_all(&edge.skipped.map_or(NONE, Vertex::as_u32).to_le_bytes())?;
                out.write_all(&edge.weight.to_le_bytes())?;
            }
        }
    }
    std::io::copy(&mut File::open(path.with_extension("snap"))?, &mut out)?;
    out.flush()?;
    Ok(())
}
