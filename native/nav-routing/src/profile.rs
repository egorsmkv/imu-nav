//! Travel modes and independently prepared car cost variants.
use serde::{Deserialize, Serialize};
use std::{fmt, str::FromStr};

#[derive(Clone, Copy, Debug, Deserialize, Eq, Hash, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Profile {
    Car,
    #[serde(rename = "car_balanced")]
    CarBalanced,
    Foot,
}

impl Profile {
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::Car => "car",
            Self::CarBalanced => "car_balanced",
            Self::Foot => "foot",
        }
    }

    /// Both car variants share access rules, turn restrictions and the motion model.
    #[must_use]
    pub const fn is_car(self) -> bool {
        !matches!(self, Self::Foot)
    }
}

impl FromStr for Profile {
    type Err = crate::ProfileError;
    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value {
            "car" => Ok(Self::Car),
            "car_balanced" => Ok(Self::CarBalanced),
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
