//! The two supported pack profiles, independent of the host-only builder feature.
use serde::{Deserialize, Serialize};
use std::{fmt, str::FromStr};

#[derive(Clone, Copy, Debug, Deserialize, Eq, Hash, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Profile {
    Car,
    Foot,
}

impl Profile {
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::Car => "car",
            Self::Foot => "foot",
        }
    }
}

impl FromStr for Profile {
    type Err = crate::ProfileError;
    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value {
            "car" => Ok(Self::Car),
            "foot" => Ok(Self::Foot),
            _ => Err(crate::ProfileError),
        }
    }
}

impl fmt::Display for Profile {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.name())
    }
}
