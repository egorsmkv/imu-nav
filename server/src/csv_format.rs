use crate::{CellKey, CellTower, Radio};
use flate2::Compression;
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use std::io::{Cursor, Read};
use std::path::PathBuf;
use std::str::FromStr;

const MAX_DECOMPRESSED_BYTES: u64 = 128 * 1024 * 1024;

/// Failure while decoding an app-compatible tower upload or import.
#[derive(Debug, thiserror::Error)]
pub enum CsvDecodeError {
    #[error("invalid gzip body")]
    InvalidGzip(#[source] std::io::Error),
    #[error("decompressed CSV exceeds {MAX_DECOMPRESSED_BYTES} bytes")]
    DecompressedBodyTooLarge,
    #[error("invalid CSV body")]
    InvalidCsv(#[from] csv::Error),
    #[error("CSV contains more than {limit} valid tower rows")]
    TooManyRows { limit: usize },
}

/// Failure while encoding an app-compatible tower download.
#[derive(Debug, thiserror::Error)]
pub enum CsvEncodeError {
    #[error("could not serialize CSV")]
    Csv(#[from] csv::Error),
    #[error("could not compress CSV")]
    Io(#[from] std::io::Error),
}

/// Failure while reading a seed import from disk.
#[derive(Debug, thiserror::Error)]
pub enum ImportError {
    #[error("cannot read {path}")]
    Read {
        path: PathBuf,
        source: std::io::Error,
    },
    #[error(transparent)]
    Decode(#[from] CsvDecodeError),
}

pub(crate) const HEADER: [&str; 14] = [
    "radio",
    "mcc",
    "net",
    "area",
    "cell",
    "unit",
    "lon",
    "lat",
    "range",
    "samples",
    "changeable",
    "created",
    "updated",
    "averageSignal",
];

/// Decode plain or gzip-compressed `OpenCellID` CSV, ignoring malformed rows like the Android client.
///
/// # Errors
///
/// Returns an error for corrupt gzip or CSV data and when the valid-row limit is exceeded.
#[cfg_attr(feature = "profiling", hotpath::measure)]
pub fn decode_towers(bytes: &[u8], limit: usize) -> Result<Vec<CellTower>, CsvDecodeError> {
    let decoded = if bytes.starts_with(&[0x1f, 0x8b]) {
        let mut decoded = Vec::new();
        GzDecoder::new(Cursor::new(bytes))
            .take(MAX_DECOMPRESSED_BYTES + 1)
            .read_to_end(&mut decoded)
            .map_err(CsvDecodeError::InvalidGzip)?;
        if u64::try_from(decoded.len()).unwrap_or(u64::MAX) > MAX_DECOMPRESSED_BYTES {
            return Err(CsvDecodeError::DecompressedBodyTooLarge);
        }
        decoded
    } else {
        bytes.to_vec()
    };

    let mut towers = Vec::new();
    let mut reader = csv::ReaderBuilder::new()
        .has_headers(false)
        .flexible(true)
        .from_reader(decoded.as_slice());
    for row in reader.records() {
        let row = row?;
        if row
            .get(0)
            .is_some_and(|value| value.eq_ignore_ascii_case("radio"))
        {
            continue;
        }
        let Some(tower) = parse_tower(&row) else {
            continue;
        };
        if towers.len() >= limit {
            return Err(CsvDecodeError::TooManyRows { limit });
        }
        towers.push(tower);
    }
    Ok(towers)
}

pub(crate) fn parse_tower(row: &csv::StringRecord) -> Option<CellTower> {
    Some(CellTower {
        key: CellKey {
            radio: Radio::from_str(row.get(0)?).ok()?,
            mcc: row.get(1)?.parse().ok()?,
            mnc: row.get(2)?.parse().ok()?,
            area: row.get(3)?.parse().ok()?,
            cid: row.get(4)?.parse().ok()?,
        },
        lon: row.get(6)?.parse().ok()?,
        lat: row.get(7)?.parse().ok()?,
        range_m: row.get(8)?.parse().ok()?,
        samples: row.get(9)?.parse().ok()?,
    })
}

/// Encode published towers as gzip-compressed `OpenCellID` CSV for existing Android clients.
///
/// # Errors
///
/// Returns an error if CSV serialization or gzip compression fails.
#[cfg_attr(feature = "profiling", hotpath::measure)]
pub fn encode_towers(towers: &[crate::Consensus]) -> Result<Vec<u8>, CsvEncodeError> {
    let output = Vec::new();
    let encoder = GzEncoder::new(output, Compression::default());
    let mut writer = csv::WriterBuilder::new()
        .has_headers(false)
        .from_writer(encoder);
    writer.write_record(HEADER)?;
    for consensus in towers {
        write_tower_record(&mut writer, consensus)?;
    }
    writer.flush()?;
    let encoder = writer
        .into_inner()
        .map_err(csv::IntoInnerError::into_error)?;
    Ok(encoder.finish()?)
}

/// Keep streamed and in-memory downloads identical after decompression.
pub(crate) fn write_tower_record<W: std::io::Write>(
    writer: &mut csv::Writer<W>,
    consensus: &crate::Consensus,
) -> Result<(), csv::Error> {
    let tower = &consensus.tower;
    writer.write_record([
        tower.key.radio.to_string(),
        tower.key.mcc.to_string(),
        tower.key.mnc.to_string(),
        tower.key.area.to_string(),
        tower.key.cid.to_string(),
        String::new(),
        format!("{:.7}", tower.lon),
        format!("{:.7}", tower.lat),
        tower.range_m.trunc().to_string(),
        tower.samples.to_string(),
        "1".to_owned(),
        consensus.updated_s.to_string(),
        consensus.updated_s.to_string(),
        String::new(),
    ])?;
    Ok(())
}

/// Read all bytes from an import file before parsing it with the shared decoder.
///
/// # Errors
///
/// Returns an error when the file cannot be read or contains invalid encoded data.
pub fn read_import(path: &std::path::Path, limit: usize) -> Result<Vec<CellTower>, ImportError> {
    let bytes = std::fs::read(path).map_err(|source| ImportError::Read {
        path: path.to_path_buf(),
        source,
    })?;
    Ok(decode_towers(&bytes, limit)?)
}
