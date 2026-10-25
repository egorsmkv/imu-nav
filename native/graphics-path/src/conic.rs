//! Conic → quadratic Bézier approximation, ported from AndroidX `Conic.cpp` (itself from Skia).
//!
//! A conic is a rational quadratic curve with a weight (circles and ellipse arcs are conics).
//! Compose wants plain quadratics, so each conic is split in halves `2^count` times until the
//! estimated error is within the tolerance. Float operations follow the C++ order exactly so
//! results match the reference bit for bit.

use crate::Point;

/// At most 2^5 = 32 quadratics per conic.
pub const MAX_CONIC_TO_QUAD_COUNT: u32 = 5;
pub const MAX_QUADRATIC_COUNT: usize = 1 << MAX_CONIC_TO_QUAD_COUNT;
/// First point plus two points per quadratic.
pub const MAX_QUADRATIC_POINTS: usize = 1 + 2 * MAX_QUADRATIC_COUNT;

#[derive(Clone, Copy, Default)]
struct Conic {
    points: [Point; 3],
    weight: f32,
}

/// Skia's finiteness test: a product with 0 stays 0 unless some value is infinite or NaN.
fn all_finite(values: impl IntoIterator<Item = f32>) -> bool {
    let mut product = 0.0f32;
    for value in values {
        product *= value;
    }
    product == 0.0
}

fn point_finite(point: Point) -> bool {
    all_finite([point.x, point.y])
}

fn points_finite(points: &[Point]) -> bool {
    all_finite(points.iter().flat_map(|p| [p.x, p.y]))
}

/// True when `b` lies between `a` and `c` (inclusive), in either order.
fn between(a: f32, b: f32, c: f32) -> bool {
    (a - b) * (c - b) <= 0.0
}

fn can_normalize(dx: f32, dy: f32) -> bool {
    dx.is_finite() && dy.is_finite() && (dx != 0.0 || dy != 0.0)
}

fn equals(a: Point, b: Point) -> bool {
    !can_normalize(a.x - b.x, a.y - b.y)
}

impl Conic {
    /// How many halvings (0..=5) bring the error estimate within `tolerance`.
    fn quadratic_count(&self, tolerance: f32) -> u32 {
        if tolerance <= 0.0 || !tolerance.is_finite() || !points_finite(&self.points) {
            return 0;
        }
        let [p0, p1, p2] = self.points;
        let a = self.weight - 1.0;
        let k = a / (4.0 * (2.0 + a));
        let x = k * (p0.x - 2.0 * p1.x + p2.x);
        let y = k * (p0.y - 2.0 * p1.y + p2.y);
        let mut error = (x * x + y * y).sqrt();
        let mut count = 0;
        while count < MAX_CONIC_TO_QUAD_COUNT {
            if error <= tolerance {
                break;
            }
            error *= 0.25;
            count += 1;
        }
        count
    }

    /// Split at t = 0.5 into two conics with a shared middle point.
    fn split(&self) -> [Conic; 2] {
        let weight = self.weight;
        let scale = 1.0 / (1.0 + weight);
        let new_weight = (0.5 + weight * 0.5).sqrt();
        let [p0, p1, p2] = self.points;
        let wp1 = Point { x: weight * p1.x, y: weight * p1.y };
        let mut middle = Point {
            x: (p0.x + (wp1.x + wp1.x) + p2.x) * scale * 0.5,
            y: (p0.y + (wp1.y + wp1.y) + p2.y) * scale * 0.5,
        };
        if !point_finite(middle) {
            // Retry in double precision when the float sum overflows.
            let w = f64::from(weight);
            let w2 = w * 2.0;
            let half = 1.0 / (1.0 + w) * 0.5;
            middle.x = ((f64::from(p0.x) + w2 * f64::from(p1.x) + f64::from(p2.x)) * half) as f32;
            middle.y = ((f64::from(p0.y) + w2 * f64::from(p1.y) + f64::from(p2.y)) * half) as f32;
        }
        let first = Conic {
            points: [p0, Point { x: (p0.x + wp1.x) * scale, y: (p0.y + wp1.y) * scale }, middle],
            weight: new_weight,
        };
        let second = Conic {
            points: [middle, Point { x: (wp1.x + p2.x) * scale, y: (wp1.y + p2.y) * scale }, p2],
            weight: new_weight,
        };
        [first, second]
    }

    /// Write `2^count` quadratics as `1 + 2 * 2^count` points and return the quadratic count.
    fn split_into_quadratics(&self, out: &mut [Point; MAX_QUADRATIC_POINTS], mut count: u32) -> usize {
        out[0] = self.points[0];
        let mut done = false;
        if count >= MAX_CONIC_TO_QUAD_COUNT {
            let halves = self.split();
            if equals(halves[0].points[1], halves[0].points[2]) && equals(halves[1].points[0], halves[1].points[1]) {
                // Both halves are straight: one quadratic is enough.
                out[1] = halves[0].points[1];
                out[2] = halves[0].points[1];
                out[3] = halves[0].points[1];
                out[4] = halves[1].points[2];
                count = 1;
                done = true;
            }
        }
        if !done {
            subdivide(self, out, 1, count);
        }
        let quadratics = 1usize << count;
        let points = 2 * quadratics + 1;
        if !points_finite(&out[..points]) {
            for point in &mut out[1..points - 1] {
                *point = self.points[1];
            }
        }
        quadratics
    }
}

/// Recursive halving that keeps monotonic-in-y conics monotonic (avoids tiny overshoots).
fn subdivide(src: &Conic, out: &mut [Point; MAX_QUADRATIC_POINTS], at: usize, level: u32) -> usize {
    if level == 0 {
        out[at] = src.points[1];
        out[at + 1] = src.points[2];
        return at + 2;
    }
    let mut halves = src.split();
    let start_y = src.points[0].y;
    let end_y = src.points[2].y;
    if between(start_y, src.points[1].y, end_y) {
        let mid_y = halves[0].points[2].y;
        if !between(start_y, mid_y, end_y) {
            let closer = if (mid_y - start_y).abs() < (mid_y - end_y).abs() { start_y } else { end_y };
            halves[0].points[2].y = closer;
            halves[1].points[0].y = closer;
        }
        if !between(start_y, halves[0].points[1].y, halves[0].points[2].y) {
            halves[0].points[1].y = start_y;
        }
        if !between(halves[1].points[0].y, halves[1].points[1].y, end_y) {
            halves[1].points[1].y = end_y;
        }
    }
    let at = subdivide(&halves[0], out, at, level - 1);
    subdivide(&halves[1], out, at, level - 1)
}

/// Holds the quadratics of the most recent conic, like the C++ `ConicConverter`.
pub struct ConicConverter {
    count: usize,
    storage: [Point; MAX_QUADRATIC_POINTS],
}

impl Default for ConicConverter {
    fn default() -> Self {
        Self { count: 0, storage: [Point::default(); MAX_QUADRATIC_POINTS] }
    }
}

impl ConicConverter {
    pub fn to_quadratics(&mut self, points: [Point; 3], weight: f32, tolerance: f32) {
        let conic = Conic { points, weight };
        let count = conic.quadratic_count(tolerance);
        self.count = conic.split_into_quadratics(&mut self.storage, count);
    }

    pub fn quadratic_count(&self) -> usize {
        self.count
    }

    /// The first point followed by two points per quadratic.
    pub fn quadratics(&self) -> &[Point] {
        &self.storage[..2 * self.count + 1]
    }
}

/// `ConicConverter.internalConicToQuadratics`: returns the number of quadratics. The Kotlin
/// caller grows its array to `4 * n + 2` floats and calls again when the result does not fit.
/// Unlike the C++ (which only compared `n` with the float count), nothing is written then.
pub fn conic_to_quadratics(points: [Point; 3], out: &mut [f32], weight: f32, tolerance: f32) -> usize {
    let conic = Conic { points, weight };
    let count = conic.quadratic_count(tolerance);
    let quadratics = 1usize << count;
    if quadratics > out.len() {
        return quadratics;
    }
    let mut storage = [Point::default(); MAX_QUADRATIC_POINTS];
    let quadratics = conic.split_into_quadratics(&mut storage, count);
    let floats = 2 * (2 * quadratics + 1);
    if floats <= out.len() {
        for (pair, point) in out.chunks_exact_mut(2).zip(&storage[..2 * quadratics + 1]) {
            pair[0] = point.x;
            pair[1] = point.y;
        }
    }
    quadratics
}
