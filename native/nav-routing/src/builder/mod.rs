//! Desktop-only road import and CH preparation; Android links only the immutable runtime.
// Coordinates, grid cells, speeds and file indices have explicit bounds in the importer/loader.
// Integer-to-float conversions here stay below the exact-integer range of f64.
#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss
)]
pub mod archive;
mod compact;
mod elevation;
pub mod osm;
pub mod profile;
mod restrictions;
mod search;
pub mod subset;
mod write;
use crate::{
    Result,
    geo::{self, Point},
};
use faster_paths::{graph::WeightedEdge, types::Vertex};
use osm::{Object, Tags};
use profile::Mode;
use restrictions::{Automaton, Restriction};
use std::{
    collections::{HashMap, HashSet},
    path::Path,
};
#[derive(Clone)]
pub struct Road {
    pub from: i64,
    pub to: i64,
    pub way: i64,
    pub geometry: Vec<Point>,
    pub name: String,
    pub speed: f32,
    pub maxspeed: u16,
    pub flags: u16,
    pub weight: u64,
    pub distance: f64,
}
struct Way {
    id: i64,
    nodes: Vec<i64>,
    tags: Tags,
}
struct Node {
    point: Point,
    tags: Tags,
}
/// Build both profiles from two streaming passes, preserving the same source for address search.
/// # Errors
/// Returns malformed input, unavailable graph data, I/O, or request cancellation errors.
pub fn build(
    input: &Path,
    output: &Path,
    name: &str,
    min_network: usize,
    with_search: bool,
    addresses: bool,
) -> Result<serde_json::Value> {
    build_with_elevation(
        input,
        output,
        name,
        min_network,
        with_search,
        addresses,
        None,
    )
}
/// Optional elevation uses caller-provided HGT tiles; no network requests occur during import.
/// # Errors
/// Returns malformed input, unavailable graph data, I/O, or request cancellation errors.
pub fn build_with_elevation(
    input: &Path,
    output: &Path,
    name: &str,
    min_network: usize,
    with_search: bool,
    addresses: bool,
    terrain_directory: Option<&Path>,
) -> Result<serde_json::Value> {
    let mut terrain = terrain_directory.map(|path| elevation::Terrain::new(path.to_owned()));
    if output.exists() {
        return Err("output directory already exists".into());
    }
    std::fs::create_dir_all(output)?;
    let Imported {
        ways,
        nodes,
        usage,
        relations,
        mut search,
    } = read_source(input, with_search, addresses)?;
    let mut bounds = [90f64, -90f64, 180f64, -180f64];
    let mut profiles = vec![];
    for mode in [Mode::Car, Mode::Foot] {
        let mut roads = roads_for(&ways, &nodes, &usage, mode, &mut terrain)?;
        if mode == Mode::Foot {
            // Split a blocked node into one terminal per incident road; users can reach either side,
            // but no graph connection crosses the barrier.
            let mut ports = HashMap::new();
            for road in &mut roads {
                let from = road.from;
                let to = road.to;
                for (node, other, endpoint) in
                    [(from, to, &mut road.from), (to, from, &mut road.to)]
                {
                    if nodes
                        .get(&node)
                        .is_some_and(|n| profile::barrier(&n.tags, mode))
                    {
                        let next = i64::MIN + i64::try_from(ports.len())?;
                        *endpoint = *ports.entry((node, other, road.way)).or_insert(next);
                    }
                }
            }
        }
        if roads.len() > 30_000_000 {
            return Err("too many road states".into());
        }
        prune(&mut roads, min_network);
        if roads.is_empty() {
            continue;
        }
        println!(
            "[routing] profile={} directed_roads={}",
            mode.name(),
            roads.len()
        );
        let mut outgoing: HashMap<i64, Vec<u32>> = HashMap::new();
        for (id, road) in roads.iter().enumerate() {
            outgoing.entry(road.from).or_default().push(id as u32);
            for p in &road.geometry {
                bounds[0] = bounds[0].min(p.lat);
                bounds[1] = bounds[1].max(p.lat);
                bounds[2] = bounds[2].min(p.lon);
                bounds[3] = bounds[3].max(p.lon);
            }
        }
        if with_search {
            search.roads(&roads);
        }
        prepare_profile(output, mode, &roads, &nodes, &outgoing, &relations)?;
        profiles.push(mode.name());
        println!("[routing] profile={} staged=true", mode.name());
    }
    if profiles.is_empty() {
        return Err("no routable roads".into());
    }
    drop((ways, nodes, usage, relations, terrain));
    if with_search {
        search.build(&output.join("search.tantivy"))?;
    } else {
        drop(search);
    }
    for profile in &profiles {
        println!("[routing] profile={profile} contracting=true");
        write::contract(&output.join(format!("{profile}.routing")))?;
        println!("[routing] profile={profile} complete=true");
    }
    compact::finish(output, &profiles, terrain_directory.is_some())?;
    let size = directory_size(output)?;
    let info = serde_json::json!({"name":name,"builtAt":time::OffsetDateTime::now_utc().format(&time::format_description::well_known::Rfc3339)?,"source":input.file_name().unwrap_or_default().to_string_lossy(),"routingFormat":crate::FORMAT,"profiles":profiles,"bounds":bounds,"sizeBytes":size,"elevation":terrain_directory.is_some(),"turnCosts":true,"searchFormat":if with_search{Some("tantivy-1")}else{None}});
    std::fs::write(output.join("pack.json"), serde_json::to_vec_pretty(&info)?)?;
    Ok(info)
}
fn directory_size(path: &Path) -> Result<u64> {
    let mut size = 0;
    for entry in std::fs::read_dir(path)? {
        let path = entry?.path();
        size += if path.is_dir() {
            directory_size(&path)?
        } else {
            path.metadata()?.len()
        };
    }
    Ok(size)
}
/// Drop tiny weakly connected islands before choosing snapping candidates.
fn prune(roads: &mut Vec<Road>, minimum: usize) {
    if minimum == 0 {
        return;
    }
    let nodes = write::node_ids(roads);
    let mut parents: Vec<_> = (0..nodes.len()).collect();
    let mut sizes = vec![1usize; nodes.len()];
    for road in roads.iter() {
        let mut left = root(&mut parents, nodes[&road.from] as usize);
        let mut right = root(&mut parents, nodes[&road.to] as usize);
        if left == right {
            continue;
        }
        if sizes[left] < sizes[right] {
            std::mem::swap(&mut left, &mut right);
        }
        parents[right] = left;
        sizes[left] += sizes[right];
    }
    roads.retain(|road| sizes[root(&mut parents, nodes[&road.from] as usize)] >= minimum);
}
/// Path compression keeps connectivity filtering linear without per-node adjacency allocations.
fn root(parents: &mut [usize], mut node: usize) -> usize {
    while parents[node] != node {
        parents[node] = parents[parents[node]];
        node = parents[node];
    }
    node
}

struct Imported {
    ways: Vec<Way>,
    nodes: HashMap<i64, Node>,
    usage: HashMap<i64, usize>,
    relations: Vec<Restriction>,
    search: search::Search,
}
/// Keep only road-node coordinates and address/place records across the two streaming passes.
fn read_source(input: &Path, with_search: bool, addresses: bool) -> Result<Imported> {
    let mut ways = vec![];
    let mut needed = HashSet::new();
    let mut usage: HashMap<i64, usize> = HashMap::new();
    let mut relations = vec![];
    let mut address_ways = vec![];
    osm::read(input, |object| {
        match object {
            Object::Way { id, nodes, tags } => {
                if with_search
                    && addresses
                    && tags.contains_key("addr:housenumber")
                    && tags.contains_key("addr:street")
                    && let Some(&node) = nodes.first()
                {
                    needed.insert(node);
                    address_ways.push((node, tags.clone()));
                }
                if profile::road(&tags) {
                    for &node in &nodes {
                        needed.insert(node);
                        *usage.entry(node).or_default() += 1;
                    }
                    ways.push(Way { id, nodes, tags });
                }
            }
            Object::Relation { members, tags }
                if tags.get("type").is_some_and(|s| s == "restriction") =>
            {
                for (kind, id, role) in &members {
                    if kind == "node" && role == "via" {
                        *usage.entry(*id).or_default() += 2;
                    }
                }
                relations.push(Restriction { members, tags });
            }
            _ => {}
        }
        Ok(())
    })?;
    let mut nodes = HashMap::new();
    let mut search = search::Search::default();
    osm::read(input, |object| {
        if let Object::Node { id, lat, lon, tags } = object {
            let point = Point {
                lat,
                lon,
                elevation: f64::NAN,
            };
            if !geo::valid(point) {
                return Err("invalid OSM coordinate".into());
            }
            if with_search {
                search.node(point, &tags, addresses);
            }
            if needed.contains(&id) {
                if tags.contains_key("barrier")
                    || profile::barrier(&tags, Mode::Car)
                    || profile::barrier(&tags, Mode::Foot)
                {
                    *usage.entry(id).or_default() += 2;
                }
                nodes.insert(id, Node { point, tags });
            }
        }
        Ok(())
    })?;
    if with_search && addresses {
        for (id, tags) in address_ways {
            if let Some(node) = nodes.get(&id) {
                search.node(node.point, &tags, true);
            }
        }
    }
    drop(needed);
    nodes.retain(|id, _| usage.contains_key(id));
    Ok(Imported {
        ways,
        nodes,
        usage,
        relations,
        search,
    })
}

/// Split ways only at topology changes, keeping all intermediate geometry and elevation samples.
fn roads_for(
    ways: &[Way],
    nodes: &HashMap<i64, Node>,
    usage: &HashMap<i64, usize>,
    mode: Mode,
    terrain: &mut Option<elevation::Terrain>,
) -> Result<Vec<Road>> {
    let mut roads = vec![];
    for way in ways {
        let mut cuts = vec![0usize];
        for index in 1..way.nodes.len().saturating_sub(1) {
            if usage.get(&way.nodes[index]).copied().unwrap_or(0) > 1
                || way.nodes.first() == way.nodes.last() && index == way.nodes.len() / 2
            {
                cuts.push(index);
            }
        }
        if way.nodes.len() > 1 {
            cuts.push(way.nodes.len() - 1);
        }
        for pair in cuts.windows(2) {
            let ids = &way.nodes[pair[0]..=pair[1]];
            let geometry: Option<Vec<_>> = ids
                .iter()
                .map(|id| nodes.get(id).map(|n| n.point))
                .collect();
            let Some(mut geometry) = geometry else {
                continue;
            };
            geometry = sample_geometry(geometry, way, terrain)?;
            let distance: f64 = geometry.windows(2).map(|p| geo::distance(p[0], p[1])).sum();
            if distance < 0.01 {
                continue;
            }
            for reverse in [false, true] {
                let Some((speed, limit, priority)) = profile::access(&way.tags, mode, reverse)
                else {
                    continue;
                };
                let mut points = geometry.clone();
                if reverse {
                    points.reverse();
                }
                let (from, to) = if reverse {
                    (*ids.last().unwrap(), ids[0])
                } else {
                    (ids[0], *ids.last().unwrap())
                };
                let weight = ((distance / f64::from(speed) / priority
                    + distance * if mode == Mode::Car { 0.09 } else { 0.07 })
                    * 1000.)
                    .round()
                    .max(1.) as u64;
                let highway = way.tags.get("highway").map_or("", String::as_str);
                let flags = u16::from(way.tags.get("junction").is_some_and(|s| s == "roundabout"))
                    | if highway == "motorway" { 2 } else { 0 }
                    | if highway.ends_with("_link") { 4 } else { 0 }
                    | if way.tags.get("route").is_some_and(|s| s == "ferry") {
                        8
                    } else {
                        0
                    };
                roads.push(Road {
                    from,
                    to,
                    way: way.id,
                    geometry: points,
                    name: way
                        .tags
                        .get("name")
                        .or_else(|| way.tags.get("ref"))
                        .cloned()
                        .unwrap_or_default(),
                    speed,
                    maxspeed: limit,
                    flags,
                    weight,
                    distance,
                });
            }
        }
    }
    Ok(roads)
}

/// Sample long edges every 60 m, interpolating bridges/tunnels between their endpoint heights.
fn sample_geometry(
    geometry: Vec<Point>,
    way: &Way,
    terrain: &mut Option<elevation::Terrain>,
) -> Result<Vec<Point>> {
    if let Some(terrain) = terrain {
        let mut sampled = vec![];
        for pair in geometry.windows(2) {
            let sample_count = (geo::distance(pair[0], pair[1]) / 60.).ceil().max(1.) as usize;
            for i in 0..sample_count {
                let mut point = geo::interpolate(pair[0], pair[1], i as f64 / sample_count as f64);
                point.elevation = terrain.height(point)?;
                sampled.push(point);
            }
        }
        let mut end = *geometry.last().unwrap();
        end.elevation = terrain.height(end)?;
        sampled.push(end);
        if way.tags.get("bridge").is_some_and(|v| v != "no")
            || way.tags.get("tunnel").is_some_and(|v| v != "no")
        {
            let start = sampled[0].elevation;
            let end = sampled.last().unwrap().elevation;
            let length: f64 = sampled
                .windows(2)
                .map(|pair| geo::distance(pair[0], pair[1]))
                .sum();
            let mut along = 0.;
            for index in 1..sampled.len() {
                along += geo::distance(sampled[index - 1], sampled[index]);
                sampled[index].elevation = start + (end - start) * along / length.max(1.);
            }
        }
        return Ok(sampled);
    }
    Ok(geometry)
}

/// CH preprocessing is sequential by profile and parallel within a profile to bound peak memory.
fn prepare_profile(
    output: &Path,
    mode: Mode,
    roads: &[Road],
    nodes: &HashMap<i64, Node>,
    outgoing: &HashMap<i64, Vec<u32>>,
    relations: &[Restriction],
) -> Result<()> {
    let (variants, mut edges) = expand_states(mode, roads, nodes, outgoing, relations)?;
    // Persist states in node order so the runtime can find outgoing roads without an extra heap index.
    let mut order: Vec<_> = (0..variants.len()).collect();
    order.sort_by_key(|&id| (roads[variants[id].0 as usize].from, id));
    let mut remap = vec![0u32; order.len()];
    for (new, &old) in order.iter().enumerate() {
        remap[old] = new as u32;
    }
    if mode == Mode::Foot {
        let node_ids = write::node_ids(roads);
        edges = roads
            .iter()
            .map(|road| WeightedEdge {
                tail: Vertex::new(node_ids[&road.from]),
                head: Vertex::new(node_ids[&road.to]),
                weight: road.weight,
            })
            .collect();
    }
    for edge in edges.iter_mut().filter(|_| mode == Mode::Car) {
        edge.tail = Vertex::new(remap[edge.tail.as_usize()]);
        edge.head = Vertex::new(remap[edge.head.as_usize()]);
    }
    println!(
        "[routing] profile={} states={} transitions={} preparing=true",
        mode.name(),
        variants.len(),
        edges.len()
    );
    write::stage(
        &output.join(format!("{}.routing", mode.name())),
        roads,
        &variants,
        &order,
        &edges,
        mode == Mode::Foot,
    )?;
    Ok(())
}

type Expanded = (Vec<(u32, usize)>, Vec<WeightedEdge<u64>>);
/// Expand only restriction-history prefixes; unrestricted roads keep one state per direction.
fn expand_states(
    mode: Mode,
    roads: &[Road],
    nodes: &HashMap<i64, Node>,
    outgoing: &HashMap<i64, Vec<u32>>,
    relations: &[Restriction],
) -> Result<Expanded> {
    let restrictions = if mode == Mode::Car {
        restrictions::forbidden(roads, outgoing, relations)?
    } else {
        vec![]
    };
    let automaton = Automaton::new(restrictions);
    let mut variants: Vec<(u32, usize)> = roads
        .iter()
        .enumerate()
        .map(|(id, _)| (id as u32, automaton.advance(0, id as u32).unwrap_or(0)))
        .collect();
    let mut ids: HashMap<_, _> = variants
        .iter()
        .enumerate()
        .map(|(id, &state)| (state, id as u32))
        .collect();
    let mut edges = Vec::new();
    let mut cursor = 0;
    while mode == Mode::Car && cursor < variants.len() {
        if variants.len() > 30_000_000 {
            return Err("too many restriction states".into());
        }
        let (physical, context) = variants[cursor];
        let from = &roads[physical as usize];
        if !nodes
            .get(&from.to)
            .is_some_and(|node| profile::barrier(&node.tags, mode))
        {
            for &next in outgoing.get(&from.to).into_iter().flatten() {
                let Some(next_context) = automaton.advance(context, next) else {
                    continue;
                };
                let key = (next, next_context);
                let target = *ids.entry(key).or_insert_with(|| {
                    let id = variants.len() as u32;
                    variants.push(key);
                    id
                });
                let to = &roads[next as usize];
                let penalty = if mode == Mode::Foot {
                    0.
                } else if from.from == to.to && from.way == to.way {
                    60.
                } else {
                    let before = geo::bearing(
                        from.geometry[from.geometry.len() - 2],
                        *from.geometry.last().unwrap(),
                    );
                    let after = geo::bearing(to.geometry[0], to.geometry[1]);
                    let angle = geo::longitude(after - before);
                    if angle >= 80. {
                        5.
                    } else if angle >= 25. {
                        2.
                    } else if angle <= -80. {
                        12.
                    } else if angle <= -25. {
                        8.
                    } else {
                        0.
                    }
                };
                edges.push(WeightedEdge {
                    tail: Vertex::new(cursor as u32),
                    head: Vertex::new(target),
                    weight: to.weight + (penalty * 1000.) as u64,
                });
            }
        }
        cursor += 1;
    }
    Ok((variants, edges))
}
