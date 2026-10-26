//! Checked identities with the same SQL and JSON scalar representations as existing databases.
use imu_nav_types::ValueError;
use rusqlite::types::{FromSql, FromSqlError, FromSqlResult, ToSql, ToSqlOutput, ValueRef};
use serde::{Deserialize, Serialize};
use std::{fmt, str::FromStr};

macro_rules! identity {
    ($name:ident) => {
        #[doc = concat!("Checked ", stringify!($name), "; database decoding enforces intrinsic bounds.")]
        #[derive(Clone, Copy, Debug, Deserialize, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize)]
        #[serde(transparent)]
        pub struct $name(imu_nav_types::$name);
        impl $name {
            /// # Errors
            /// Rejects values outside the identity's intrinsic domain.
            pub fn new(value: i64) -> Result<Self, ValueError> { imu_nav_types::$name::new(value).map(Self) }
            #[must_use]
            pub const fn get(self) -> i64 { self.0.get() }
        }
        impl FromStr for $name {
            type Err = ValueError;
            fn from_str(value: &str) -> Result<Self, Self::Err> {
                Self::new(value.parse().map_err(|_| ValueError::Invalid(stringify!($name)))?)
            }
        }
        impl fmt::Display for $name {
            fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result { self.0.fmt(formatter) }
        }
        impl FromSql for $name {
            fn column_result(value: ValueRef<'_>) -> FromSqlResult<Self> {
                Self::new(i64::column_result(value)?).map_err(|error| FromSqlError::Other(Box::new(error)))
            }
        }
        impl ToSql for $name {
            fn to_sql(&self) -> rusqlite::Result<ToSqlOutput<'_>> { Ok(ToSqlOutput::Owned(self.get().into())) }
        }
    };
}
identity!(AccountId);
identity!(Mcc);
identity!(Mnc);
identity!(CellArea);
identity!(CellId);

/// Untrusted public device identity; internal account-prefixed and seed keys remain separate.
#[derive(Clone, Debug, Eq, Hash, PartialEq)]
pub struct DeviceId(String);

impl DeviceId {
    /// # Errors
    /// Requires 8–64 ASCII alphanumeric or hyphen bytes, matching the public upload contract.
    pub fn new(value: String) -> Result<Self, ValueError> {
        if (8..=64).contains(&value.len())
            && value
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
        {
            Ok(Self(value))
        } else {
            Err(ValueError::Invalid("device identity"))
        }
    }
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sql_and_json_keep_scalars_and_reject_invalid_stored_identities() -> anyhow::Result<()> {
        let connection = rusqlite::Connection::open_in_memory()?;
        connection.execute_batch("CREATE TABLE ids(value INTEGER); INSERT INTO ids VALUES(-1);")?;
        assert!(
            connection
                .query_row("SELECT value FROM ids", [], |row| row
                    .get::<_, AccountId>(0))
                .is_err()
        );
        assert_eq!(
            connection.query_row("SELECT value FROM ids", [], |row| row.get::<_, i64>(0))?,
            -1
        );
        let account = AccountId::new(1)?;
        assert_eq!(
            connection.query_row("SELECT ?1", [account], |row| row.get::<_, AccountId>(0))?,
            account
        );
        assert_eq!(serde_json::to_string(&account)?, "1");
        assert!(serde_json::from_str::<AccountId>("0").is_err());
        assert!(serde_json::from_str::<Mcc>("1000").is_err());
        assert!(serde_json::from_str::<Mnc>("-1").is_err());
        assert!(serde_json::from_str::<CellArea>("-1").is_err());
        assert!(serde_json::from_str::<CellId>("0").is_ok());
        assert!(DeviceId::new("account:1:phone".into()).is_err());
        assert!(DeviceId::new("test-device".into()).is_ok());
        Ok(())
    }
}
