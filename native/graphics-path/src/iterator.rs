//! Walks a path's verbs and points, ported from `AndroidX` `PathIterator.cpp`.
//!
//! The data comes from Skia's `SkPathRef`. Unlike the C++, which follows raw pointers, this
//! version works on slices with their real lengths, so a malformed path ends the iteration
//! (returns `Done`) instead of reading outside Skia's arrays.

use crate::conic::ConicConverter;
use crate::{Point, Verb};
use std::borrow::Cow;

/// Before Android 11 Skia stored verbs back to front; from Android 11 they are in order.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum VerbDirection {
    Forward,
    Backward,
}

/// Matches `PathIterator::ConicEvaluation` (0 = as conic, 1 = as quadratics).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum ConicEvaluation {
    AsConic,
    AsQuadratics,
}

impl ConicEvaluation {
    #[must_use]
    pub fn from_raw(value: i32) -> Self {
        if value == 0 {
            Self::AsConic
        } else {
            Self::AsQuadratics
        }
    }
}

pub struct PathIterator<'a> {
    points: Cow<'a, [Point]>,
    /// Verbs in memory order; [`VerbDirection::Backward`] reads them from the end.
    verbs: Cow<'a, [u8]>,
    weights: Cow<'a, [f32]>,
    direction: VerbDirection,
    evaluation: ConicEvaluation,
    tolerance: f32,
    /// Verbs left to read, counting down like the C++ `mIndex`.
    remaining: usize,
    /// Verbs read so far.
    verb_cursor: usize,
    /// Index of the point the next segment starts after (the C++ `mPoints`).
    point_cursor: usize,
    weight_cursor: usize,
    converter: ConicConverter,
    current_quadratic: usize,
}

impl<'a> PathIterator<'a> {
    #[must_use]
    pub fn new(
        points: &'a [Point],
        verbs: &'a [u8],
        weights: &'a [f32],
        direction: VerbDirection,
        evaluation: ConicEvaluation,
        tolerance: f32,
    ) -> Self {
        Self {
            points: Cow::Borrowed(points),
            verbs: Cow::Borrowed(verbs),
            weights: Cow::Borrowed(weights),
            direction,
            evaluation,
            tolerance,
            remaining: verbs.len(),
            verb_cursor: 0,
            point_cursor: 0,
            weight_cursor: 0,
            converter: ConicConverter::default(),
            current_quadratic: 0,
        }
    }

    fn verb_at(&self, read: usize) -> Option<Verb> {
        let index = match self.direction {
            VerbDirection::Forward => read,
            VerbDirection::Backward => self.verbs.len().checked_sub(read + 1)?,
        };
        self.verbs.get(index).map(|&raw| Verb::from_raw(raw))
    }

    #[must_use]
    pub fn raw_count(&self) -> usize {
        self.verbs.len()
    }

    #[must_use]
    pub fn has_next(&self) -> bool {
        self.remaining > 0
    }

    #[must_use]
    pub fn peek(&self) -> Verb {
        if self.remaining > 0 {
            self.verb_at(self.verb_cursor).unwrap_or(Verb::Done)
        } else {
            Verb::Done
        }
    }

    /// Calculated segment count. Like the C++, this primes the iterator's conic converter;
    /// use a separate instance for sizing and walking so pending quadratics cannot be prepended.
    /// This side effect also exists in `AndroidX` 1.0.1's Kotlin implementation on API 34+.
    pub fn count(&mut self) -> usize {
        if self.evaluation == ConicEvaluation::AsConic {
            return self.verbs.len();
        }
        let mut count = 0;
        let mut point = self.point_cursor;
        let mut weight = self.weight_cursor;
        // Like the C++, start at the current position and stop where the verbs end.
        for read in self.verb_cursor..self.verb_cursor + self.verbs.len() {
            match self.verb_at(read) {
                Some(Verb::Move | Verb::Line) => {
                    point += 1;
                    count += 1;
                }
                Some(Verb::Quadratic) => {
                    point += 2;
                    count += 1;
                }
                Some(Verb::Conic) => {
                    let (Some(points), Some(&w)) =
                        (self.conic_points(point), self.weights.get(weight))
                    else {
                        return count;
                    };
                    self.converter.to_quadratics(points, w, self.tolerance);
                    weight += 1;
                    point += 2;
                    count += self.converter.quadratic_count();
                }
                Some(Verb::Cubic) => {
                    point += 3;
                    count += 1;
                }
                Some(Verb::Close | Verb::Done) => count += 1,
                None => return count,
            }
        }
        count
    }

    /// The three points of a conic or quadratic starting one before `cursor`.
    fn conic_points(&self, cursor: usize) -> Option<[Point; 3]> {
        let start = cursor.checked_sub(1)?;
        let slice = self.points.get(start..start + 3)?;
        Some([slice[0], slice[1], slice[2]])
    }

    fn take(&self, from: usize, count: usize) -> Option<&[Point]> {
        self.points.get(from..from + count)
    }

    /// Fill up to four points and return the verb; `Done` once the path is exhausted.
    pub fn next(&mut self, out: &mut [Point; 4]) -> Verb {
        if self.remaining == 0 {
            return Verb::Done;
        }
        if let Some(verb) = self.next_quadratic(out) {
            return verb;
        }
        self.remaining -= 1;
        let Some(verb) = self.verb_at(self.verb_cursor) else {
            self.remaining = 0;
            return Verb::Done;
        };
        self.verb_cursor += 1;
        let at = self.point_cursor;
        let segment = match verb {
            Verb::Move => self.take(at, 1).map(|p| (p, 1)),
            Verb::Line => at
                .checked_sub(1)
                .and_then(|start| self.take(start, 2))
                .map(|p| (p, 1)),
            Verb::Quadratic | Verb::Conic => at
                .checked_sub(1)
                .and_then(|start| self.take(start, 3))
                .map(|p| (p, 2)),
            Verb::Cubic => at
                .checked_sub(1)
                .and_then(|start| self.take(start, 4))
                .map(|p| (p, 3)),
            Verb::Close | Verb::Done => return verb,
        };
        let Some((points, advance)) = segment else {
            // Points missing for this verb: stop rather than read past Skia's array.
            self.remaining = 0;
            return Verb::Done;
        };
        out[..points.len()].copy_from_slice(points);
        self.point_cursor += advance;
        if verb == Verb::Conic {
            let Some(&weight) = self.weights.get(self.weight_cursor) else {
                self.remaining = 0;
                return Verb::Done;
            };
            self.weight_cursor += 1;
            out[3] = Point {
                x: weight,
                y: weight,
            };
            if self.evaluation == ConicEvaluation::AsQuadratics {
                self.converter
                    .to_quadratics([out[0], out[1], out[2]], weight, self.tolerance);
                self.current_quadratic = 0;
                // The C++ jumps back to emit the first quadratic; an empty conversion falls through.
                if let Some(quadratic) = self.next_quadratic(out) {
                    return quadratic;
                }
                return self.next_after_empty_conversion(out);
            }
        }
        verb
    }

    fn next_quadratic(&mut self, out: &mut [Point; 4]) -> Option<Verb> {
        if self.current_quadratic == self.converter.quadratic_count() {
            return None;
        }
        let index = self.current_quadratic * 2;
        let quadratics = self.converter.quadratics();
        // A count() call may leave the converter with fewer stored points than the cursor expects.
        let points = quadratics.get(index..index + 3)?;
        out[..3].copy_from_slice(points);
        self.current_quadratic += 1;
        Some(Verb::Quadratic)
    }

    /// Conversions always produce at least one quadratic, so this is only a safety net.
    fn next_after_empty_conversion(&mut self, out: &mut [Point; 4]) -> Verb {
        if self.remaining == 0 {
            Verb::Done
        } else {
            self.next(out)
        }
    }
}

impl PathIterator<'static> {
    /// Own a snapshot of the path. Iteration never copies the buffers again.
    #[must_use]
    pub fn from_owned(
        points: Vec<Point>,
        verbs: Vec<u8>,
        weights: Vec<f32>,
        direction: VerbDirection,
        evaluation: ConicEvaluation,
        tolerance: f32,
    ) -> Self {
        let mut iterator = Self::new(&[], &[], &[], direction, evaluation, tolerance);
        iterator.remaining = verbs.len();
        iterator.points = Cow::Owned(points);
        iterator.verbs = Cow::Owned(verbs);
        iterator.weights = Cow::Owned(weights);
        iterator
    }
}

#[cfg(test)]
mod ownership_tests {
    use super::*;

    #[test]
    fn owned_iterator_outlives_source_storage() {
        let mut iterator = {
            let points = [Point { x: 1.0, y: 2.0 }, Point { x: 3.0, y: 4.0 }];
            let verbs = [Verb::Move as u8, Verb::Line as u8];
            PathIterator::from_owned(
                points.to_vec(),
                verbs.to_vec(),
                vec![],
                VerbDirection::Forward,
                ConicEvaluation::AsConic,
                0.25,
            )
        };
        let mut output = [Point::default(); 4];
        assert_eq!(iterator.next(&mut output), Verb::Move);
        assert_eq!(output[0], Point { x: 1.0, y: 2.0 });
        assert_eq!(iterator.next(&mut output), Verb::Line);
        assert_eq!(output[1], Point { x: 3.0, y: 4.0 });
        assert_eq!(iterator.next(&mut output), Verb::Done);
    }
}
