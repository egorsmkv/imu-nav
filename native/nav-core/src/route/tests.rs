use super::*;

#[test]
fn proof_geometry_matches_the_constructed_route_and_its_index() {
    let proof = kani_proofs::straight_route();
    let actual = RouteGeometry::new(proof.points.clone()).unwrap();
    assert_eq!(proof.points, actual.points);
    assert_eq!(proof.blocks, actual.blocks);
    assert!((proof.length_m() - actual.length_m()).abs() < 1.0e-9);
    assert_eq!(proof.turns, actual.turns);
    let landmarks = vec![RouteTurn {
        position_m: 1_000.0,
        angle_deg: 90.0,
    }];
    let landmark_route = kani_proofs::with_landmarks(landmarks.clone());
    assert_eq!(landmark_route.turns, landmarks);
    assert_eq!(landmark_route.blocks, actual.blocks);
}

#[test]
fn antipodal_routes_keep_a_finite_cumulative_length() {
    for latitude in -89..=89 {
        let first = GeoPoint {
            latitude_deg: f64::from(latitude),
            longitude_deg: -179.0,
        };
        let second = GeoPoint {
            latitude_deg: -f64::from(latitude),
            longitude_deg: 1.0,
        };
        let route = RouteGeometry::new(vec![first, second]).unwrap();
        assert!(route.length_m().is_finite(), "latitude={latitude}");
        assert!((route.length_m() - PI * EARTH_DIAMETER_M / 2.0).abs() < 0.2);
    }
}

fn route() -> RouteGeometry {
    RouteGeometry::new(vec![
        GeoPoint {
            latitude_deg: 50.0,
            longitude_deg: 30.0,
        },
        GeoPoint {
            latitude_deg: 50.01,
            longitude_deg: 30.0,
        },
        GeoPoint {
            latitude_deg: 50.01,
            longitude_deg: 30.01,
        },
    ])
    .unwrap()
}

#[test]
fn shared_scale_matches_independent_segment_projection_exactly() {
    for latitude in [0.0, 50.0, 89.5] {
        // Includes a crossing, repeated vertex, parallel return and a straight continuation.
        let points: Vec<_> = [
            (0.0, 0.0),
            (0.01, 0.01),
            (0.01, 0.01),
            (0.0, 0.01),
            (0.01, 0.0),
            (0.02, 0.0),
        ]
        .into_iter()
        .map(|(north, east)| GeoPoint {
            latitude_deg: latitude + north,
            longitude_deg: 30.0 + east,
        })
        .collect();
        let route = RouteGeometry::new(points).unwrap();
        for north in [0.0, 0.005, 0.015, 0.025] {
            let query = GeoPoint {
                latitude_deg: latitude + north,
                longitude_deg: 30.004,
            };
            for accuracy in [1.0_f64, 30.0, 200.0] {
                let best = route.project_range(query, 0, route.points.len() - 2);
                let distinct = (4.0 * accuracy).max(100.0);
                let ambiguous = (0..route.points.len() - 1).any(|segment| {
                    let rival = route.project_range(query, segment, segment);
                    (rival.position_m - best.position_m).abs() > distinct
                        && rival.offset_m <= best.offset_m + 2.0 * accuracy
                });
                assert_eq!(
                    route.project_unambiguous(query, accuracy).unwrap(),
                    (!ambiguous).then_some(best)
                );
            }
        }
    }
}

// Frozen exhaustive reference: evaluate every distance with the original arithmetic.
fn exhaustive_projection(
    route: &RouteGeometry,
    point: GeoPoint,
    from: usize,
    to: usize,
) -> Projection {
    let metres_per_degree_longitude = longitude_scale(point);
    let mut best = Projection {
        position_m: 0.0,
        offset_m: f64::MAX,
        segment: from,
        point: route.points[from],
    };
    for index in from..=to.min(route.points.len() - 2) {
        let start_x =
            (route.points[index].longitude_deg - point.longitude_deg) * metres_per_degree_longitude;
        let start_y =
            (route.points[index].latitude_deg - point.latitude_deg) * METRES_PER_DEGREE_LATITUDE;
        let direction_x = (route.points[index + 1].longitude_deg
            - route.points[index].longitude_deg)
            * metres_per_degree_longitude;
        let direction_y = (route.points[index + 1].latitude_deg - route.points[index].latitude_deg)
            * METRES_PER_DEGREE_LATITUDE;
        let length_squared = direction_x * direction_x + direction_y * direction_y;
        let fraction = if length_squared < 1.0e-6 {
            0.0
        } else {
            ((-start_x * direction_x - start_y * direction_y) / length_squared).clamp(0.0, 1.0)
        };
        let closest_x = start_x + direction_x * fraction;
        let closest_y = start_y + direction_y * fraction;
        let offset_m = closest_x.hypot(closest_y);
        if offset_m < best.offset_m {
            best = Projection {
                position_m: route.cumulative_m[index]
                    + fraction * (route.cumulative_m[index + 1] - route.cumulative_m[index]),
                offset_m,
                segment: index,
                point: GeoPoint {
                    latitude_deg: point.latitude_deg + closest_y / METRES_PER_DEGREE_LATITUDE,
                    longitude_deg: point.longitude_deg + closest_x / metres_per_degree_longitude,
                },
            };
        }
    }
    best
}

#[test]
fn bounded_scans_match_exhaustive_search_and_ambiguity_at_corridor_edges() {
    for latitude in [-89.9, 0.0, 50.0, 89.5] {
        let points = (0..80)
            .map(|index| {
                let angle = f64::from(index / 2) * 0.2; // Repeated vertices and crossings.
                GeoPoint {
                    latitude_deg: latitude + angle.sin() * 0.02,
                    longitude_deg: 30.0 + (angle * 2.0).sin() * 0.02,
                }
            })
            .collect();
        let route = RouteGeometry::new(points).unwrap();
        for index in 0..100 {
            let angle = f64::from(index) * 0.07;
            let query = GeoPoint {
                latitude_deg: latitude + angle.sin() * 0.025,
                longitude_deg: 30.0 + angle.cos() * 0.025,
            };
            let end = route.points.len() - 2;
            let best = exhaustive_projection(&route, query, 0, end);
            assert_eq!(route.project_range(query, 0, end), best);
            assert_eq!(
                route.project_range(query, 17, 29),
                exhaustive_projection(&route, query, 17, 29)
            );
            let rival = exhaustive_projection(&route, query, 37, 37);
            let edge = ((rival.offset_m - best.offset_m) / 2.0).max(0.001);
            for accuracy in [
                0.001,
                2.0,
                40.0,
                1000.0,
                edge.next_down(),
                edge,
                edge.next_up(),
                f64::MAX,
            ] {
                let distinct = (4.0 * accuracy).max(100.0);
                let ambiguous = (0..=end).any(|segment| {
                    let rival = exhaustive_projection(&route, query, segment, segment);
                    (rival.position_m - best.position_m).abs() > distinct
                        && rival.offset_m <= best.offset_m + 2.0 * accuracy
                });
                assert_eq!(
                    route.project_unambiguous(query, accuracy).unwrap(),
                    (!ambiguous).then_some(best)
                );
            }
        }
    }
}

#[test]
fn long_segments_crossing_the_query_survive_endpoint_bounds() {
    let query = GeoPoint {
        latitude_deg: 50.0,
        longitude_deg: 30.0,
    };
    let point = |north: f64, east: f64| GeoPoint {
        latitude_deg: query.latitude_deg + north,
        longitude_deg: query.longitude_deg + east,
    };
    for points in [
        vec![
            point(0.0, 0.0001),
            point(0.0, 0.0002),
            point(-0.01, 0.0),
            point(0.01, 0.0),
        ],
        vec![
            point(0.0001, 0.0),
            point(0.0002, 0.0),
            point(0.0, -0.01),
            point(0.0, 0.01),
        ],
    ] {
        let route = RouteGeometry::new(points).unwrap();
        let expected = exhaustive_projection(&route, query, 0, route.points.len() - 2);
        assert_eq!(expected.segment, 2);
        assert_eq!(
            route.project_range(query, 0, route.points.len() - 2),
            expected
        );
        assert_eq!(
            route.project_unambiguous(query, 1.0).unwrap(),
            Some(expected)
        );
    }
}

#[test]
fn equal_block_bounds_preserve_the_earliest_projection() {
    // Repeated laps give every full block identical bounds and equal-distance candidates.
    // Include a partial final block and exercise both indexed projection specializations.
    let points = (0..98)
        .map(|index| GeoPoint {
            latitude_deg: 50.0 + if index % 2 == 0 { 0.0 } else { 0.01 },
            longitude_deg: 30.0,
        })
        .collect();
    let route = RouteGeometry::new(points).unwrap();
    for longitude_deg in [30.0, 30.001, 30.1] {
        let query = GeoPoint {
            latitude_deg: 50.005,
            longitude_deg,
        };
        let expected = exhaustive_projection(&route, query, 0, route.points.len() - 2);
        assert_eq!(expected.segment, 0);
        for actual in [
            route.project_indexed::<false>(query, longitude_scale(query)),
            route.project_indexed::<true>(query, longitude_scale(query)),
        ] {
            assert_eq!(actual.unwrap(), expected);
        }
        assert_eq!(route.project_unambiguous(query, 2.0).unwrap(), None);
    }
}

#[test]
fn block_index_matches_exhaustive_search_on_long_winding_and_parallel_routes() {
    for parallel_return in [false, true] {
        let points: Vec<_> = (0..1_025)
            .map(|index| {
                let fraction = f64::from(index) / 1_024.0;
                let (north, east) = if parallel_return && index > 512 {
                    ((1.0 - fraction) * 0.04, 0.0004)
                } else {
                    (fraction * 0.04, (fraction * 12.0 * PI).sin() * 0.002)
                };
                GeoPoint {
                    latitude_deg: 50.0 + north,
                    longitude_deg: 30.0 + east,
                }
            })
            .collect();
        let route = RouteGeometry::new(points).unwrap();
        for index in 0..96 {
            let fraction = f64::from(index) / 95.0;
            let query = GeoPoint {
                latitude_deg: 50.0 + fraction * 0.04 + 0.0001,
                longitude_deg: 30.0 + (fraction * 19.0).cos() * 0.002,
            };
            let best = exhaustive_projection(&route, query, 0, route.points.len() - 2);
            for accuracy in [0.001_f64, 2.0, 40.0, 200.0] {
                let distinct = (4.0 * accuracy).max(100.0);
                let ambiguous = (0..route.points.len() - 1).any(|segment| {
                    let rival = exhaustive_projection(&route, query, segment, segment);
                    (rival.position_m - best.position_m).abs() > distinct
                        && rival.offset_m <= best.offset_m + 2.0 * accuracy
                });
                assert_eq!(
                    route.project_unambiguous(query, accuracy).unwrap(),
                    (!ambiguous).then_some(best),
                    "parallel_return={parallel_return} index={index} accuracy={accuracy}"
                );
            }
        }
    }
}

#[test]
fn projects_onto_local_segment_with_arc_length() {
    let route = route();
    let projection = route
        .project(
            GeoPoint {
                latitude_deg: 50.005,
                longitude_deg: 30.0001,
            },
            500.0,
            500.0,
            500.0,
            120.0,
        )
        .unwrap();
    assert_eq!(projection.segment, 0);
    assert!((projection.position_m - 556.0).abs() < 2.0);
    assert!(projection.offset_m < 10.0);
}

#[test]
fn global_fallback_finds_farther_route_section() {
    let route = route();
    let projection = route
        .project(
            GeoPoint {
                latitude_deg: 50.01,
                longitude_deg: 30.009,
            },
            100.0,
            50.0,
            50.0,
            20.0,
        )
        .unwrap();
    assert_eq!(projection.segment, 1);
    assert!(projection.offset_m < 1.0);
    assert!(projection.position_m > 1_500.0);
}

#[test]
fn indexed_global_fallback_matches_exhaustive_projection() {
    let points: Vec<_> = (0..1_025)
        .map(|index| {
            let fraction = f64::from(index) / 1_024.0;
            GeoPoint {
                latitude_deg: 50.0 + 0.04 * fraction,
                longitude_deg: 30.0 + (fraction * 12.0 * PI).sin() * 0.002,
            }
        })
        .collect();
    let route = RouteGeometry::new(points).unwrap();
    for query in (0..96)
        .map(|index| {
            let fraction = f64::from(index) / 95.0;
            GeoPoint {
                latitude_deg: 50.0 + 0.04 * fraction,
                longitude_deg: 30.02 + (fraction * 9.0 * PI).cos() * 0.002,
            }
        })
        .chain([
            GeoPoint {
                latitude_deg: 91.0,
                longitude_deg: 30.0,
            },
            GeoPoint {
                latitude_deg: 50.0,
                longitude_deg: 181.0,
            },
        ])
    {
        let local = exhaustive_projection(&route, query, 0, 0);
        let global = exhaustive_projection(&route, query, 0, route.points.len() - 2);
        let expected = if global.offset_m < local.offset_m {
            global
        } else {
            local
        };
        assert_eq!(route.project(query, 0.0, 0.0, 0.0, 0.0), Ok(expected));
    }
}

#[test]
fn rejects_invalid_coordinates_and_search_windows() {
    assert_eq!(
        RouteGeometry::new(vec![]).unwrap_err(),
        RouteError::TooShort
    );
    let route = route();
    assert_eq!(
        route.project(
            GeoPoint {
                latitude_deg: f64::NAN,
                longitude_deg: 30.0
            },
            0.0,
            0.0,
            1.0,
            1.0,
        ),
        Err(RouteError::InvalidSearch),
    );
}

#[test]
fn long_segment_clamps_to_arc_window_including_backward_motion() {
    let route = RouteGeometry::new(vec![
        GeoPoint {
            latitude_deg: 50.0,
            longitude_deg: 30.0,
        },
        GeoPoint {
            latitude_deg: 50.02,
            longitude_deg: 30.0,
        },
    ])
    .unwrap();
    // Independent oracle: on this meridian, latitude and arc-length fractions agree.
    for center in [0.0_f64, 0.25, 0.5, 1.0] {
        for radius in [0.0_f64, 0.1, 1.0] {
            for query in [0.9_f64, 0.6, 0.3, 0.0] {
                let expected = query.clamp((center - radius).max(0.0), (center + radius).min(1.0));
                let result = route
                    .project(
                        GeoPoint {
                            latitude_deg: 50.0 + 0.02 * query,
                            longitude_deg: 30.0,
                        },
                        center * route.length_m(),
                        radius * route.length_m(),
                        radius * route.length_m(),
                        1e9,
                    )
                    .unwrap();
                assert!((result.position_m - expected * route.length_m()).abs() < 1e-6);
                assert!((result.point.latitude_deg - (50.0 + 0.02 * expected)).abs() < 1e-10);
                assert!((result.offset_m - (query - expected).abs() * 0.02 * 110_540.0).abs() < 1e-6);
            }
        }
    }
}

#[test]
fn global_fallback_escapes_window_inside_a_single_segment() {
    let route = RouteGeometry::new(vec![
        GeoPoint {
            latitude_deg: 50.0,
            longitude_deg: 30.0,
        },
        GeoPoint {
            latitude_deg: 50.02,
            longitude_deg: 30.0,
        },
    ])
    .unwrap();
    let query = GeoPoint {
        latitude_deg: 50.018,
        longitude_deg: 30.0,
    };
    let local = route
        .project(query, route.length_m() * 0.5, 0.0, 0.0, 1e9)
        .unwrap();
    let global = route
        .project(query, route.length_m() * 0.5, 0.0, 0.0, 20.0)
        .unwrap();
    assert!((local.position_m - route.length_m() * 0.5).abs() < 1e-6);
    assert!((global.position_m - route.length_m() * 0.9).abs() < 1e-6);
    assert!(global.offset_m < 1e-6);
}

#[test]
fn repeated_vertices_loops_and_parallel_returns_respect_window() {
    for return_longitude in [30.0, 30.0001] {
        let route = RouteGeometry::new(vec![
            GeoPoint {
                latitude_deg: 50.0,
                longitude_deg: 30.0,
            },
            GeoPoint {
                latitude_deg: 50.02,
                longitude_deg: 30.0,
            },
            GeoPoint {
                latitude_deg: 50.02,
                longitude_deg: 30.0,
            },
            GeoPoint {
                latitude_deg: 50.02,
                longitude_deg: return_longitude,
            },
            GeoPoint {
                latitude_deg: 50.0,
                longitude_deg: return_longitude,
            },
        ])
        .unwrap();
        let first_length = route.cumulative_m[1];
        let result = route
            .project(
                GeoPoint {
                    latitude_deg: 50.005,
                    longitude_deg: return_longitude,
                },
                first_length * 0.8,
                first_length * 0.1,
                first_length * 0.1,
                1e9,
            )
            .unwrap();
        assert!((result.position_m - first_length * 0.7).abs() < 1e-6);
        assert_eq!(result.segment, 0);
    }
    let point = GeoPoint {
        latitude_deg: 50.0,
        longitude_deg: 30.0,
    };
    let route = RouteGeometry::new(vec![point, point, point]).unwrap();
    let result = route.project(point, 0.0, 0.0, 0.0, 1e9).unwrap();
    assert_eq!(result.position_m, 0.0);
    assert_eq!(result.offset_m, 0.0);
}
