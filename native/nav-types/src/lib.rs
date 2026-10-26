#![forbid(unsafe_code)]
//! Domain values shared across the navigation, server and desktop workspaces.
//!
//! Constructors check intrinsic invariants; contextual limits still belong to the caller.
//! Quantities deliberately have no implicit conversion or arithmetic operators.
//!
//! ```compile_fail
//! use imu_nav_types::{Distance, RoutePosition};
//! let position: RoutePosition = Distance::new(1.0).unwrap();
//! ```
//! ```compile_fail
//! use imu_nav_types::{ElapsedMs, UnixMs};
//! let time: UnixMs = ElapsedMs::new(1000).unwrap();
//! ```

pub mod handles;

use std::fmt;

/// A domain invariant failed before a value could enter application state.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ValueError {
    /// The supplied scalar is outside its domain.
    Invalid(&'static str),
    /// A conversion or arithmetic operation cannot be represented.
    Overflow,
}

impl fmt::Display for ValueError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Invalid(name) => write!(formatter, "invalid {name}"),
            Self::Overflow => formatter.write_str("domain arithmetic overflow"),
        }
    }
}

impl std::error::Error for ValueError {}

macro_rules! scalar {
    ($name:ident, $raw:ty, $valid:expr, $doc:literal) => {
        #[doc = $doc]
        #[derive(Clone, Copy, Debug, PartialEq)]
        #[cfg_attr(feature = "serde", derive(serde::Serialize))]
        #[cfg_attr(feature = "serde", serde(transparent))]
        #[repr(transparent)]
        pub struct $name($raw);

        impl $name {
            /// Check intrinsic bounds without normalizing or clamping the input.
            ///
            /// # Errors
            /// Returns an error when the input is outside this quantity's domain.
            pub fn new(value: $raw) -> Result<Self, ValueError> {
                if ($valid)(value) {
                    Ok(Self(value))
                } else {
                    Err(ValueError::Invalid(stringify!($name)))
                }
            }

            /// Extract the scalar at an arithmetic or serialization boundary.
            #[must_use]
            pub const fn get(self) -> $raw {
                self.0
            }
        }

        impl TryFrom<$raw> for $name {
            type Error = ValueError;
            fn try_from(value: $raw) -> Result<Self, Self::Error> {
                Self::new(value)
            }
        }

        impl From<$name> for $raw {
            fn from(value: $name) -> Self {
                value.get()
            }
        }

        #[cfg(feature = "serde")]
        impl<'de> serde::Deserialize<'de> for $name {
            fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
                Self::new(<$raw as serde::Deserialize>::deserialize(deserializer)?)
                    .map_err(serde::de::Error::custom)
            }
        }

        impl fmt::Display for $name {
            fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
                self.0.fmt(formatter)
            }
        }
    };
}

scalar!(
    RoutePosition,
    f64,
    f64::is_finite,
    "Signed metres along a planned route."
);
scalar!(
    Distance,
    f64,
    |value: f64| value.is_finite() && value >= 0.0,
    "Finite non-negative distance in metres."
);
scalar!(
    Velocity,
    f64,
    f64::is_finite,
    "Signed along-route velocity in metres per second."
);
scalar!(
    Speed,
    f64,
    |value: f64| value.is_finite() && value >= 0.0,
    "Physical speed in metres per second."
);
scalar!(
    SpeedKmh,
    f64,
    |value: f64| value.is_finite() && value >= 0.0,
    "Physical speed in kilometres per hour."
);
scalar!(
    PositionSigma,
    f64,
    |value: f64| value.is_finite() && value >= 0.0,
    "Position standard deviation in metres; zero is permitted."
);
scalar!(
    SpeedSigma,
    f64,
    |value: f64| value.is_finite() && value >= 0.0,
    "Speed standard deviation in metres per second; zero is permitted."
);
scalar!(
    Latitude,
    f64,
    |value: f64| value.is_finite() && (-90.0..=90.0).contains(&value),
    "Latitude in degrees, including both poles."
);
scalar!(
    Longitude,
    f64,
    |value: f64| value.is_finite() && (-180.0..=180.0).contains(&value),
    "Longitude in degrees, including both date-line representations."
);
scalar!(
    Seconds,
    f64,
    |value: f64| value.is_finite() && value >= 0.0,
    "Finite non-negative duration in seconds."
);

impl SpeedKmh {
    /// Unit conversion keeps the existing division order used by navigation.
    #[must_use]
    pub fn to_mps(self) -> Speed {
        Speed(self.0 / 3.6)
    }
}

impl Speed {
    /// # Errors
    /// Returns overflow when the converted speed is not finite.
    pub fn to_kmh(self) -> Result<SpeedKmh, ValueError> {
        SpeedKmh::new(self.0 * 3.6)
    }
}

impl DurationMs {
    /// Millisecond conversion may round beyond f64's exact integer range.
    #[allow(clippy::cast_precision_loss)] // Milliseconds on Android remain far below that range.
    #[must_use]
    pub fn to_seconds(self) -> Seconds {
        Seconds(self.0 as f64 / 1000.0)
    }
}

impl ElapsedMs {
    /// Duration since the monotonic clock's origin, without changing time domains.
    #[must_use]
    pub const fn since_boot(self) -> DurationMs {
        DurationMs(self.0)
    }
    /// # Errors
    /// Rejects backwards time and differences that cannot fit a duration.
    pub fn duration_since(self, earlier: Self) -> Result<DurationMs, ValueError> {
        DurationMs::new(self.0.checked_sub(earlier.0).ok_or(ValueError::Overflow)?)
    }

    /// # Errors
    /// Rejects timestamp overflow rather than wrapping to an old observation.
    pub fn checked_add(self, duration: DurationMs) -> Result<Self, ValueError> {
        Self::new(self.0.checked_add(duration.0).ok_or(ValueError::Overflow)?)
    }
}

impl From<ElapsedMs> for MonotonicMs {
    fn from(value: ElapsedMs) -> Self {
        Self(value.get())
    }
}

impl Distance {
    /// No accumulated distance or systematic drift.
    pub const ZERO: Self = Self(0.0);
}

/// Valid geographic coordinates. Height is intentionally a separate quantity.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Coordinate {
    pub latitude: Latitude,
    pub longitude: Longitude,
}

impl Coordinate {
    /// # Errors
    /// Rejects non-finite or out-of-range coordinates without wrapping them.
    pub fn new(latitude: f64, longitude: f64) -> Result<Self, ValueError> {
        Ok(Self {
            latitude: Latitude::new(latitude)?,
            longitude: Longitude::new(longitude)?,
        })
    }
}

macro_rules! identifier {
    ($name:ident, $raw:ty, $valid:expr, $doc:literal) => {
        scalar!($name, $raw, $valid, $doc);
        impl Eq for $name {}
        impl PartialOrd for $name {
            fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
                Some(self.cmp(other))
            }
        }
        impl std::hash::Hash for $name {
            fn hash<H: std::hash::Hasher>(&self, state: &mut H) {
                self.0.hash(state);
            }
        }
        impl Ord for $name {
            fn cmp(&self, other: &Self) -> std::cmp::Ordering {
                self.0.cmp(&other.0)
            }
        }
    };
}

identifier!(
    ElapsedMs,
    i64,
    |value: i64| value >= 0,
    "Non-negative Android elapsed-realtime timestamp in milliseconds."
);
identifier!(
    MonotonicMs,
    i64,
    |_: i64| true,
    "Signed timestamp for standalone monotonic-time regressions."
);
identifier!(
    UnixMs,
    i64,
    |_: i64| true,
    "Wall-clock timestamp in Unix milliseconds, separate from elapsed time."
);
identifier!(
    DurationMs,
    i64,
    |value: i64| value >= 0,
    "Non-negative duration in milliseconds."
);

identifier!(
    AccountId,
    i64,
    |value: i64| value > 0,
    "Positive database account identity."
);
identifier!(
    Mcc,
    i64,
    |value: i64| (1..=999).contains(&value),
    "Mobile country code."
);
identifier!(
    Mnc,
    i64,
    |value: i64| (0..=999).contains(&value),
    "Mobile network code; zero is valid."
);
identifier!(
    CellArea,
    i64,
    |value: i64| value >= 0,
    "Non-negative radio cell area identity."
);
identifier!(
    CellId,
    i64,
    |value: i64| value >= 0,
    "Non-negative radio cell identity; zero is valid."
);
identifier!(
    Sequence,
    u64,
    |value: u64| value > 0,
    "Nonzero position message sequence."
);
identifier!(
    Revision,
    u64,
    |value: u64| value > 0,
    "Nonzero route revision."
);
identifier!(
    SearchId,
    u64,
    |value: u64| value > 0,
    "Nonzero search document or reference identity."
);

macro_rules! vocabulary {
    ($name:ident, $($variant:ident => $wire:literal),+ $(,)?) => {
        #[doc = concat!("Closed ", stringify!($name), " vocabulary; serialized spellings are stable.")]
        #[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
        #[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
        pub enum $name { $( #[cfg_attr(feature = "serde", serde(rename = $wire))] $variant, )+ }
        impl $name {
            #[must_use]
            pub const fn as_str(self) -> &'static str { match self { $( Self::$variant => $wire, )+ } }
        }
        impl std::str::FromStr for $name {
            type Err = ValueError;
            fn from_str(value: &str) -> Result<Self, Self::Err> {
                match value { $( $wire => Ok(Self::$variant), )+ _ => Err(ValueError::Invalid(stringify!($name))) }
            }
        }
        impl fmt::Display for $name {
            fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result { formatter.write_str(self.as_str()) }
        }
    };
}

vocabulary!(TravelMode, Car => "CAR", Foot => "FOOT");
vocabulary!(PositionSource, Gps => "GPS", GpsSuspect => "GPS_SUSPECT", DeadReckoning => "DR",
    Network => "DR_NET", Vehicle => "DR_OBD", Stopped => "DR_STOPPED", Cell => "CELL");
vocabulary!(GpsState, Ok => "OK", Degraded => "DEGRADED", Lost => "LOST");
vocabulary!(GpsVerdict, Good => "GOOD", Suspect => "SUSPECT", Bad => "BAD");
vocabulary!(SearchKind, Place => "place", Street => "street", Address => "address");

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quantity_boundaries_are_context_specific() {
        assert!(RoutePosition::new(-1.0).is_ok());
        assert!(Velocity::new(-1.0).is_ok());
        assert!(Speed::new(-1.0).is_err());
        assert!(PositionSigma::new(0.0).is_ok());
        assert!(MonotonicMs::new(-1).is_ok());
        assert!(ElapsedMs::new(-1).is_err());
        assert!(Coordinate::new(-90.0, 180.0).is_ok());
        assert!(Coordinate::new(90.1, 0.0).is_err());
        for value in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY] {
            assert!(RoutePosition::new(value).is_err());
            assert!(SpeedSigma::new(value).is_err());
            assert!(Coordinate::new(0.0, value).is_err());
        }
    }

    #[test]
    fn conversions_and_arithmetic_are_checked() {
        assert_eq!(SpeedKmh::new(36.0).unwrap().to_mps().get(), 10.0);
        assert_eq!(DurationMs::new(1500).unwrap().to_seconds().get(), 1.5);
        let end = ElapsedMs::new(i64::MAX).unwrap();
        assert!(end.checked_add(DurationMs::new(1).unwrap()).is_err());
        assert!(ElapsedMs::new(0).unwrap().duration_since(end).is_err());
        assert!(Speed::new(f64::MAX).unwrap().to_kmh().is_err());
        assert!(Sequence::new(0).is_err());
        assert!(Mcc::new(0).is_err());
        assert!(Mnc::new(0).is_ok());
        assert!(CellId::new(0).is_ok());
    }
}
