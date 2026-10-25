//! Geographic helpers share metres and clockwise compass bearings with the navigation engine.
use serde::{Deserialize, Serialize};
#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq)]
pub struct Point {
    pub lat: f64,
    pub lon: f64,
    pub elevation: f64,
}
#[must_use]
pub fn longitude(value: f64) -> f64 {
    (value + 180.).rem_euclid(360.) - 180.
}
#[must_use]
pub fn distance(a: Point, b: Point) -> f64 {
    let dlat = (b.lat - a.lat).to_radians();
    let dlon = longitude(b.lon - a.lon).to_radians();
    let hav = (dlat / 2.).sin().powi(2)
        + a.lat.to_radians().cos() * b.lat.to_radians().cos() * (dlon / 2.).sin().powi(2);
    12_742_000. * hav.sqrt().min(1.).asin()
}
#[must_use]
pub fn bearing(a: Point, b: Point) -> f64 {
    let dlon = longitude(b.lon - a.lon).to_radians();
    (dlon.sin() * b.lat.to_radians().cos())
        .atan2(
            a.lat.to_radians().cos() * b.lat.to_radians().sin()
                - a.lat.to_radians().sin() * b.lat.to_radians().cos() * dlon.cos(),
        )
        .to_degrees()
        .rem_euclid(360.)
}
#[must_use]
pub fn interpolate(a: Point, b: Point, t: f64) -> Point {
    Point {
        lat: a.lat + (b.lat - a.lat) * t,
        lon: longitude(a.lon + longitude(b.lon - a.lon) * t),
        elevation: a.elevation + (b.elevation - a.elevation) * t,
    }
}
#[must_use]
pub fn project(p: Point, a: Point, b: Point) -> (Point, f64) {
    let scale = p.lat.to_radians().cos();
    let dx = longitude(b.lon - a.lon) * scale;
    let dy = b.lat - a.lat;
    let length = dx * dx + dy * dy;
    let fraction = if length > 0. {
        ((longitude(p.lon - a.lon) * scale * dx + (p.lat - a.lat) * dy) / length).clamp(0., 1.)
    } else {
        0.
    };
    (interpolate(a, b, fraction), fraction)
}
#[must_use]
pub fn valid(point: Point) -> bool {
    point.lat.is_finite()
        && point.lon.is_finite()
        && point.lat.abs() <= 90.
        && point.lon.abs() <= 180.
}
