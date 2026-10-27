#![cfg(feature = "builder")]
use imu_nav_routing::{
    builder,
    route::{Request, route},
    storage::Network,
};
use std::sync::atomic::AtomicBool;

/// Artificial streets isolate road preference from GPS, real addresses and traffic conditions.
fn streets(tags: &str) -> String {
    format!(
        r#"<osm version="0.6">
<node id="1" lat="0" lon="-0.001"/><node id="2" lat="0" lon="0"/>
<node id="3" lat="0" lon="0.0001"/><node id="4" lat="0.004" lon="0.0018"/>
<node id="5" lat="0" lon="0.0035"/><node id="6" lat="0" lon="0.0036"/>
<node id="7" lat="0" lon="0.0046"/><node id="8" lat="0" lon="0.0018"/>
<way id="1"><nd ref="1"/><nd ref="2"/><tag k="highway" v="primary"/><tag k="name" v="Entry"/></way>
<way id="2"><nd ref="2"/><nd ref="3"/><nd ref="4"/><nd ref="5"/><nd ref="6"/><tag k="highway" v="primary"/><tag k="name" v="Main road"/></way>
<way id="3"><nd ref="6"/><nd ref="7"/><tag k="highway" v="primary"/><tag k="name" v="Exit"/></way>
<way id="4"><nd ref="2"/><nd ref="8"/><tag k="highway" v="service"/><tag k="name" v="Courtyard"/>{tags}</way>
<way id="5"><nd ref="8"/><nd ref="6"/><tag k="highway" v="service"/><tag k="name" v="Courtyard"/>{tags}</way>
</osm>"#
    )
}

fn request(via: Option<[f64; 2]>) -> Request {
    let mut points = vec![[0., -0.0009]];
    points.extend(via);
    points.push([0., 0.0045]);
    Request { points }
}

#[test]
fn car_choices_use_distinct_metrics_without_changing_modeled_speed() {
    for service in ["", "driveway", "parking_aisle", "alley"] {
        let tmp = tempfile::tempdir().unwrap();
        let input = tmp.path().join("streets.osm");
        std::fs::write(
            &input,
            streets(&format!("<tag k=\"service\" v=\"{service}\"/>")),
        )
        .unwrap();
        let pack = tmp.path().join("pack");
        builder::build(&input, &pack, "Synthetic", 0, false, false).unwrap();
        let flag = AtomicBool::new(false);
        let main = Network::open(&pack.join("car.routing")).unwrap();
        let balanced = Network::open(&pack.join("car_balanced.routing")).unwrap();
        let preferred = route(&main, &request(None), &flag).unwrap();
        let shortcut = route(&balanced, &request(None), &flag).unwrap();
        assert!(preferred.steps.iter().any(|s| s.name == "Main road"));
        assert!(!preferred.steps.iter().any(|s| s.name == "Courtyard"));
        assert!(shortcut.steps.iter().any(|s| s.name == "Courtyard"));
        assert!(preferred.duration_s < shortcut.duration_s);
        let local = request(Some([0., 0.0018]));
        let main_local = route(&main, &local, &flag).unwrap();
        let balanced_local = route(&balanced, &local, &flag).unwrap();
        assert_eq!(main_local.geometry, balanced_local.geometry);
        assert_eq!(
            main_local.segment_speed_mps,
            balanced_local.segment_speed_mps
        );
        assert_eq!(main_local.duration_s, balanced_local.duration_s);
    }
}

#[test]
fn local_components_allow_endpoints_and_stops_but_never_through_traffic() {
    for access in ["destination", "customers"] {
        let tmp = tempfile::tempdir().unwrap();
        let input = tmp.path().join("streets.osm");
        std::fs::write(
            &input,
            streets(&format!("<tag k=\"motor_vehicle\" v=\"{access}\"/>")),
        )
        .unwrap();
        let pack = tmp.path().join("pack");
        builder::build(&input, &pack, "Synthetic", 0, false, false).unwrap();
        for profile in ["car", "car_balanced"] {
            let network = Network::open(&pack.join(format!("{profile}.routing"))).unwrap();
            let flag = AtomicBool::new(false);
            let through = route(&network, &request(None), &flag).unwrap();
            assert!(!through.steps.iter().any(|s| s.name == "Courtyard"));
            for points in [
                vec![[0., -0.0009], [0., 0.0027]],
                vec![[0., 0.0009], [0., 0.0045]],
                vec![[0., 0.0009], [0., 0.0027]],
                vec![[0., -0.0009], [0., 0.0009], [0., 0.0027], [0., 0.0045]],
            ] {
                let local = route(&network, &Request { points }, &flag).unwrap();
                assert!(local.steps.iter().any(|s| s.name == "Courtyard"));
            }
        }
    }
}

#[test]
fn delivery_is_excluded_and_vehicle_specific_access_overrides_general_access() {
    use builder::{
        osm::Tags,
        profile::{self, Mode},
    };
    let mut tags = Tags::from([
        ("highway".into(), "service".into()),
        ("access".into(), "delivery".into()),
    ]);
    for mode in [Mode::Car, Mode::CarBalanced] {
        assert!(profile::access(&tags, mode, false).is_none());
        tags.insert("motorcar".into(), "yes".into());
        assert!(profile::access(&tags, mode, false).is_some());
        assert!(!profile::local_access(&tags, mode, false));
        tags.remove("motorcar");
    }
    tags.insert("access".into(), "destination".into());
    tags.insert("motor_vehicle:forward".into(), "yes".into());
    assert!(!profile::local_access(&tags, Mode::Car, false));
    assert!(profile::local_access(&tags, Mode::Car, true));
}

#[test]
fn a_local_stop_retains_via_way_turn_restriction_history() {
    let tmp = tempfile::tempdir().unwrap();
    let input = tmp.path().join("streets.osm");
    let xml = streets("<tag k=\"access\" v=\"destination\"/>")
        .replace("<tag k=\"highway\"", "<tag k=\"oneway\" v=\"yes\"/><tag k=\"highway\"")
        .replace("</osm>", r#"<relation id="10"><member type="way" ref="1" role="from"/><member type="way" ref="4" role="via"/><member type="way" ref="5" role="to"/><tag k="type" v="restriction"/><tag k="restriction" v="no_straight_on"/></relation></osm>"#);
    std::fs::write(&input, xml).unwrap();
    let pack = tmp.path().join("pack");
    builder::build(&input, &pack, "Synthetic", 0, false, false).unwrap();
    for profile in ["car", "car_balanced"] {
        let network = Network::open(&pack.join(format!("{profile}.routing"))).unwrap();
        assert!(
            route(
                &network,
                &request(Some([0., 0.0009])),
                &AtomicBool::new(false)
            )
            .is_err()
        );
    }
}
