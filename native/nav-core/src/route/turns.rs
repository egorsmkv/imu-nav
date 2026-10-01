//! Sharp-turn landmarks with straight approaches; broad curves and complex clusters are excluded.
use super::{GeoPoint, RouteGeometry};

const APPROACH_M: f64 = 40.0;
const INNER_M: f64 = 10.0;
const MIDDLE_M: f64 = 25.0;
const STRAIGHT_TOLERANCE_DEG: f64 = 10.0;
const MIN_ANGLE_DEG: f64 = 45.0;
const MAX_ANGLE_DEG: f64 = 125.0;
const CLUSTER_GAP_M: f64 = 30.0;
const MAX_CLUSTER_M: f64 = 40.0;

/// Approximate midpoint of a compact route corner; positive angle is a right turn.
#[derive(Clone, Copy, Debug)]
pub struct RouteTurn {
    pub position_m: f64,
    pub angle_deg: f64,
}

struct Cluster {
    first_m: f64,
    last_m: f64,
    angle_deg: f64,
    mixed: bool,
}

impl Cluster {
    fn finish(self, output: &mut Vec<RouteTurn>) {
        if !self.mixed && self.last_m - self.first_m <= MAX_CLUSTER_M {
            output.push(RouteTurn {
                position_m: f64::midpoint(self.first_m, self.last_m),
                angle_deg: self.angle_deg,
            });
        }
    }
}

impl RouteGeometry {
    /// Geometry-only evidence, independent of instructions or the Kotlin engine's corrections.
    pub(super) fn build_turns(&self) -> Vec<RouteTurn> {
        let mut output = Vec::new();
        let mut cluster: Option<Cluster> = None;
        for &position_m in &self.cumulative_m {
            if position_m < APPROACH_M || position_m + APPROACH_M > self.length_m() {
                continue;
            }
            let before = [
                self.point_at(position_m - APPROACH_M),
                self.point_at(position_m - MIDDLE_M),
                self.point_at(position_m - INNER_M),
            ];
            let after = [
                self.point_at(position_m + INNER_M),
                self.point_at(position_m + MIDDLE_M),
                self.point_at(position_m + APPROACH_M),
            ];
            if delta(bearing(before[0], before[1]), bearing(before[1], before[2])).abs()
                > STRAIGHT_TOLERANCE_DEG
                || delta(bearing(after[0], after[1]), bearing(after[1], after[2])).abs()
                    > STRAIGHT_TOLERANCE_DEG
            {
                continue;
            }
            let angle_deg = delta(bearing(before[0], before[2]), bearing(after[0], after[2]));
            if !(MIN_ANGLE_DEG..=MAX_ANGLE_DEG).contains(&angle_deg.abs()) {
                continue;
            }
            if cluster
                .as_ref()
                .is_some_and(|previous| position_m - previous.last_m > CLUSTER_GAP_M)
                && let Some(previous) = cluster.take()
            {
                previous.finish(&mut output);
            }
            if let Some(previous) = &mut cluster {
                previous.last_m = position_m;
                previous.mixed |= previous.angle_deg.signum() != angle_deg.signum();
                if angle_deg.abs() > previous.angle_deg.abs() {
                    previous.angle_deg = angle_deg;
                }
            } else {
                cluster = Some(Cluster {
                    first_m: position_m,
                    last_m: position_m,
                    angle_deg,
                    mixed: false,
                });
            }
        }
        if let Some(previous) = cluster {
            previous.finish(&mut output);
        }
        output
    }

    fn point_at(&self, position_m: f64) -> GeoPoint {
        let index = self.segment_at(position_m);
        let span = self.cumulative_m[index + 1] - self.cumulative_m[index];
        let fraction = if span > 0.0 {
            ((position_m - self.cumulative_m[index]) / span).clamp(0.0, 1.0)
        } else {
            0.0
        };
        let from = self.points[index];
        let to = self.points[index + 1];
        GeoPoint {
            latitude_deg: from.latitude_deg + fraction * (to.latitude_deg - from.latitude_deg),
            longitude_deg: from.longitude_deg + fraction * (to.longitude_deg - from.longitude_deg),
        }
    }
}

fn bearing(from: GeoPoint, to: GeoPoint) -> f64 {
    let east = (to.longitude_deg - from.longitude_deg) * from.latitude_deg.to_radians().cos();
    let north = to.latitude_deg - from.latitude_deg;
    east.atan2(north).to_degrees()
}

fn delta(from: f64, to: f64) -> f64 {
    (to - from + 180.0).rem_euclid(360.0) - 180.0
}
