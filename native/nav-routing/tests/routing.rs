#![cfg(feature = "builder")]
use imu_nav_routing::{
    builder,
    route::{Request, route},
    storage::Network,
};
use std::sync::atomic::AtomicBool;
fn fixture() -> String {
    r#"<osm version="0.6">
<node id="1" lat="50.45" lon="30.5"/><node id="2" lat="50.46" lon="30.5"/><node id="3" lat="50.46" lon="30.51"/><node id="4" lat="50.47" lon="30.5"/><node id="5" lat="50.455" lon="30.505"/>
<way id="1"><nd ref="1"/><nd ref="2"/><nd ref="4"/><tag k="highway" v="primary"/><tag k="name" v="Northway"/><tag k="maxspeed" v="50"/></way>
<way id="2"><nd ref="2"/><nd ref="3"/><tag k="highway" v="secondary"/><tag k="name" v="Eastway"/><tag k="maxspeed" v="30"/></way>
<way id="3"><nd ref="1"/><nd ref="5"/><nd ref="3"/><tag k="highway" v="footway"/><tag k="name" v="Parkpath"/></way>
</osm>"#.into()
}
#[test]
fn independent_import_routes_geometry_profiles_and_guidance() {
    let tmp = tempfile::tempdir().unwrap();
    let osm = tmp.path().join("test.osm");
    std::fs::write(&osm, fixture()).unwrap();
    let out = tmp.path().join("pack");
    builder::build(&osm, &out, "Test", 0, false, false).unwrap();
    let car = Network::open(&out.join("car.routing")).unwrap();
    let foot = Network::open(&out.join("foot.routing")).unwrap();
    let flag = AtomicBool::new(false);
    let request = Request {
        points: vec![[50.4502, 30.5001], [50.4601, 30.5099]],
    };
    let drive = route(&car, &request, &flag).unwrap();
    let walk = route(&foot, &request, &flag).unwrap();
    assert!(
        drive
            .steps
            .iter()
            .any(|s| s.r#type == "turn" && s.modifier.as_deref() == Some("right"))
    );
    assert!(walk.steps.iter().any(|s| s.name == "Parkpath"));
    assert!(walk.duration_s > drive.duration_s);
    assert!(drive.maxspeed_kmh.contains(&Some(50)));
    assert_eq!(drive.geometry.len() - 1, drive.maxspeed_kmh.len());
    flag.store(true, std::sync::atomic::Ordering::Relaxed);
    assert!(route(&car, &request, &flag).is_err());
}
#[test]
fn truncated_and_malformed_packs_are_rejected() {
    let tmp = tempfile::tempdir().unwrap();
    let file = tmp.path().join("bad.routing");
    std::fs::write(&file, b"IMURT001").unwrap();
    assert!(Network::open(&file).is_err());
}
#[test]
fn turn_restriction_prevents_direct_turn() {
    let tmp = tempfile::tempdir().unwrap();
    let osm = tmp.path().join("test.osm");
    let text=fixture().replace("</osm>",r#"<relation id="100"><member type="way" ref="1" role="from"/><member type="node" ref="2" role="via"/><member type="way" ref="2" role="to"/><tag k="type" v="restriction"/><tag k="restriction" v="no_right_turn"/></relation></osm>"#);
    std::fs::write(&osm, text).unwrap();
    let out = tmp.path().join("pack");
    builder::build(&osm, &out, "Test", 0, false, false).unwrap();
    let car = Network::open(&out.join("car.routing")).unwrap();
    let request = Request {
        points: vec![[50.451, 30.5], [50.46, 30.509]],
    };
    assert!(route(&car, &request, &AtomicBool::new(false)).is_err());
}

#[test]
fn mmap_ch_matches_independent_dijkstra_on_both_profiles() {
    use imu_nav_routing::{
        ids::{Direction, VertexId},
        query,
    };
    use std::{cmp::Reverse, collections::BinaryHeap};
    let tmp = tempfile::tempdir().unwrap();
    let osm = tmp.path().join("test.osm");
    std::fs::write(&osm, fixture()).unwrap();
    let out = tmp.path().join("pack");
    builder::build(&osm, &out, "Test", 0, false, false).unwrap();
    for profile in ["car", "car_balanced", "foot"] {
        let network = Network::open(&out.join(format!("{profile}.routing"))).unwrap();
        let mut original = vec![vec![]; network.graph_count];
        for dir in 0..2 {
            for tail in 0..network.graph_count {
                for index in network.outgoing(
                    Direction::ALL[dir],
                    VertexId::new(u32::try_from(tail).unwrap()),
                ) {
                    let arc = network.arc(Direction::ALL[dir], index);
                    if arc.skipped.is_none() {
                        let (from, to) = if dir == 0 {
                            (tail, arc.head.get() as usize)
                        } else {
                            (arc.head.get() as usize, tail)
                        };
                        original[from].push((to, arc.weight));
                    }
                }
            }
        }
        for source in 0..network.graph_count {
            let mut distances = vec![u64::MAX; network.graph_count];
            distances[source] = 0;
            let mut queue = BinaryHeap::from([Reverse((0, source))]);
            while let Some(Reverse((cost, node))) = queue.pop() {
                if cost != distances[node] {
                    continue;
                }
                for &(next, weight) in &original[node] {
                    if cost + weight < distances[next] {
                        distances[next] = cost + weight;
                        queue.push(Reverse((cost + weight, next)));
                    }
                }
            }
            for (target, &expected) in distances.iter().enumerate() {
                let actual = query::path(
                    &network,
                    VertexId::new(u32::try_from(source).unwrap()),
                    VertexId::new(u32::try_from(target).unwrap()),
                    &AtomicBool::new(false),
                )
                .unwrap();
                assert_eq!(
                    actual.as_ref().map(|(cost, _)| *cost),
                    (expected != u64::MAX).then_some(expected)
                );
                if let Some((cost, path)) = actual {
                    let actual_cost: u64 = path
                        .windows(2)
                        .map(|pair| {
                            original[pair[0].get() as usize]
                                .iter()
                                .filter(|(to, _)| *to == pair[1].get() as usize)
                                .map(|(_, cost)| cost)
                                .min()
                                .unwrap()
                        })
                        .sum();
                    assert_eq!(cost, actual_cost);
                }
            }
        }
    }
}

#[test]
fn elevation_uses_local_hgt_and_missing_tiles_fail() {
    let tmp = tempfile::tempdir().unwrap();
    let osm = tmp.path().join("test.osm");
    std::fs::write(&osm, fixture()).unwrap();
    let terrain = tmp.path().join("terrain");
    std::fs::create_dir(&terrain).unwrap();
    assert!(
        builder::build_with_elevation(
            &osm,
            &tmp.path().join("missing"),
            "Test",
            0,
            false,
            false,
            Some(&terrain)
        )
        .is_err()
    );
    std::fs::write(
        terrain.join("N50E030.hgt"),
        [123i16.to_be_bytes(); 4].concat(),
    )
    .unwrap();
    let out = tmp.path().join("elevated");
    builder::build_with_elevation(&osm, &out, "Test", 0, false, false, Some(&terrain)).unwrap();
    let car = Network::open(&out.join("car.routing")).unwrap();
    let result = route(
        &car,
        &Request {
            points: vec![[50.451, 30.5], [50.46, 30.509]],
        },
        &AtomicBool::new(false),
    )
    .unwrap();
    assert!(
        result
            .elevation_m
            .unwrap()
            .iter()
            .all(|height| (height - 123.).abs() < 1e-8)
    );
}

#[test]
fn via_way_restrictions_retain_history() {
    let tmp = tempfile::tempdir().unwrap();
    let input = tmp.path().join("via.osm");
    let osm = r#"<osm version="0.6">
<node id="1" lat="50" lon="30"/><node id="2" lat="50.01" lon="30"/><node id="3" lat="50.02" lon="30"/><node id="4" lat="50.03" lon="30"/>
<way id="10"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/><tag k="oneway" v="yes"/><tag k="name" v="A"/></way>
<way id="11"><nd ref="2"/><nd ref="3"/><tag k="highway" v="residential"/><tag k="oneway" v="yes"/><tag k="name" v="B"/></way>
<way id="12"><nd ref="3"/><nd ref="4"/><tag k="highway" v="residential"/><tag k="oneway" v="yes"/><tag k="name" v="C"/></way>
<relation id="99"><member type="way" ref="10" role="from"/><member type="way" ref="11" role="via"/><member type="way" ref="12" role="to"/><tag k="type" v="restriction"/><tag k="restriction" v="no_straight_on"/></relation></osm>"#;
    std::fs::write(&input, osm).unwrap();
    let output = tmp.path().join("pack");
    builder::build(&input, &output, "Test", 0, false, false).unwrap();
    let car = Network::open(&output.join("car.routing")).unwrap();
    assert!(
        route(
            &car,
            &Request {
                points: vec![[50.001, 30.], [50.029, 30.]]
            },
            &AtomicBool::new(false)
        )
        .is_err()
    );
    assert!(
        route(
            &car,
            &Request {
                points: vec![[50.011, 30.], [50.029, 30.]]
            },
            &AtomicBool::new(false)
        )
        .is_ok()
    );
}

#[test]
fn dateline_same_road_projection_does_not_take_the_long_way() {
    let tmp = tempfile::tempdir().unwrap();
    let input = tmp.path().join("date.osm");
    std::fs::write(&input,r#"<osm version="0.6"><node id="1" lat="0" lon="179.999"/><node id="2" lat="0" lon="-179.999"/><way id="1"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/></way></osm>"#).unwrap();
    let output = tmp.path().join("pack");
    builder::build(&input, &output, "Test", 0, false, false).unwrap();
    for profile in ["car", "car_balanced", "foot"] {
        let network = Network::open(&output.join(format!("{profile}.routing"))).unwrap();
        let result = route(
            &network,
            &Request {
                points: vec![[0., 179.9992], [0., -179.9992]],
            },
            &AtomicBool::new(false),
        )
        .unwrap();
        let length: f64 = result
            .geometry
            .windows(2)
            .map(|pair| {
                imu_nav_routing::geo::distance(
                    imu_nav_routing::geo::Point {
                        lat: pair[0][0],
                        lon: pair[0][1],
                        elevation: 0.,
                    },
                    imu_nav_routing::geo::Point {
                        lat: pair[1][0],
                        lon: pair[1][1],
                        elevation: 0.,
                    },
                )
            })
            .sum();
        assert!((170.0..185.0).contains(&length));
    }
}

#[test]
fn one_way_direction_and_barriers_are_profile_specific() {
    let tmp = tempfile::tempdir().unwrap();
    let input = tmp.path().join("oneway.osm");
    let text = fixture().replace(
        "<tag k=\"name\" v=\"Northway\"/>",
        "<tag k=\"name\" v=\"Northway\"/><tag k=\"oneway\" v=\"yes\"/>",
    );
    std::fs::write(&input, text).unwrap();
    let out = tmp.path().join("pack");
    builder::build(&input, &out, "Test", 0, false, false).unwrap();
    let request = Request {
        points: vec![[50.459, 30.5], [50.451, 30.5]],
    };
    assert!(
        route(
            &Network::open(&out.join("car.routing")).unwrap(),
            &request,
            &AtomicBool::new(false)
        )
        .is_err()
    );
    assert!(
        route(
            &Network::open(&out.join("foot.routing")).unwrap(),
            &request,
            &AtomicBool::new(false)
        )
        .is_ok()
    );
    let barrier = tmp.path().join("barrier.osm");
    std::fs::write(&barrier,r#"<osm version="0.6"><node id="1" lat="50" lon="30"/><node id="2" lat="50.01" lon="30"><tag k="barrier" v="bollard"/></node><node id="3" lat="50.02" lon="30"/><way id="1"><nd ref="1"/><nd ref="2"/><nd ref="3"/><tag k="highway" v="residential"/></way></osm>"#).unwrap();
    let out = tmp.path().join("barrier");
    builder::build(&barrier, &out, "Test", 0, false, false).unwrap();
    let request = Request {
        points: vec![[50.001, 30.], [50.019, 30.]],
    };
    assert!(
        route(
            &Network::open(&out.join("car.routing")).unwrap(),
            &request,
            &AtomicBool::new(false)
        )
        .is_err()
    );
    assert!(
        route(
            &Network::open(&out.join("foot.routing")).unwrap(),
            &request,
            &AtomicBool::new(false)
        )
        .is_ok()
    );
}

#[test]
fn compact_pack_shares_geometry_and_preserves_point_order_and_precision() {
    let tmp = tempfile::tempdir().unwrap();
    let input = tmp.path().join("rounded.osm");
    std::fs::write(&input, r#"<osm version="0.6"><node id="1" lat="-50.000000049" lon="-30.000000049"/><node id="2" lat="-50.000000051" lon="-30.000000051"/><node id="3" lat="-50.001" lon="-30.001"/><way id="1"><nd ref="1"/><nd ref="2"/><nd ref="3"/><tag k="highway" v="residential"/><tag k="name" v="Спільна"/></way></osm>"#).unwrap();
    let output = tmp.path().join("pack");
    builder::build(&input, &output, "Test", 0, false, false).unwrap();
    let shared = std::fs::read(output.join("shared.routing")).unwrap();
    let read = |at| u64::from_le_bytes(shared[at..at + 8].try_into().unwrap());
    assert_eq!(&shared[..8], b"IMUGEO02");
    assert_eq!(read(8), 1); // Both profiles and both directions reference the same polyline.
    assert_eq!(read(24), 3); // No point simplification.
    assert_eq!(read(40), 0); // No absent-elevation array.
    let pack = imu_nav_routing::pack::Pack::open(&output).unwrap();
    for profile in ["car", "car_balanced", "foot"] {
        let network = pack.profile(profile.parse().unwrap()).unwrap();
        assert_eq!(network.count, 2);
        let a = network.state(imu_nav_routing::ids::StateId::new(0));
        let b = network.state(imu_nav_routing::ids::StateId::new(1));
        assert_eq!(a.geometry, b.geometry);
        assert_eq!(a.point_count, 3);
        for index in 0..3 {
            assert_eq!(network.point(a, index).lat, network.point(b, 2 - index).lat);
        }
        assert_eq!(network.name(a), "Спільна");
        assert!((network.point(a, 0).lat + 50.000_000_049).abs() <= 0.000_000_05);
    }
    drop(pack);
    let path = output.join("car.routing");
    let bytes = std::fs::read(&path).unwrap();
    let mut bad_reference = bytes.clone();
    bad_reference[80..84].copy_from_slice(&u32::MAX.to_le_bytes());
    std::fs::write(&path, bad_reference).unwrap();
    assert!(Network::open(&path).is_err());
    let mut legacy = bytes;
    legacy[..8].copy_from_slice(b"IMURT001");
    std::fs::write(&path, legacy).unwrap();
    assert!(Network::open(&path).is_err());
}

#[test]
fn wide_hierarchy_sections_and_corrupt_grid_are_checked() {
    let tmp = tempfile::tempdir().unwrap();
    let input = tmp.path().join("test.osm");
    std::fs::write(&input, fixture()).unwrap();
    let output = tmp.path().join("pack");
    builder::build(&input, &output, "Test", 0, false, false).unwrap();
    let path = output.join("car.routing");
    let bytes = std::fs::read(&path).unwrap();
    let read =
        |at| usize::try_from(u64::from_le_bytes(bytes[at..at + 8].try_into().unwrap())).unwrap();
    let count = read(8);
    let nodes = read(16);
    let arcs = [read(32), read(40)];
    assert_eq!(read(48), 0);
    let prefix = 72 + count * 40;
    let mut wide = bytes[..prefix].to_vec();
    wide[48..56].copy_from_slice(&15u64.to_le_bytes());
    let mut cursor = prefix;
    for _ in 0..2 * (nodes + 1) {
        let offset = u32::from_le_bytes(bytes[cursor..cursor + 4].try_into().unwrap());
        wide.extend_from_slice(&u64::from(offset).to_le_bytes());
        cursor += 4;
    }
    for _ in 0..arcs.iter().sum::<usize>() {
        wide.extend_from_slice(&bytes[cursor..cursor + 8]);
        let weight = u32::from_le_bytes(bytes[cursor + 8..cursor + 12].try_into().unwrap());
        wide.extend_from_slice(&u64::from(weight).to_le_bytes());
        cursor += 12;
    }
    let grid = wide.len();
    wide.extend_from_slice(&bytes[cursor..]);
    std::fs::write(&path, &wide).unwrap();
    let network = Network::open(&path).unwrap();
    let request = Request {
        points: vec![[50.4502, 30.5001], [50.4601, 30.5099]],
    };
    assert!(route(&network, &request, &AtomicBool::new(false)).is_ok());
    drop(network);
    wide[grid..grid + 4].copy_from_slice(&u32::MAX.to_le_bytes());
    std::fs::write(&path, wide).unwrap();
    assert!(Network::open(&path).is_err());
}

#[test]
fn malformed_shared_ranges_and_coordinates_are_rejected() {
    let tmp = tempfile::tempdir().unwrap();
    let input = tmp.path().join("test.osm");
    std::fs::write(&input, fixture()).unwrap();
    let output = tmp.path().join("pack");
    builder::build(&input, &output, "Test", 0, false, false).unwrap();
    let path = output.join("shared.routing");
    let original = std::fs::read(&path).unwrap();
    for offset in [8, 24, 64] {
        let mut invalid = original.clone();
        invalid[offset..offset + 8].copy_from_slice(&u64::MAX.to_le_bytes());
        std::fs::write(&path, invalid).unwrap();
        assert!(imu_nav_routing::pack::Pack::open(&output).is_err());
    }
    let geometries =
        usize::try_from(u64::from_le_bytes(original[8..16].try_into().unwrap())).unwrap();
    let names = usize::try_from(u64::from_le_bytes(original[16..24].try_into().unwrap())).unwrap();
    let points = 64 + (geometries + names) * 16;
    let mut invalid = original.clone();
    invalid[points..points + 4].copy_from_slice(&i32::MAX.to_le_bytes());
    std::fs::write(&path, invalid).unwrap();
    assert!(imu_nav_routing::pack::Pack::open(&output).is_err());
    std::fs::write(&path, &original[..63]).unwrap();
    assert!(imu_nav_routing::pack::Pack::open(&output).is_err());
}

#[test]
fn trusted_open_skips_record_scan_but_keeps_layout_checks_and_routes() {
    let tmp = tempfile::tempdir().unwrap();
    let input = tmp.path().join("test.osm");
    std::fs::write(&input, fixture()).unwrap();
    let output = tmp.path().join("pack");
    builder::build(&input, &output, "Test", 0, false, false).unwrap();
    let request = Request {
        points: vec![[50.4502, 30.5001], [50.4601, 30.5099]],
    };
    let flag = AtomicBool::new(false);
    let full = imu_nav_routing::pack::Pack::open(&output).unwrap();
    let trusted = imu_nav_routing::pack::Pack::open_trusted(&output).unwrap();
    for profile in ["car", "car_balanced", "foot"] {
        let expected = route(
            full.profile(profile.parse().unwrap()).unwrap(),
            &request,
            &flag,
        )
        .unwrap();
        let actual = route(
            trusted.profile(profile.parse().unwrap()).unwrap(),
            &request,
            &flag,
        )
        .unwrap();
        assert_eq!(expected.geometry, actual.geometry);
        assert_eq!(expected.duration_s, actual.duration_s);
    }
    drop((full, trusted));
    let path = output.join("shared.routing");
    let original = std::fs::read(&path).unwrap();
    // A bad coordinate is only found by the full scan.
    let geometries =
        usize::try_from(u64::from_le_bytes(original[8..16].try_into().unwrap())).unwrap();
    let names = usize::try_from(u64::from_le_bytes(original[16..24].try_into().unwrap())).unwrap();
    let points = 64 + (geometries + names) * 16;
    let mut invalid = original.clone();
    invalid[points..points + 4].copy_from_slice(&i32::MAX.to_le_bytes());
    std::fs::write(&path, invalid).unwrap();
    assert!(imu_nav_routing::pack::Pack::open(&output).is_err());
    assert!(imu_nav_routing::pack::Pack::open_trusted(&output).is_ok());
    // Truncation still fails the cheap section checks.
    std::fs::write(&path, &original[..original.len() - 1]).unwrap();
    assert!(imu_nav_routing::pack::Pack::open_trusted(&output).is_err());
}

#[test]
fn single_mode_packs_trim_shared_data_and_preserve_routes_and_search() {
    let tmp = tempfile::tempdir().unwrap();
    let input = tmp.path().join("test.osm");
    std::fs::write(&input, fixture()).unwrap();
    let combined = tmp.path().join("combined");
    builder::build(&input, &combined, "Test", 0, true, true).unwrap();
    let original = imu_nav_routing::pack::Pack::open(&combined).unwrap();
    let query = Request {
        points: vec![[50.4502, 30.5001], [50.4601, 30.5099]],
    };
    for profile in ["car", "foot"] {
        let output = tmp.path().join(profile);
        let info = builder::subset::create(&combined, &output, profile).unwrap();
        assert_eq!(
            info["profiles"],
            if profile == "car" {
                serde_json::json!(["car", "car_balanced"])
            } else {
                serde_json::json!(["foot"])
            }
        );
        if profile == "car" {
            assert!(output.join("car_balanced.routing").is_file());
        }
        let selected = imu_nav_routing::pack::Pack::open(&output).unwrap();
        let other = if profile == "car" { "foot" } else { "car" };
        assert!(selected.profile(other.parse().unwrap()).is_err());
        assert!(!output.join(format!("{other}.routing")).exists());
        let expected = route(
            original.profile(profile.parse().unwrap()).unwrap(),
            &query,
            &AtomicBool::new(false),
        )
        .unwrap();
        let actual = route(
            selected.profile(profile.parse().unwrap()).unwrap(),
            &query,
            &AtomicBool::new(false),
        )
        .unwrap();
        assert_eq!(
            serde_json::to_value(expected).unwrap(),
            serde_json::to_value(actual).unwrap()
        );
        for entry in std::fs::read_dir(combined.join("search.tantivy")).unwrap() {
            let entry = entry.unwrap();
            assert_eq!(
                std::fs::read(entry.path()).unwrap(),
                std::fs::read(output.join("search.tantivy").join(entry.file_name())).unwrap()
            );
        }
        if profile == "car" {
            assert!(
                std::fs::metadata(output.join("shared.routing"))
                    .unwrap()
                    .len()
                    < std::fs::metadata(combined.join("shared.routing"))
                        .unwrap()
                        .len()
            );
        }
        let source_bytes = std::fs::read(combined.join(format!("{profile}.routing"))).unwrap();
        let selected_bytes = std::fs::read(output.join(format!("{profile}.routing"))).unwrap();
        let records_end = 72 + original.profile(profile.parse().unwrap()).unwrap().count * 40;
        assert_eq!(source_bytes[..72], selected_bytes[..72]);
        assert_eq!(source_bytes[records_end..], selected_bytes[records_end..]);
        assert!(builder::subset::create(&combined, &output, profile).is_err());
    }
}
