//! Route geometry and language-independent OSRM-style maneuver records for the existing app.
// Coordinates, grid cells, speeds and file indices have explicit bounds in the importer/loader.
// Integer-to-float conversions here stay below the exact-integer range of f64.
#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss
)]
use crate::ids::StateId;
use crate::{
    Result, check,
    geo::{self, Point},
    query::{self, Snap},
    storage::{LOCAL_ACCESS, LOCAL_EXIT, Network},
};
use serde::{Deserialize, Serialize};
use std::sync::atomic::AtomicBool;
#[derive(Deserialize)]
pub struct Request {
    pub points: Vec<[f64; 2]>,
}
#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct Step {
    pub r#type: String,
    pub modifier: Option<String>,
    pub name: String,
    pub geometry_index: usize,
    pub distance_m: f64,
    pub duration_s: f64,
    pub roundabout_exit: Option<u32>,
}
#[derive(Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Route {
    pub geometry: Vec<[f64; 2]>,
    pub steps: Vec<Step>,
    pub duration_s: f64,
    pub maxspeed_kmh: Vec<Option<u16>>,
    pub segment_speed_mps: Vec<f64>,
    pub elevation_m: Option<Vec<f64>>,
}
impl Route {
    fn point(&mut self, point: Point, speed: f64, limit: u16) {
        if self.geometry.last().is_some_and(|p| {
            p[0].to_bits() == point.lat.to_bits() && p[1].to_bits() == point.lon.to_bits()
        }) {
            return;
        }
        if let Some(previous) = self.geometry.last() {
            let meters = geo::distance(
                Point {
                    lat: previous[0],
                    lon: previous[1],
                    elevation: 0.,
                },
                point,
            );
            self.duration_s += meters / speed;
            self.segment_speed_mps.push(speed);
            self.maxspeed_kmh.push((limit > 0).then_some(limit));
        }
        self.geometry.push([point.lat, point.lon]);
        if let Some(heights) = &mut self.elevation_m {
            if point.elevation.is_finite() {
                heights.push(point.elevation);
            } else {
                self.elevation_m = None;
            }
        }
    }
    fn step(&mut self, kind: &str, modifier: Option<&str>, name: &str, exit: Option<u32>) {
        self.steps.push(Step {
            r#type: kind.into(),
            modifier: modifier.map(str::to_owned),
            name: name.into(),
            geometry_index: self.geometry.len().saturating_sub(1),
            distance_m: 0.,
            duration_s: 0.,
            roundabout_exit: exit,
        });
    }
    pub(crate) fn lengths(&mut self) {
        for i in 0..self.steps.len() {
            let end = self
                .steps
                .get(i + 1)
                .map_or(self.geometry.len().saturating_sub(1), |step| {
                    step.geometry_index
                });
            for segment in self.steps[i].geometry_index..end {
                let a = self.geometry[segment];
                let b = self.geometry[segment + 1];
                let distance = geo::distance(
                    Point {
                        lat: a[0],
                        lon: a[1],
                        elevation: 0.,
                    },
                    Point {
                        lat: b[0],
                        lon: b[1],
                        elevation: 0.,
                    },
                );
                self.steps[i].distance_m += distance;
                self.steps[i].duration_s += distance / self.segment_speed_mps[segment];
            }
        }
    }
}
fn modifier(angle: f64) -> &'static str {
    if angle.abs() > 150. {
        "uturn"
    } else if angle > 100. {
        "sharp right"
    } else if angle > 45. {
        "right"
    } else if angle > 15. {
        "slight right"
    } else if angle < -100. {
        "sharp left"
    } else if angle < -45. {
        "left"
    } else if angle < -15. {
        "slight left"
    } else {
        "straight"
    }
}
pub(crate) fn append(
    network: &Network,
    route: &mut Route,
    path: &[StateId],
    source: &Snap,
    target: &Snap,
    cancel: &AtomicBool,
) -> Result<()> {
    let mut roundabout_step = None;
    let mut exits = 0u32;
    for (position, &id) in path.iter().enumerate() {
        check(cancel)?;
        if route.geometry.len() > 500_000 {
            return Err("route geometry exceeds limit".into());
        }
        let state = network.state(id);
        let start = if position == 0 {
            source.fraction * state.distance
        } else {
            0.
        };
        let end = if position + 1 == path.len() {
            target.fraction * state.distance
        } else {
            state.distance
        };
        let speed = f64::from(state.speed);
        if position == 0 {
            route.point(source.point, speed, state.maxspeed);
            if route.steps.is_empty() {
                route.step("depart", None, &network.name(state), None);
            }
        } else {
            let previous = network.state(path[position - 1]);
            let a = network.point(previous, previous.point_count - 2);
            let junction = network.point(state, 0);
            let b = network.point(state, 1);
            let angle = geo::longitude(geo::bearing(junction, b) - geo::bearing(a, junction));
            if state.flags & 1 != 0 && previous.flags & 1 == 0 {
                route.step(
                    "roundabout",
                    Some(modifier(angle)),
                    &network.name(state),
                    None,
                );
                roundabout_step = Some(route.steps.len() - 1);
                exits = 0;
            } else if previous.flags & 1 != 0 && state.flags & 1 == 0 {
                exits += 1;
                if let Some(index) = roundabout_step.take() {
                    route.steps[index].roundabout_exit = Some(exits);
                }
                route.step(
                    "exit roundabout",
                    Some(modifier(angle)),
                    &network.name(state),
                    None,
                );
            } else if state.flags & 1 == 0
                && (angle.abs() > 25. || network.name(previous) != network.name(state))
            {
                let kind = if state.flags & 4 != 0 && previous.flags & 4 == 0 {
                    "on ramp"
                } else if state.flags & 4 == 0 && previous.flags & 4 != 0 {
                    "merge"
                } else if angle.abs() > 25. {
                    "turn"
                } else {
                    "new name"
                };
                route.step(kind, Some(modifier(angle)), &network.name(state), None);
            }
            if previous.flags & 1 != 0
                && state.flags & 1 != 0
                && network.from_node(state.from).any(|candidate| {
                    let exit = network.state(candidate);
                    exit.flags & 1 == 0 && exit.to != previous.from
                })
            {
                exits += 1;
            }
        }
        let mut along = 0.;
        for index in 1..state.point_count {
            let a = network.point(state, index - 1);
            let b = network.point(state, index);
            let length = geo::distance(a, b);
            let next = along + length;
            if next > start && along < end {
                let left = ((start - along) / length.max(1e-9)).clamp(0., 1.);
                let right = ((end - along) / length.max(1e-9)).clamp(0., 1.);
                if left > 0. {
                    route.point(geo::interpolate(a, b, left), speed, state.maxspeed);
                }
                route.point(geo::interpolate(a, b, right), speed, state.maxspeed);
            }
            along = next;
        }
    }
    Ok(())
}
/// # Errors
/// Returns malformed input, unavailable graph data, I/O, or request cancellation errors.
pub fn route(network: &Network, request: &Request, cancel: &AtomicBool) -> Result<Route> {
    if !(2..=100).contains(&request.points.len()) {
        return Err("route needs 2 to 100 points".into());
    }
    check(cancel)?;
    let mut result = Route {
        elevation_m: Some(vec![]),
        ..Route::default()
    };
    let mut start = query::snap(
        network,
        Point {
            lat: request.points[0][0],
            lon: request.points[0][1],
            elevation: 0.,
        },
        cancel,
    )?;
    start.retain(|s| network.state(s.state).flags & 16 == 0);
    for point in &request.points[1..] {
        let end = query::snap(
            network,
            Point {
                lat: point[0],
                lon: point[1],
                elevation: 0.,
            },
            cancel,
        )?;
        let (path, source, target) = query::between(network, &start, &end, cancel)?;
        append(network, &mut result, &path, &source, &target, cancel)?;
        // An explicit stop authorizes leaving its local-access component. Its adjacent twin
        // retains exactly the same restriction history, geometry and clipping fraction.
        let mut departure = target;
        let state = network.state(departure.state);
        if state.flags & LOCAL_ACCESS != 0 && state.flags & LOCAL_EXIT == 0 {
            departure.state = StateId::new(
                departure
                    .state
                    .get()
                    .checked_add(1)
                    .ok_or("local-access state overflow")?,
            );
            let twin = network.state(departure.state);
            if state.flags ^ twin.flags != LOCAL_EXIT
                || state.from != twin.from
                || state.to != twin.to
                || state.geometry != twin.geometry
            {
                return Err("invalid local-access twin".into());
            }
        }
        start = vec![departure];
    }
    result.step("arrive", None, "", None);
    result.lengths();
    Ok(result)
}
