//! Rust port of the AndroidX Graphics Path 1.0.1 native library.
//!
//! Compose iterates `android.graphics.Path` segments through `androidx.graphics.path`. From
//! Android 14 the platform `PathIterator` does that; on older versions AndroidX reads Skia's
//! private path data through this JNI library. The Java/Kotlin side stays the unchanged
//! AndroidX artifact, so this library registers exactly the same native methods.
//!
//! Derived from AndroidX (Apache-2.0); see `LICENSE` and `README.md`.

pub mod conic;
pub mod iterator;

#[cfg(target_os = "android")]
mod bridge;

/// A point as Skia stores it (two consecutive `f32`).
#[repr(C)]
#[derive(Clone, Copy, Default, Debug, PartialEq)]
pub struct Point {
    pub x: f32,
    pub y: f32,
}

/// Skia path verbs; the numbers are part of the JNI contract with AndroidX.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
#[repr(u8)]
pub enum Verb {
    Move = 0,
    Line = 1,
    Quadratic = 2,
    Conic = 3,
    Cubic = 4,
    Close = 5,
    Done = 6,
}

impl Verb {
    /// Unknown values end the iteration instead of being misread.
    pub fn from_raw(raw: u8) -> Self {
        match raw {
            0 => Self::Move,
            1 => Self::Line,
            2 => Self::Quadratic,
            3 => Self::Conic,
            4 => Self::Cubic,
            5 => Self::Close,
            _ => Self::Done,
        }
    }
}
