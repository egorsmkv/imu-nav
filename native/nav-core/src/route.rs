//! Route-polyline geometry used by the native estimator.

use std::f64::consts::PI;

const EARTH_DIAMETER_M: f64 = 12_742_000.0;
const METRES_PER_DEGREE_LATITUDE: f64 = 110_540.0;
const METRES_PER_DEGREE_LONGITUDE_EQUATOR: f64 = 111_320.0;

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

#[derive(Clone, Debug)]
pub struct RouteGeometry {
    points: Vec<GeoPoint>,
    cumulative_m: Vec<f64>,
}

impl RouteGeometry {
    /// Builds cumulative route geometry from at least two valid geographic points.
    ///
    /// # Errors
    ///
    /// Returns [`RouteError::TooShort`] for fewer than two points and
    /// [`RouteError::InvalidCoordinate`] for an invalid coordinate.
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
        Ok(Self {
            points,
            cumulative_m,
        })
    }

    #[must_use]
    pub fn length_m(&self) -> f64 {
        *self.cumulative_m.last().unwrap_or(&0.0)
    }

    /// Projects a point onto a local route window, with an optional global fallback.
    ///
    /// # Errors
    ///
    /// Returns [`RouteError::InvalidSearch`] when a coordinate, search distance, or threshold is
    /// invalid.
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
        let local = self.project_range(
            point,
            self.segment_at(around_m - behind_m),
            self.segment_at(around_m + ahead_m),
        );
        if local.offset_m > global_if_farther_m {
            let global = self.project_range(point, 0, self.points.len() - 2);
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

    fn project_range(&self, point: GeoPoint, from: usize, to: usize) -> Projection {
        let metres_per_degree_longitude =
            METRES_PER_DEGREE_LONGITUDE_EQUATOR * (point.latitude_deg * PI / 180.0).cos();
        let mut best = Projection {
            position_m: 0.0,
            offset_m: f64::MAX,
            segment: from,
            point: self.points[from],
        };
        for index in from..=to.min(self.points.len() - 2) {
            let start_x = (self.points[index].longitude_deg - point.longitude_deg)
                * metres_per_degree_longitude;
            let start_y =
                (self.points[index].latitude_deg - point.latitude_deg) * METRES_PER_DEGREE_LATITUDE;
            let direction_x = (self.points[index + 1].longitude_deg
                - self.points[index].longitude_deg)
                * metres_per_degree_longitude;
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
            let offset_m = closest_x.hypot(closest_y);
            if offset_m < best.offset_m {
                best = Projection {
                    position_m: self.cumulative_m[index]
                        + fraction * (self.cumulative_m[index + 1] - self.cumulative_m[index]),
                    offset_m,
                    segment: index,
                    point: GeoPoint {
                        latitude_deg: point.latitude_deg + closest_y / METRES_PER_DEGREE_LATITUDE,
                        longitude_deg: point.longitude_deg
                            + closest_x / metres_per_degree_longitude,
                    },
                };
            }
        }
        best
    }
}

fn distance_m(first: GeoPoint, second: GeoPoint) -> f64 {
    let half_delta_latitude = (second.latitude_deg - first.latitude_deg) * PI / 360.0;
    let half_delta_longitude = (second.longitude_deg - first.longitude_deg) * PI / 360.0;
    let haversine = half_delta_latitude.sin().powi(2)
        + half_delta_longitude.sin().powi(2)
            * (first.latitude_deg * PI / 180.0).cos()
            * (second.latitude_deg * PI / 180.0).cos();
    haversine.sqrt().atan2((1.0 - haversine).sqrt()) * EARTH_DIAMETER_M
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
