//! Route-polyline geometry used by the native estimator.

use std::f64::consts::PI;
mod turns;
pub use turns::RouteTurn;

const EARTH_DIAMETER_M: f64 = 12_742_000.0;
const METRES_PER_DEGREE_LATITUDE: f64 = 110_540.0;
const METRES_PER_DEGREE_LONGITUDE_EQUATOR: f64 = 111_320.0;
// Endpoint bounds skip only segments safely outside an ambiguity corridor.
const RIVAL_PREFILTER_ROUNDING_MARGIN_M: f64 = 1.0;
// Bounds help near-route fixes; beyond this offset their checks cost more than they skip.
const MAX_USEFUL_ENDPOINT_BOUND_OFFSET_M: f64 = 300.0;
const SEGMENTS_PER_BLOCK: usize = 32;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct GeoPoint {
    pub latitude_deg: f64,
    pub longitude_deg: f64,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Projection {
    pub position_m: f64,
    pub offset_m: f64,
    pub segment: usize,
    pub point: GeoPoint,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RouteError {
    TooShort,
    InvalidCoordinate,
    InvalidSearch,
}

/// A conservative geographic envelope for consecutive segments, kept in route order.
#[derive(Clone, Debug, PartialEq)]
struct SegmentBlock {
    first: usize,
    last: usize,
    min_latitude_deg: f64,
    max_latitude_deg: f64,
    min_longitude_deg: f64,
    max_longitude_deg: f64,
}

impl SegmentBlock {
    /// Each axis independently bounds the distance to every point on the enclosed segments.
    fn lower_bound_components_m(&self, point: GeoPoint, longitude_scale: f64) -> (f64, f64) {
        let latitude_gap = if point.latitude_deg < self.min_latitude_deg {
            self.min_latitude_deg - point.latitude_deg
        } else {
            (point.latitude_deg - self.max_latitude_deg).max(0.0)
        };
        let longitude_gap = if self.max_longitude_deg - self.min_longitude_deg >= 180.0
            || (self.min_longitude_deg..=self.max_longitude_deg).contains(&point.longitude_deg)
        {
            // A block spanning the date line has a wrapped envelope. A zero lower bound is
            // conservative and cannot prune its short crossing segments as distant ones.
            0.0
        } else {
            longitude_delta(point.longitude_deg, self.min_longitude_deg)
                .abs()
                .min(longitude_delta(point.longitude_deg, self.max_longitude_deg).abs())
        };
        (
            latitude_gap * METRES_PER_DEGREE_LATITUDE,
            longitude_gap * longitude_scale,
        )
    }

    fn outside(&self, point: GeoPoint, longitude_scale: f64, limit_m: f64) -> bool {
        let (latitude_gap, longitude_gap) = self.lower_bound_components_m(point, longitude_scale);
        latitude_gap > limit_m || longitude_gap > limit_m
    }
}

#[derive(Clone, Debug)]
#[cfg_attr(any(test, kani), derive(PartialEq))]
pub struct RouteGeometry {
    points: Vec<GeoPoint>,
    cumulative_m: Vec<f64>,
    turns: Vec<RouteTurn>,
    blocks: Vec<SegmentBlock>,
}

impl RouteGeometry {
    /// Builds cumulative route geometry from at least two valid geographic points.
    ///
    /// # Errors
    ///
    /// Returns [`RouteError::TooShort`] for fewer than two points and
    /// [`RouteError::InvalidCoordinate`] for an invalid coordinate.
    #[cfg_attr(feature = "profiling", hotpath::measure(impl_type = "RouteGeometry"))]
    pub fn new(points: Vec<GeoPoint>) -> Result<Self, RouteError> {
        if points.len() < 2 {
            return Err(RouteError::TooShort);
        }
        if points.iter().any(|point| {
            !point.latitude_deg.is_finite()
                || !point.longitude_deg.is_finite()
                || !(-90.0..=90.0).contains(&point.latitude_deg)
                || !(-180.0..=180.0).contains(&point.longitude_deg)
        }) {
            return Err(RouteError::InvalidCoordinate);
        }
        let mut cumulative_m = Vec::with_capacity(points.len());
        cumulative_m.push(0.0);
        for index in 1..points.len() {
            let next = cumulative_m[index - 1] + distance_m(points[index - 1], points[index]);
            cumulative_m.push(next);
        }
        let mut route = Self {
            points,
            cumulative_m,
            turns: Vec::new(),
            blocks: Vec::new(),
        };
        route.turns = route.build_turns();
        route.blocks = route.build_blocks();
        Ok(route)
    }

    /// Build once off the main thread so repeated coarse fixes can reject distant segments.
    fn build_blocks(&self) -> Vec<SegmentBlock> {
        (0..self.points.len() - 1)
            .step_by(SEGMENTS_PER_BLOCK)
            .map(|first| {
                let last = (first + SEGMENTS_PER_BLOCK - 1).min(self.points.len() - 2);
                let mut block = SegmentBlock {
                    first,
                    last,
                    min_latitude_deg: f64::INFINITY,
                    max_latitude_deg: f64::NEG_INFINITY,
                    min_longitude_deg: f64::INFINITY,
                    max_longitude_deg: f64::NEG_INFINITY,
                };
                for &point in &self.points[first..=last + 1] {
                    block.min_latitude_deg = block.min_latitude_deg.min(point.latitude_deg);
                    block.max_latitude_deg = block.max_latitude_deg.max(point.latitude_deg);
                    block.min_longitude_deg = block.min_longitude_deg.min(point.longitude_deg);
                    block.max_longitude_deg = block.max_longitude_deg.max(point.longitude_deg);
                }
                block
            })
            .collect()
    }

    #[must_use]
    pub fn length_m(&self) -> f64 {
        *self.cumulative_m.last().unwrap_or(&0.0)
    }

    /// Distinct sharp turns indexed once with the immutable geometry, off the Android main thread.
    #[must_use]
    pub fn turns(&self) -> &[RouteTurn] {
        &self.turns
    }

    /// Projects a point onto a local route window, with an optional global fallback.
    ///
    /// # Errors
    ///
    /// Returns [`RouteError::InvalidSearch`] when a coordinate, search distance, or threshold is
    /// invalid, or [`RouteError::TooShort`] if the route has no indexed segments.
    #[cfg_attr(feature = "profiling", hotpath::measure(impl_type = "RouteGeometry"))]
    pub fn project(
        &self,
        point: GeoPoint,
        around_m: f64,
        behind_m: f64,
        ahead_m: f64,
        global_if_farther_m: f64,
    ) -> Result<Projection, RouteError> {
        if !point.latitude_deg.is_finite()
            || !point.longitude_deg.is_finite()
            || !around_m.is_finite()
            || !behind_m.is_finite()
            || !ahead_m.is_finite()
            || !global_if_farther_m.is_finite()
            || behind_m < 0.0
            || ahead_m < 0.0
            || global_if_farther_m < 0.0
        {
            return Err(RouteError::InvalidSearch);
        }
        let local = self.project_window(
            point,
            (around_m - behind_m).clamp(0.0, self.length_m()),
            (around_m + ahead_m).clamp(0.0, self.length_m()),
        );
        if local.offset_m > global_if_farther_m {
            let global = if (-90.0..=90.0).contains(&point.latitude_deg)
                && (-180.0..=180.0).contains(&point.longitude_deg)
            {
                self.project_indexed::<false>(point, longitude_scale(point))?
            } else {
                // Finite out-of-world queries cannot use geographic index bounds.
                self.project_range(point, 0, self.points.len() - 2)
            };
            if global.offset_m < local.offset_m {
                return Ok(global);
            }
        }
        Ok(local)
    }

    #[must_use]
    pub fn segment_at(&self, position_m: f64) -> usize {
        if position_m <= 0.0 {
            return 0;
        }
        if position_m >= self.length_m() {
            return self.points.len() - 2;
        }
        let mut low = 0;
        let mut high = self.points.len() - 1;
        while low < high - 1 {
            let middle = usize::midpoint(low, high);
            if self.cumulative_m[middle] <= position_m {
                low = middle;
            } else {
                high = middle;
            }
        }
        low
    }

    /// Globally projects a coarse fix only when its error corridor does not touch a distant
    /// route occurrence (loops, parallel returns or crossings). Adjacent segments are one match.
    ///
    /// # Errors
    /// Returns [`RouteError::InvalidSearch`] for invalid coordinates or uncertainty, or
    /// [`RouteError::TooShort`] if the route has no indexed segments.
    #[cfg_attr(feature = "profiling", hotpath::measure(impl_type = "RouteGeometry"))]
    pub fn project_unambiguous(
        &self,
        point: GeoPoint,
        accuracy_m: f64,
    ) -> Result<Option<Projection>, RouteError> {
        if !accuracy_m.is_finite()
            || accuracy_m <= 0.0
            || !(-90.0..=90.0).contains(&point.latitude_deg)
            || !(-180.0..=180.0).contains(&point.longitude_deg)
        {
            return Err(RouteError::InvalidSearch);
        }
        // Every rival uses the same query latitude; recomputing its cosine per segment dominates
        // dense-route scans. Keep the same arithmetic and comparisons, just reuse the scale.
        let longitude_scale = longitude_scale(point);
        let best = self.project_indexed::<true>(point, longitude_scale)?;
        let corridor_m = best.offset_m + 2.0 * accuracy_m;
        let distinct_distance_m = (4.0 * accuracy_m).max(100.0);
        let latitude_limit_m = corridor_m + RIVAL_PREFILTER_ROUNDING_MARGIN_M;
        for block in &self.blocks {
            if block.outside(point, longitude_scale, latitude_limit_m) {
                continue;
            }
            for segment in block.first..=block.last {
                // A segment whose endpoints are both beyond the same latitude bound cannot
                // intersect the corridor. Keep the original projection for every candidate that
                // might matter, preserving its rounding, ordering and ambiguity decision.
                let start_y = (self.points[segment].latitude_deg - point.latitude_deg)
                    * METRES_PER_DEGREE_LATITUDE;
                let end_y = (self.points[segment + 1].latitude_deg - point.latitude_deg)
                    * METRES_PER_DEGREE_LATITUDE;
                if (start_y > latitude_limit_m && end_y > latitude_limit_m)
                    || (start_y < -latitude_limit_m && end_y < -latitude_limit_m)
                {
                    continue;
                }
                let rival = self.project_range_scaled::<false>(
                    point,
                    segment,
                    segment,
                    longitude_scale,
                    corridor_m,
                );
                if (rival.position_m - best.position_m).abs() > distinct_distance_m
                    && rival.offset_m <= corridor_m
                {
                    return Ok(None);
                }
            }
        }
        Ok(Some(best))
    }

    /// Seed a global search, then reject only blocks that cannot beat its current best.
    /// Visiting surviving blocks in route order preserves the earliest exact-distance tie.
    fn project_indexed<const ENDPOINT_BOUNDS: bool>(
        &self,
        point: GeoPoint,
        longitude_scale: f64,
    ) -> Result<Projection, RouteError> {
        let seed_block = self
            .blocks
            .iter()
            .map(|block| {
                let (latitude, longitude) = block.lower_bound_components_m(point, longitude_scale);
                (block, latitude * latitude + longitude * longitude)
            })
            // Carry the winning bound through the reduction instead of recalculating it for
            // every comparison. min_by keeps the first equal bound, as before.
            .min_by(|(_, left), (_, right)| left.total_cmp(right))
            .map(|(block, _)| block)
            .ok_or(RouteError::TooShort)?;
        let mut best = self.project_range_scaled::<ENDPOINT_BOUNDS>(
            point,
            seed_block.first,
            seed_block.last,
            longitude_scale,
            f64::MAX,
        );
        for block in &self.blocks {
            if block.first == seed_block.first {
                continue;
            }
            let limit_m = best.offset_m + RIVAL_PREFILTER_ROUNDING_MARGIN_M;
            if block.outside(point, longitude_scale, limit_m) {
                continue;
            }
            let candidate = self.project_range_scaled::<ENDPOINT_BOUNDS>(
                point,
                block.first,
                block.last,
                longitude_scale,
                best.offset_m,
            );
            if candidate.offset_m < best.offset_m
                || (candidate.offset_m.total_cmp(&best.offset_m).is_eq()
                    && candidate.segment < best.segment)
            {
                best = candidate;
            }
        }
        Ok(best)
    }

    /// Clip endpoint segments as well as segment indices to the permitted arc lengths.
    fn project_window(&self, point: GeoPoint, start_m: f64, end_m: f64) -> Projection {
        let scale = longitude_scale(point);
        let from = self.segment_at(start_m);
        let to = self.segment_at(end_m);
        let mut best = Projection {
            position_m: start_m,
            offset_m: f64::MAX,
            segment: from,
            point: self.points[from],
        };
        for index in from..=to {
            let mut candidate =
                self.project_range_scaled::<false>(point, index, index, scale, f64::MAX);
            if candidate.position_m < start_m || candidate.position_m > end_m {
                candidate.position_m = candidate.position_m.clamp(start_m, end_m);
                let length = self.cumulative_m[index + 1] - self.cumulative_m[index];
                let fraction = if length > 0.0 {
                    ((candidate.position_m - self.cumulative_m[index]) / length).clamp(0.0, 1.0)
                } else {
                    0.0
                };
                let start = self.points[index];
                let end = self.points[index + 1];
                candidate.point = GeoPoint {
                    latitude_deg: start.latitude_deg
                        + fraction * (end.latitude_deg - start.latitude_deg),
                    longitude_deg: normalize_longitude(
                        start.longitude_deg
                            + fraction * longitude_delta(start.longitude_deg, end.longitude_deg),
                    ),
                };
                candidate.offset_m = ((candidate.point.latitude_deg - point.latitude_deg)
                    * METRES_PER_DEGREE_LATITUDE)
                    .hypot(
                        longitude_delta(point.longitude_deg, candidate.point.longitude_deg) * scale,
                    );
            }
            if candidate.offset_m < best.offset_m {
                best = candidate;
            }
        }
        best
    }

    fn project_range(&self, point: GeoPoint, from: usize, to: usize) -> Projection {
        self.project_range_scaled::<false>(point, from, to, longitude_scale(point), f64::MAX)
    }

    /// The longitude scale belongs to the query point, so a multi-segment scan can share it.
    fn project_range_scaled<const ENDPOINT_BOUNDS: bool>(
        &self,
        point: GeoPoint,
        from: usize,
        to: usize,
        metres_per_degree_longitude: f64,
        max_offset_m: f64,
    ) -> Projection {
        let mut best = Projection {
            position_m: 0.0,
            offset_m: f64::MAX,
            segment: from,
            point: self.points[from],
        };
        for index in from..=to.min(self.points.len() - 2) {
            let start_y =
                (self.points[index].latitude_deg - point.latitude_deg) * METRES_PER_DEGREE_LATITUDE;
            // Once a contender exists, a segment entirely beyond the same coordinate bound
            // cannot beat it. The margin covers endpoint-vs-interpolation rounding; segments
            // that might tie still take the original projection path in their original order.
            if ENDPOINT_BOUNDS && best.offset_m <= MAX_USEFUL_ENDPOINT_BOUND_OFFSET_M {
                let limit_m = best.offset_m + RIVAL_PREFILTER_ROUNDING_MARGIN_M;
                let end_y = (self.points[index + 1].latitude_deg - point.latitude_deg)
                    * METRES_PER_DEGREE_LATITUDE;
                if (start_y > limit_m && end_y > limit_m)
                    || (start_y < -limit_m && end_y < -limit_m)
                {
                    continue;
                }
            }
            let start_x =
                segment_start_longitude(point, self.points[index], self.points[index + 1])
                    * metres_per_degree_longitude;
            let direction_x = longitude_delta(
                self.points[index].longitude_deg,
                self.points[index + 1].longitude_deg,
            ) * metres_per_degree_longitude;
            if ENDPOINT_BOUNDS && best.offset_m <= MAX_USEFUL_ENDPOINT_BOUND_OFFSET_M {
                let limit_m = best.offset_m + RIVAL_PREFILTER_ROUNDING_MARGIN_M;
                let end_x = start_x + direction_x;
                if (start_x > limit_m && end_x > limit_m)
                    || (start_x < -limit_m && end_x < -limit_m)
                {
                    continue;
                }
            }
            let direction_y = (self.points[index + 1].latitude_deg
                - self.points[index].latitude_deg)
                * METRES_PER_DEGREE_LATITUDE;
            let length_squared = direction_x * direction_x + direction_y * direction_y;
            let fraction = if length_squared < 1.0e-6 {
                0.0
            } else {
                ((-start_x * direction_x - start_y * direction_y) / length_squared).clamp(0.0, 1.0)
            };
            let closest_x = start_x + direction_x * fraction;
            let closest_y = start_y + direction_y * fraction;
            // Each component bounds the distance from below. Keep a rounding margin and the
            // original hypot/comparisons for all contenders, including equal-distance ties.
            // An empty bounded scan returns the MAX sentinel, outside any finite corridor.
            let limit_m = best.offset_m.min(max_offset_m).next_up();
            if closest_x.abs() > limit_m || closest_y.abs() > limit_m {
                continue;
            }
            let offset_m = closest_x.hypot(closest_y);
            if offset_m < best.offset_m {
                best = Projection {
                    position_m: self.cumulative_m[index]
                        + fraction * (self.cumulative_m[index + 1] - self.cumulative_m[index]),
                    offset_m,
                    segment: index,
                    point: GeoPoint {
                        latitude_deg: point.latitude_deg + closest_y / METRES_PER_DEGREE_LATITUDE,
                        longitude_deg: normalize_longitude(
                            point.longitude_deg + closest_x / metres_per_degree_longitude,
                        ),
                    },
                };
            }
        }
        best
    }
}

/// Keep ordinary-coordinate arithmetic unchanged and wrap only a real date-line crossing.
fn normalize_longitude(longitude: f64) -> f64 {
    if (-180.0..=180.0).contains(&longitude) {
        longitude
    } else {
        (longitude + 180.0).rem_euclid(360.0) - 180.0
    }
}

fn longitude_delta(from: f64, to: f64) -> f64 {
    normalize_longitude(to - from)
}

/// Align the short segment to its midpoint's nearest longitude branch around the query.
fn segment_start_longitude(query: GeoPoint, start: GeoPoint, end: GeoPoint) -> f64 {
    let delta = longitude_delta(query.longitude_deg, start.longitude_deg);
    let center = delta + longitude_delta(start.longitude_deg, end.longitude_deg) / 2.0;
    delta
        - if center > 180.0 {
            360.0
        } else if center < -180.0 {
            -360.0
        } else {
            0.0
        }
}

/// Local tangent-plane scale at the observation latitude, identical for every candidate segment.
fn longitude_scale(point: GeoPoint) -> f64 {
    METRES_PER_DEGREE_LONGITUDE_EQUATOR * (point.latitude_deg * PI / 180.0).cos()
}

fn distance_m(first: GeoPoint, second: GeoPoint) -> f64 {
    let half_delta_latitude = (second.latitude_deg - first.latitude_deg) * PI / 360.0;
    let half_delta_longitude = (second.longitude_deg - first.longitude_deg) * PI / 360.0;
    // Roundoff at antipodes must not make the square-root domain negative.
    let haversine = (half_delta_latitude.sin().powi(2)
        + half_delta_longitude.sin().powi(2)
            * (first.latitude_deg * PI / 180.0).cos()
            * (second.latitude_deg * PI / 180.0).cos())
    .clamp(0.0, 1.0);
    haversine.sqrt().atan2((1.0 - haversine).sqrt()) * EARTH_DIAMETER_M
}

#[cfg(test)]
mod tests;

#[cfg(any(test, kani))]
pub(crate) mod kani_proofs;
