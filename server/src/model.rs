use serde::{Deserialize, Serialize};
use std::fmt::{Display, Formatter};
use std::str::FromStr;

/// Invalid anti-poisoning or request-limit configuration.
#[derive(Clone, Debug, thiserror::Error)]
pub enum PolicyError {
    #[error("{0} must be greater than zero")]
    NotPositive(&'static str),
    #[error("{0} must be finite")]
    NotFinite(&'static str),
    #[error("{0} is too large")]
    TooLarge(&'static str),
}

/// Anti-poisoning and request limits. Defaults are suitable for a public server.
#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Policy {
    pub max_samples_per_device: i64,
    pub min_devices: usize,
    pub outlier_min_m: f64,
    pub max_jump_m: f64,
    pub max_range_m: f64,
    pub max_rows_per_upload: usize,
    pub max_uploads_per_hour_per_device: usize,
    pub max_uploads_per_hour_per_ip: usize,
    pub max_devices_per_ip_per_day: usize,
    pub ukraine_only: bool,
}

impl Default for Policy {
    fn default() -> Self {
        Self {
            max_samples_per_device: 50,
            min_devices: 2,
            outlier_min_m: 1_000.0,
            max_jump_m: 5_000.0,
            max_range_m: 50_000.0,
            max_rows_per_upload: 20_000,
            max_uploads_per_hour_per_device: 30,
            max_uploads_per_hour_per_ip: 120,
            max_devices_per_ip_per_day: 5,
            ukraine_only: false,
        }
    }
}

impl Policy {
    /// Validate invariants relied upon by consensus and rate-limiting code.
    ///
    /// # Errors
    ///
    /// Returns the first invalid field so configuration errors fail at startup rather than during
    /// an upload.
    pub fn validate(&self) -> Result<(), PolicyError> {
        if self.max_samples_per_device <= 0 {
            return Err(PolicyError::NotPositive("max_samples_per_device"));
        }
        for (name, value) in [
            ("min_devices", self.min_devices),
            ("max_rows_per_upload", self.max_rows_per_upload),
            (
                "max_uploads_per_hour_per_device",
                self.max_uploads_per_hour_per_device,
            ),
            (
                "max_uploads_per_hour_per_ip",
                self.max_uploads_per_hour_per_ip,
            ),
            (
                "max_devices_per_ip_per_day",
                self.max_devices_per_ip_per_day,
            ),
        ] {
            if value == 0 {
                return Err(PolicyError::NotPositive(name));
            }
        }
        if i64::try_from(self.min_devices).is_err() {
            return Err(PolicyError::TooLarge("min_devices"));
        }
        for (name, value) in [
            ("outlier_min_m", self.outlier_min_m),
            ("max_jump_m", self.max_jump_m),
            ("max_range_m", self.max_range_m),
        ] {
            if !value.is_finite() {
                return Err(PolicyError::NotFinite(name));
            }
            if value <= 0.0 {
                return Err(PolicyError::NotPositive(name));
            }
        }
        Ok(())
    }
}

/// Radio technology, matching the `OpenCellID` `radio` field.
#[derive(Clone, Copy, Debug, Deserialize, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum Radio {
    Gsm,
    Umts,
    Lte,
    Nr,
    Cdma,
}

/// A radio name that is not part of the `OpenCellID` vocabulary.
#[derive(Clone, Debug, Eq, PartialEq, thiserror::Error)]
#[error("unknown radio {0}")]
pub struct RadioParseError(String);

impl Display for Radio {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(match self {
            Self::Gsm => "GSM",
            Self::Umts => "UMTS",
            Self::Lte => "LTE",
            Self::Nr => "NR",
            Self::Cdma => "CDMA",
        })
    }
}

impl FromStr for Radio {
    type Err = RadioParseError;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value.to_ascii_uppercase().as_str() {
            "GSM" => Ok(Self::Gsm),
            "UMTS" => Ok(Self::Umts),
            "LTE" => Ok(Self::Lte),
            "NR" => Ok(Self::Nr),
            "CDMA" => Ok(Self::Cdma),
            _ => Err(RadioParseError(value.to_owned())),
        }
    }
}

/// Globally unique identity of a radio cell.
#[derive(Clone, Debug, Deserialize, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize)]
pub struct CellKey {
    pub radio: Radio,
    pub mcc: i64,
    pub mnc: i64,
    pub area: i64,
    pub cid: i64,
}

/// Published tower position and observation strength.
#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct CellTower {
    #[serde(flatten)]
    pub key: CellKey,
    pub lat: f64,
    pub lon: f64,
    pub range_m: f64,
    pub samples: i64,
}

/// Robust consensus and its publication metadata.
#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Consensus {
    #[serde(flatten)]
    pub tower: CellTower,
    pub devices: usize,
    pub seeded: bool,
    pub updated_s: i64,
}

/// Result returned after accepting a device upload.
#[derive(Clone, Copy, Debug, Deserialize, Serialize)]
pub struct UploadResult {
    pub accepted: usize,
    pub rejected: usize,
}

/// Event sent to connected management clients.
#[derive(Clone, Debug, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServerEvent {
    Ready { published: usize },
    TowerUpserted { tower: Consensus },
    TowerDeleted { key: CellKey },
    ResyncRequired { missed: u64 },
}

/// Great-circle distance in metres.
pub(crate) fn distance_m(a_lat: f64, a_lon: f64, b_lat: f64, b_lon: f64) -> f64 {
    const EARTH_RADIUS_M: f64 = 6_371_000.0;
    let lat1 = a_lat.to_radians();
    let lat2 = b_lat.to_radians();
    let delta_lat = (b_lat - a_lat).to_radians();
    let delta_lon = (b_lon - a_lon).to_radians();
    let haversine =
        (delta_lat / 2.0).sin().powi(2) + lat1.cos() * lat2.cos() * (delta_lon / 2.0).sin().powi(2);
    2.0 * EARTH_RADIUS_M * haversine.sqrt().asin()
}

pub(crate) fn plausible(tower: &CellTower, policy: &Policy) -> bool {
    let in_ukraine = !policy.ukraine_only
        || (43.0..=53.5).contains(&tower.lat) && (20.0..=41.0).contains(&tower.lon);
    tower.range_m > 0.0
        && tower.range_m <= policy.max_range_m
        && tower.samples > 0
        && (1..=999).contains(&tower.key.mcc)
        && (0..=999).contains(&tower.key.mnc)
        && tower.key.area >= 0
        && tower.key.cid >= 0
        && (-90.0..=90.0).contains(&tower.lat)
        && (-180.0..=180.0).contains(&tower.lon)
        && !(tower.lat == 0.0 && tower.lon == 0.0)
        && in_ukraine
}

#[cfg(test)]
mod tests;
