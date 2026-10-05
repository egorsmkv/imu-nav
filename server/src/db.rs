//! The small SQL interface used by both persistent server backends.
//!
//! Keeping row decoding here lets the existing application queries use the same value types
//! while PostgreSQL supplies durable relational storage and SQLite remains the local default.

use postgres::types::Type as PgType;
use postgres_native_tls::MakeTlsConnector;
use r2d2::PooledConnection;
use r2d2_postgres::PostgresConnectionManager;
use rusqlite::TransactionBehavior;
use rusqlite::types::{FromSql, ToSqlOutput, Value, ValueRef};
use std::cell::RefCell;
use std::collections::VecDeque;
use std::path::Path;
use std::time::Duration;

type PgManager = PostgresConnectionManager<MakeTlsConnector>;
pub(crate) type PgPool = r2d2::Pool<PgManager>;

/// Encode the primitive values used in SQL parameters without tying callers to either driver.
pub(crate) fn value<T: rusqlite::ToSql + ?Sized>(input: &T) -> Value {
    match input.to_sql().expect("a built-in SQL value must encode") {
        ToSqlOutput::Borrowed(value) => value_to_owned(value),
        ToSqlOutput::Owned(value) => value,
        _ => unreachable!("server parameters use primitive SQL values"),
    }
}

pub(crate) fn value_to_owned(value: ValueRef<'_>) -> Value {
    match value {
        ValueRef::Null => Value::Null,
        ValueRef::Integer(number) => Value::Integer(number),
        ValueRef::Real(number) => Value::Real(number),
        ValueRef::Text(bytes) => Value::Text(String::from_utf8_lossy(bytes).into_owned()),
        ValueRef::Blob(bytes) => Value::Blob(bytes.to_vec()),
    }
}

/// Accept arrays and vectors at the same call sites as rusqlite's parameter API.
pub(crate) trait IntoParams {
    fn into_params(self) -> Vec<Value>;
}

impl<T: rusqlite::ToSql, const N: usize> IntoParams for [T; N] {
    fn into_params(self) -> Vec<Value> {
        self.iter().map(value).collect()
    }
}

impl IntoParams for Vec<Value> {
    fn into_params(self) -> Vec<Value> {
        self
    }
}

pub(crate) fn params_from_iter(values: impl IntoIterator<Item = Value>) -> Vec<Value> {
    values.into_iter().collect()
}

#[macro_export]
macro_rules! db_params {
    ($($item:expr),* $(,)?) => {
        vec![$($crate::db::value(&$item)),*]
    };
}
pub(crate) use db_params as params;

/// A connection checked out for one blocking database operation.
pub(crate) enum Connection {
    Sqlite(rusqlite::Connection),
    Postgres(Box<RefCell<PooledConnection<PgManager>>>),
}

impl Connection {
    pub(crate) fn open(path: &Path) -> anyhow::Result<Self> {
        Ok(Self::Sqlite(rusqlite::Connection::open(path)?))
    }

    pub(crate) fn postgres(pool: &PgPool) -> anyhow::Result<Self> {
        Ok(Self::Postgres(Box::new(RefCell::new(pool.get()?))))
    }

    pub(crate) fn busy_timeout(&self, timeout: Duration) -> rusqlite::Result<()> {
        match self {
            Self::Sqlite(connection) => connection.busy_timeout(timeout),
            Self::Postgres(_) => Ok(()),
        }
    }

    pub(crate) fn execute_batch(&self, sql: &str) -> rusqlite::Result<()> {
        match self {
            Self::Sqlite(connection) => connection.execute_batch(sql),
            Self::Postgres(connection) => connection
                .borrow_mut()
                .batch_execute(&translate(sql))
                .map_err(pg_error),
        }
    }

    pub(crate) fn execute(&self, sql: &str, params: impl IntoParams) -> rusqlite::Result<usize> {
        match self {
            Self::Sqlite(connection) => {
                connection.execute(sql, rusqlite::params_from_iter(params.into_params()))
            }
            Self::Postgres(connection) => {
                execute_postgres(&mut connection.borrow_mut(), sql, params.into_params())
            }
        }
    }

    pub(crate) fn query_row<T>(
        &self,
        sql: &str,
        params: impl IntoParams,
        map: impl FnOnce(&Row) -> rusqlite::Result<T>,
    ) -> rusqlite::Result<T> {
        self.prepare(sql)?.query_row(params, map)
    }

    pub(crate) fn prepare<'a>(&'a self, sql: &str) -> rusqlite::Result<Statement<'a>> {
        match self {
            Self::Sqlite(connection) => Ok(Statement::Sqlite(connection.prepare(sql)?)),
            Self::Postgres(connection) => Ok(Statement::Postgres {
                connection,
                sql: sql.to_owned(),
            }),
        }
    }

    pub(crate) fn transaction(&mut self) -> rusqlite::Result<Transaction<'_>> {
        self.transaction_with_behavior(TransactionBehavior::Immediate)
    }

    /// Keep paged public exports on one consistent snapshot without taking the writer lock.
    pub(crate) fn read_transaction(&mut self) -> rusqlite::Result<Transaction<'_>> {
        match self {
            Self::Sqlite(connection) => Ok(Transaction::Sqlite(Some(
                connection.transaction_with_behavior(TransactionBehavior::Deferred)?,
            ))),
            Self::Postgres(connection) => {
                connection
                    .borrow_mut()
                    .batch_execute("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
                    .map_err(pg_error)?;
                Ok(Transaction::Postgres {
                    connection,
                    committed: false,
                })
            }
        }
    }

    pub(crate) fn transaction_with_behavior(
        &mut self,
        behavior: TransactionBehavior,
    ) -> rusqlite::Result<Transaction<'_>> {
        match self {
            Self::Sqlite(connection) => Ok(Transaction::Sqlite(Some(
                connection.transaction_with_behavior(behavior)?,
            ))),
            Self::Postgres(connection) => {
                connection
                    .borrow_mut()
                    .batch_execute("BEGIN")
                    .map_err(pg_error)?;
                // SQLite serializes writers. The transaction lock gives PostgreSQL the same
                // consensus and policy ordering even with concurrent pooled connections.
                connection
                    .borrow_mut()
                    .query_one("SELECT pg_advisory_xact_lock(73266721)", &[])
                    .map_err(pg_error)?;
                Ok(Transaction::Postgres {
                    connection,
                    committed: false,
                })
            }
        }
    }

    pub(crate) fn last_insert_rowid(&self) -> i64 {
        match self {
            Self::Sqlite(connection) => connection.last_insert_rowid(),
            Self::Postgres(connection) => connection
                .borrow_mut()
                .query_one("SELECT lastval()", &[])
                .and_then(|row| row.try_get::<_, i64>(0))
                .unwrap_or_default(),
        }
    }
}

/// A transaction whose PostgreSQL writes roll back if its owner returns early.
pub(crate) enum Transaction<'a> {
    Sqlite(Option<rusqlite::Transaction<'a>>),
    Postgres {
        connection: &'a RefCell<PooledConnection<PgManager>>,
        committed: bool,
    },
}

impl Transaction<'_> {
    pub(crate) fn execute(&self, sql: &str, params: impl IntoParams) -> rusqlite::Result<usize> {
        match self {
            Self::Sqlite(transaction) => transaction
                .as_ref()
                .expect("active transaction")
                .execute(sql, rusqlite::params_from_iter(params.into_params())),
            Self::Postgres { connection, .. } => {
                execute_postgres(&mut connection.borrow_mut(), sql, params.into_params())
            }
        }
    }

    pub(crate) fn execute_batch(&self, sql: &str) -> rusqlite::Result<()> {
        match self {
            Self::Sqlite(transaction) => transaction
                .as_ref()
                .expect("active transaction")
                .execute_batch(sql),
            Self::Postgres { connection, .. } => connection
                .borrow_mut()
                .batch_execute(&translate(sql))
                .map_err(pg_error),
        }
    }

    pub(crate) fn query_row<T>(
        &self,
        sql: &str,
        params: impl IntoParams,
        map: impl FnOnce(&Row) -> rusqlite::Result<T>,
    ) -> rusqlite::Result<T> {
        self.prepare(sql)?.query_row(params, map)
    }

    pub(crate) fn prepare<'a>(&'a self, sql: &str) -> rusqlite::Result<Statement<'a>> {
        match self {
            Self::Sqlite(transaction) => Ok(Statement::Sqlite(
                transaction
                    .as_ref()
                    .expect("active transaction")
                    .prepare(sql)?,
            )),
            Self::Postgres { connection, .. } => Ok(Statement::Postgres {
                connection,
                sql: sql.to_owned(),
            }),
        }
    }

    pub(crate) fn prepare_cached<'a>(&'a self, sql: &str) -> rusqlite::Result<Statement<'a>> {
        match self {
            Self::Sqlite(transaction) => Ok(Statement::CachedSqlite(
                transaction
                    .as_ref()
                    .expect("active transaction")
                    .prepare_cached(sql)?,
            )),
            Self::Postgres { connection, .. } => Ok(Statement::Postgres {
                connection,
                sql: sql.to_owned(),
            }),
        }
    }

    pub(crate) fn commit(mut self) -> rusqlite::Result<()> {
        match &mut self {
            Self::Sqlite(_) => {}
            Self::Postgres {
                connection,
                committed,
            } => {
                connection
                    .borrow_mut()
                    .batch_execute("COMMIT")
                    .map_err(pg_error)?;
                *committed = true;
                return Ok(());
            }
        }
        if let Self::Sqlite(transaction) = &mut self {
            transaction.take().expect("active transaction").commit()
        } else {
            unreachable!()
        }
    }
}

impl Drop for Transaction<'_> {
    fn drop(&mut self) {
        if let Self::Postgres {
            connection,
            committed: false,
        } = self
        {
            let _ = connection.borrow_mut().batch_execute("ROLLBACK");
        }
    }
}

/// A prepared query whose rows are decoded into the same primitive types on both backends.
pub(crate) enum Statement<'a> {
    Sqlite(rusqlite::Statement<'a>),
    CachedSqlite(rusqlite::CachedStatement<'a>),
    Postgres {
        connection: &'a RefCell<PooledConnection<PgManager>>,
        sql: String,
    },
}

impl Statement<'_> {
    pub(crate) fn execute(&mut self, params: impl IntoParams) -> rusqlite::Result<usize> {
        match self {
            Self::Sqlite(statement) => {
                statement.execute(rusqlite::params_from_iter(params.into_params()))
            }
            Self::CachedSqlite(statement) => {
                statement.execute(rusqlite::params_from_iter(params.into_params()))
            }
            Self::Postgres { connection, sql } => {
                execute_postgres(&mut connection.borrow_mut(), sql, params.into_params())
            }
        }
    }

    pub(crate) fn query(&mut self, params: impl IntoParams) -> rusqlite::Result<Rows<'_>> {
        let values = params.into_params();
        match self {
            Self::Sqlite(statement) => {
                let columns = statement.column_count();
                Ok(Rows::Sqlite {
                    rows: statement.query(rusqlite::params_from_iter(values))?,
                    columns,
                })
            }
            Self::CachedSqlite(statement) => {
                let columns = statement.column_count();
                Ok(Rows::Sqlite {
                    rows: statement.query(rusqlite::params_from_iter(values))?,
                    columns,
                })
            }
            Self::Postgres { connection, sql } => Ok(Rows::Postgres {
                rows: postgres_rows(&mut connection.borrow_mut(), sql, values)?.into(),
            }),
        }
    }

    pub(crate) fn query_map<T, F>(
        &mut self,
        params: impl IntoParams,
        map: F,
    ) -> rusqlite::Result<MappedRows<'_, T, F>>
    where
        F: FnMut(&Row) -> rusqlite::Result<T>,
    {
        Ok(MappedRows {
            rows: self.query(params)?,
            map,
            failed: false,
            item: std::marker::PhantomData,
        })
    }

    pub(crate) fn query_row<T>(
        &mut self,
        params: impl IntoParams,
        map: impl FnOnce(&Row) -> rusqlite::Result<T>,
    ) -> rusqlite::Result<T> {
        let mut rows = self.query(params)?;
        map(&rows.next()?.ok_or(rusqlite::Error::QueryReturnedNoRows)?)
    }
}

pub(crate) enum Rows<'a> {
    Sqlite {
        rows: rusqlite::Rows<'a>,
        columns: usize,
    },
    Postgres {
        rows: VecDeque<Row>,
    },
}

impl Rows<'_> {
    pub(crate) fn next(&mut self) -> rusqlite::Result<Option<Row>> {
        match self {
            Self::Sqlite { rows, columns } => rows
                .next()?
                .map(|row| {
                    let values = (0..*columns)
                        .map(|index| row.get_ref(index).map(value_to_owned))
                        .collect::<rusqlite::Result<_>>()?;
                    Ok(Row { values })
                })
                .transpose(),
            Self::Postgres { rows } => Ok(rows.pop_front()),
        }
    }
}

pub(crate) struct MappedRows<'a, T, F> {
    rows: Rows<'a>,
    map: F,
    failed: bool,
    item: std::marker::PhantomData<T>,
}

impl<T, F: FnMut(&Row) -> rusqlite::Result<T>> Iterator for MappedRows<'_, T, F> {
    type Item = rusqlite::Result<T>;

    fn next(&mut self) -> Option<Self::Item> {
        if self.failed {
            return None;
        }
        match self.rows.next() {
            Ok(Some(row)) => Some((self.map)(&row)),
            Ok(None) => None,
            Err(error) => {
                self.failed = true;
                Some(Err(error))
            }
        }
    }
}

/// An owned result row allows query helpers to release a pooled connection before mapping data.
pub(crate) struct Row {
    values: Vec<Value>,
}

impl Row {
    pub(crate) fn get<I, T>(&self, index: I) -> rusqlite::Result<T>
    where
        I: TryInto<usize> + Copy,
        T: FromSql,
    {
        let index = index
            .try_into()
            .map_err(|_| rusqlite::Error::InvalidColumnIndex(usize::MAX))?;
        let value = self
            .values
            .get(index)
            .ok_or(rusqlite::Error::InvalidColumnIndex(index))?;
        T::column_result(ValueRef::from(value)).map_err(|error| {
            rusqlite::Error::FromSqlConversionFailure(index, value.data_type(), Box::new(error))
        })
    }
}

fn pg_values(values: Vec<Value>) -> Vec<Box<dyn postgres::types::ToSql + Sync>> {
    values
        .into_iter()
        .map(|value| match value {
            Value::Null => Box::new(Option::<i64>::None) as Box<dyn postgres::types::ToSql + Sync>,
            Value::Integer(number) => Box::new(number),
            Value::Real(number) => Box::new(number),
            Value::Text(text) => Box::new(text),
            Value::Blob(bytes) => Box::new(bytes),
        })
        .collect()
}

fn execute_postgres(
    client: &mut postgres::Client,
    sql: &str,
    params: Vec<Value>,
) -> rusqlite::Result<usize> {
    let params = pg_values(params);
    let refs = params
        .iter()
        .map(|value| value.as_ref() as &(dyn postgres::types::ToSql + Sync))
        .collect::<Vec<_>>();
    let sql = translate(sql);
    let count = client.execute(&sql, &refs).map_err(pg_error)?;
    usize::try_from(count).map_err(|error| rusqlite::Error::ToSqlConversionFailure(Box::new(error)))
}

fn postgres_rows(
    client: &mut postgres::Client,
    sql: &str,
    params: Vec<Value>,
) -> rusqlite::Result<Vec<Row>> {
    let params = pg_values(params);
    let refs = params
        .iter()
        .map(|value| value.as_ref() as &(dyn postgres::types::ToSql + Sync))
        .collect::<Vec<_>>();
    let sql = translate(sql);
    let rows = client.query(&sql, &refs).map_err(pg_error)?;
    rows.iter()
        .map(|row| {
            let values = row
                .columns()
                .iter()
                .enumerate()
                .map(|(index, column)| pg_value(row, index, column.type_()))
                .collect::<rusqlite::Result<Vec<_>>>()?;
            Ok(Row { values })
        })
        .collect()
}

fn pg_value(row: &postgres::Row, index: usize, kind: &PgType) -> rusqlite::Result<Value> {
    let value = match *kind {
        PgType::BOOL => row
            .try_get::<_, Option<bool>>(index)
            .map(|value| value.map_or(Value::Null, |value| Value::Integer(i64::from(value)))),
        PgType::INT2 => row
            .try_get::<_, Option<i16>>(index)
            .map(|value| value.map_or(Value::Null, |value| Value::Integer(i64::from(value)))),
        PgType::INT4 => row
            .try_get::<_, Option<i32>>(index)
            .map(|value| value.map_or(Value::Null, |value| Value::Integer(i64::from(value)))),
        PgType::INT8 => row
            .try_get::<_, Option<i64>>(index)
            .map(|value| value.map_or(Value::Null, Value::Integer)),
        PgType::FLOAT4 => row
            .try_get::<_, Option<f32>>(index)
            .map(|value| value.map_or(Value::Null, |value| Value::Real(f64::from(value)))),
        PgType::FLOAT8 => row
            .try_get::<_, Option<f64>>(index)
            .map(|value| value.map_or(Value::Null, Value::Real)),
        PgType::BYTEA => row
            .try_get::<_, Option<Vec<u8>>>(index)
            .map(|value| value.map_or(Value::Null, Value::Blob)),
        _ => row
            .try_get::<_, Option<String>>(index)
            .map(|value| value.map_or(Value::Null, Value::Text)),
    };
    value.map_err(pg_error)
}

fn pg_error(error: postgres::Error) -> rusqlite::Error {
    if let Some(database_error) = error.as_db_error() {
        tracing::error!(
            code = database_error.code().code(),
            message = database_error.message(),
            "PostgreSQL query failed"
        );
    } else {
        tracing::error!("PostgreSQL connection or decoding failed");
    }
    rusqlite::Error::ToSqlConversionFailure(Box::new(error))
}

fn translate(sql: &str) -> String {
    let mut sql = sql.to_owned();
    for number in 1..=12 {
        sql = sql.replace(
            &format!(" GLOB ?{number}"),
            &format!(" LIKE REPLACE(?{number}, '*', '%')"),
        );
    }
    let sql = sql.replace("MAX(incomplete,?3)", "GREATEST(incomplete,?3)");
    let ignored = sql.trim_start().starts_with("INSERT OR IGNORE INTO ");
    let sql = sql.replacen("INSERT OR IGNORE INTO ", "INSERT INTO ", 1);
    let mut translated = String::with_capacity(sql.len() + 24);
    let mut sequential = 0;
    let mut chars = sql.chars().peekable();
    while let Some(ch) = chars.next() {
        if ch == '?' {
            let mut number = String::new();
            while chars.peek().is_some_and(char::is_ascii_digit) {
                number.push(chars.next().unwrap_or_default());
            }
            if number.is_empty() {
                sequential += 1;
                number = sequential.to_string();
            }
            translated.push('$');
            translated.push_str(&number);
        } else {
            translated.push(ch);
        }
    }
    if ignored {
        translated.push_str(" ON CONFLICT DO NOTHING");
    }
    translated
}
