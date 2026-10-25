//! Shared, direction-independent E7 geometry and UTF-8 names, validated once per pack.
use crate::{Result, file_data::FileData, geo::Point};
use std::path::Path;
pub const MAGIC: &[u8; 8] = b"IMUGEO02";
pub const FILE: &str = "shared.routing";
pub const SCALE: f64 = 10_000_000.;
pub const REVERSED: u16 = 32;
/// Immutable shared data; profile readers retain the same owner through an Arc.
pub struct Shared {
    data: FileData,
    pub geometries: usize,
    pub names: usize,
    geometry_table: usize,
    name_table: usize,
    points: usize,
    name_bytes: usize,
    point_width: usize,
}
/// Checked section arithmetic before any fixed-width record is read.
pub(crate) fn section(
    cursor: &mut usize,
    count: usize,
    width: usize,
    length: usize,
) -> Result<usize> {
    let start = *cursor;
    *cursor = cursor
        .checked_add(count.checked_mul(width).ok_or("section overflow")?)
        .ok_or("section overflow")?;
    if *cursor > length {
        return Err("truncated routing section".into());
    }
    Ok(start)
}
impl Shared {
    pub fn open(path: &Path) -> Result<Self> {
        let data = FileData::open(path)?;
        if data.len() < 64 || &data.bytes::<8>(0) != MAGIC {
            return Err("unsupported shared routing data".into());
        }
        let number =
            |at| -> Result<usize> { Ok(usize::try_from(u64::from_le_bytes(data.bytes::<8>(at)))?) };
        let geometries = number(8)?;
        let names = number(16)?;
        let point_count = number(24)?;
        let name_length = number(32)?;
        let elevation = number(40)?;
        if geometries > 60_000_000
            || names > 60_000_000
            || elevation > 1
            || number(48)? != 0
            || number(56)? != 0
        {
            return Err("invalid shared routing header".into());
        }
        let point_width = if elevation == 1 { 16 } else { 8 };
        let mut cursor = 64;
        let geometry_table = section(&mut cursor, geometries, 16, data.len())?;
        let name_table = section(&mut cursor, names, 16, data.len())?;
        let points = section(&mut cursor, point_count, point_width, data.len())?;
        let name_bytes = section(&mut cursor, name_length, 1, data.len())?;
        if cursor != data.len() {
            return Err("trailing shared data".into());
        }
        let shared = Self {
            data,
            geometries,
            names,
            geometry_table,
            name_table,
            points,
            name_bytes,
            point_width,
        };
        shared.validate(point_count, name_length)?;
        Ok(shared)
    }
    fn validate(&self, point_count: usize, name_length: usize) -> Result<()> {
        for id in 0..self.geometries {
            let (offset, count) = self.descriptor(self.geometry_table, id);
            if self.data.bytes::<4>(self.geometry_table + id * 16 + 12) != [0; 4]
                || count < 2
                || offset > point_count
                || count as usize > point_count - offset
            {
                return Err("invalid shared geometry range".into());
            }
        }
        for id in 0..self.names {
            let (offset, count) = self.descriptor(self.name_table, id);
            if self.data.bytes::<4>(self.name_table + id * 16 + 12) != [0; 4]
                || count > 65_536
                || offset > name_length
                || count as usize > name_length - offset
                || !self
                    .data
                    .with_slice(self.name_bytes + offset, count as usize, |v| {
                        std::str::from_utf8(v).is_ok()
                    })
            {
                return Err("invalid shared name".into());
            }
        }
        for id in 0..point_count {
            let point = self.point(id);
            if !crate::geo::valid(point) || point.elevation.is_infinite() {
                return Err("invalid shared coordinate".into());
            }
        }
        Ok(())
    }
    fn descriptor(&self, table: usize, id: usize) -> (usize, u32) {
        let offset = u64::from_le_bytes(self.data.bytes::<8>(table + id * 16));
        // Section validation bounds every offset to the addressable file before public access.
        (
            usize::try_from(offset).unwrap_or(usize::MAX),
            u32::from_le_bytes(self.data.bytes::<4>(table + id * 16 + 8)),
        )
    }
    pub fn geometry(&self, id: u32) -> (usize, u32) {
        self.descriptor(self.geometry_table, id as usize)
    }
    pub fn point(&self, id: usize) -> Point {
        let at = self.points + id * self.point_width;
        Point {
            lat: f64::from(i32::from_le_bytes(self.data.bytes::<4>(at))) / SCALE,
            lon: f64::from(i32::from_le_bytes(self.data.bytes::<4>(at + 4))) / SCALE,
            elevation: if self.point_width == 16 {
                f64::from_le_bytes(self.data.bytes::<8>(at + 8))
            } else {
                f64::NAN
            },
        }
    }
    pub fn name(&self, id: u32) -> String {
        let (offset, count) = self.descriptor(self.name_table, id as usize);
        self.data
            .with_slice(self.name_bytes + offset, count as usize, |v| {
                std::str::from_utf8(v).unwrap_or("").to_owned()
            })
    }
}
