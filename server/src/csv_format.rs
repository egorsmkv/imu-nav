use crate::{CellKey, CellTower, Radio};
use flate2::Compression;
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use std::borrow::Cow;
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
        Cow::Owned(decoded)
    } else {
        Cow::Borrowed(bytes)
    };

    let mut towers = Vec::new();
    let mut reader = csv::ReaderBuilder::new()
        .has_headers(false)
        .flexible(true)
        .from_reader(decoded.as_ref());
    // Reuse the record's storage across rows; parsed towers contain only owned scalar values.
    let mut row = csv::StringRecord::new();
    while reader.read_record(&mut row)? {
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    const CSV: &[u8] = b"radio,mcc,net,area,cell,unit,lon,lat,range,samples\n\
        lTe,255,1,10,1,,30.5,50.4,500,5\n\
        malformed\n\
        NR,255,2,20,2,,30.6,50.5,600,6,extra\n";

    fn gzip(bytes: &[u8]) -> Vec<u8> {
        let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(bytes).unwrap();
        encoder.finish().unwrap()
    }

    #[test]
    fn plain_and_gzip_decoding_preserve_rows_when_record_storage_is_reused() {
        let compressed = gzip(CSV);
        for bytes in [CSV, compressed.as_slice()] {
            let towers = decode_towers(bytes, 2).unwrap();
            assert_eq!(towers.len(), 2);
            assert_eq!(towers[0].key.radio, Radio::Lte);
            assert_eq!(towers[0].key.cid, 1);
            assert_eq!(towers[0].samples, 5);
            assert_eq!(towers[1].key.radio, Radio::Nr);
            assert_eq!(towers[1].key.cid, 2);
            assert_eq!(towers[1].samples, 6);
            assert!(matches!(
                decode_towers(bytes, 1),
                Err(CsvDecodeError::TooManyRows { limit: 1 })
            ));
            assert!(matches!(
                decode_towers(bytes, 0),
                Err(CsvDecodeError::TooManyRows { limit: 0 })
            ));
        }
        assert!(decode_towers(b"radio\nmalformed\n", 0).unwrap().is_empty());
    }

    #[test]
    fn corrupt_gzip_and_invalid_csv_utf8_are_still_rejected() {
        let mut compressed = gzip(CSV);
        let checksum = compressed.len() - 8;
        compressed[checksum] ^= 1;
        assert!(matches!(
            decode_towers(&compressed, 2),
            Err(CsvDecodeError::InvalidGzip(_))
        ));
        assert!(matches!(
            decode_towers(b"\xff\n", 2),
            Err(CsvDecodeError::InvalidCsv(_))
        ));
    }
}
