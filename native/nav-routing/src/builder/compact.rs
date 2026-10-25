//! Host-only finalization: compact trusted staged profiles without touching search data or topology.
// Staging uses the old fixed-width layout internally, never accepted by the shipped reader.
use crate::{
    Result,
    file_data::FileData,
    shared::{REVERSED, SCALE},
};
use std::{
    collections::{HashMap, hash_map::DefaultHasher},
    fs::File,
    hash::{Hash, Hasher},
    io::{BufWriter, Read, Seek, SeekFrom, Write},
    path::Path,
};
const HEADER: usize = 72;
/// Dictionary entries keep only small descriptors in RAM; coordinate payloads stay on disk.
struct Geometry {
    offset: u64,
    count: u32,
    next: Option<u32>,
}
pub(super) struct Dictionary {
    points: BufWriter<File>,
    reader: File,
    bytes: u64,
    elevation: bool,
    geometries: Vec<Geometry>,
    buckets: HashMap<u64, u32>,
    names: Vec<String>,
    name_ids: HashMap<String, u32>,
}
impl Dictionary {
    pub(super) fn new(directory: &Path, elevation: bool) -> Result<Self> {
        let path = directory.join("geometry.staging");
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .open(&path)?;
        Ok(Self {
            reader: file.try_clone()?,
            points: BufWriter::with_capacity(1024 * 1024, file),
            bytes: 0,
            elevation,
            geometries: vec![],
            buckets: HashMap::new(),
            names: vec![],
            name_ids: HashMap::new(),
        })
    }
    pub(super) fn name(&mut self, name: &str) -> Result<u32> {
        if let Some(&id) = self.name_ids.get(name) {
            return Ok(id);
        }
        let id = u32::try_from(self.names.len())?;
        self.names.push(name.to_owned());
        self.name_ids.insert(name.to_owned(), id);
        Ok(id)
    }
    pub(super) fn width(&self) -> usize {
        if self.elevation { 16 } else { 8 }
    }
    pub(super) fn geometry(&mut self, mut encoded: Vec<u8>) -> Result<(u32, bool)> {
        let width = self.width();
        let reverse = encoded
            .chunks_exact(width)
            .cmp(encoded.chunks_exact(width).rev())
            .is_gt();
        if reverse {
            for index in 0..encoded.len() / width / 2 {
                let other = encoded.len() / width - 1 - index;
                for byte in 0..width {
                    encoded.swap(index * width + byte, other * width + byte);
                }
            }
        }
        let mut hash = DefaultHasher::new();
        encoded.hash(&mut hash);
        let hash = hash.finish();
        let head = self.buckets.get(&hash).copied();
        let mut candidate = head;
        while let Some(id) = candidate {
            let entry = &self.geometries[id as usize];
            let offset = entry.offset;
            let count = entry.count;
            candidate = entry.next;
            if count as usize * width == encoded.len() && self.equal(offset, &encoded)? {
                return Ok((id, reverse));
            }
        }
        let id = u32::try_from(self.geometries.len())?;
        self.geometries.push(Geometry {
            offset: self.bytes,
            count: u32::try_from(encoded.len() / width)?,
            next: head,
        });
        self.buckets.insert(hash, id);
        self.points.write_all(&encoded)?;
        self.bytes += encoded.len() as u64;
        Ok((id, reverse))
    }
    fn equal(&mut self, offset: u64, encoded: &[u8]) -> Result<bool> {
        let committed = self.bytes - self.points.buffer().len() as u64;
        if offset >= committed {
            let start = usize::try_from(offset - committed)?;
            return Ok(self.points.buffer().get(start..start + encoded.len()) == Some(encoded));
        }
        // A geometry may straddle the buffered tail. Flush only in that uncommon case.
        if offset + encoded.len() as u64 > committed {
            self.points.flush()?;
        }
        let mut existing = vec![0; encoded.len()];
        // The cloned descriptor shares a cursor. Restore the writer position before its next flush.
        self.reader.seek(SeekFrom::Start(offset))?;
        self.reader.read_exact(&mut existing)?;
        self.reader.seek(SeekFrom::Start(
            self.bytes - self.points.buffer().len() as u64,
        ))?;
        Ok(existing == encoded)
    }
    pub(super) fn finish(mut self, directory: &Path) -> Result<()> {
        self.points.flush()?;
        let mut output = BufWriter::new(File::create_new(directory.join(crate::shared::FILE))?);
        output.write_all(crate::shared::MAGIC)?;
        let names_length: usize = self.names.iter().map(String::len).sum();
        for value in [
            self.geometries.len() as u64,
            self.names.len() as u64,
            self.bytes / self.width() as u64,
            names_length as u64,
            u64::from(self.elevation),
            0,
            0,
        ] {
            output.write_all(&value.to_le_bytes())?;
        }
        for entry in &self.geometries {
            descriptor(&mut output, entry.offset / self.width() as u64, entry.count)?;
        }
        let mut offset = 0;
        for name in &self.names {
            descriptor(&mut output, offset, u32::try_from(name.len())?)?;
            offset += name.len() as u64;
        }
        self.reader.seek(SeekFrom::Start(0))?;
        std::io::copy(&mut self.reader, &mut output)?;
        for name in &self.names {
            output.write_all(name.as_bytes())?;
        }
        output.flush()?;
        drop(self);
        std::fs::remove_file(directory.join("geometry.staging"))?;
        Ok(())
    }
}
fn descriptor(out: &mut impl Write, offset: u64, count: u32) -> Result<()> {
    out.write_all(&offset.to_le_bytes())?;
    out.write_all(&count.to_le_bytes())?;
    out.write_all(&0u32.to_le_bytes())?;
    Ok(())
}
struct Staged {
    data: FileData,
    count: usize,
    points: usize,
    names: usize,
    offsets: [usize; 2],
    arcs: [usize; 2],
    arc_counts: [usize; 2],
    graph_count: usize,
    kind: u64,
    grid: usize,
    grid_count: usize,
}
impl Staged {
    fn open(path: &Path) -> Result<Self> {
        let data = FileData::open(path)?;
        if data.len() < 64 || &data.bytes::<8>(0) != b"IMUST001" {
            return Err("invalid staged profile".into());
        }
        let number =
            |at| -> Result<usize> { Ok(usize::try_from(u64::from_le_bytes(data.bytes::<8>(at)))?) };
        let count = number(8)?;
        let point_count = number(16)?;
        let name_length = number(24)?;
        let arc_counts = [number(32)?, number(40)?];
        let graph_count = number(48)?;
        let kind = number(56)? as u64;
        let mut cursor = 64;
        crate::shared::section(&mut cursor, count, 64, data.len())?;
        let points = crate::shared::section(&mut cursor, point_count, 24, data.len())?;
        let names = crate::shared::section(&mut cursor, name_length, 1, data.len())?;
        let offsets = [
            crate::shared::section(&mut cursor, graph_count + 1, 8, data.len())?,
            crate::shared::section(&mut cursor, graph_count + 1, 8, data.len())?,
        ];
        let arcs = [
            crate::shared::section(&mut cursor, arc_counts[0], 16, data.len())?,
            crate::shared::section(&mut cursor, arc_counts[1], 16, data.len())?,
        ];
        let grid = cursor;
        if !(data.len() - grid).is_multiple_of(12) {
            return Err("invalid staged grid".into());
        }
        let grid_count = (data.len() - grid) / 12;
        Ok(Self {
            data,
            count,
            points,
            names,
            offsets,
            arcs,
            arc_counts,
            graph_count,
            kind,
            grid,
            grid_count,
        })
    }
    fn u32(&self, at: usize) -> u32 {
        u32::from_le_bytes(self.data.bytes::<4>(at))
    }
    fn u64(&self, at: usize) -> u64 {
        u64::from_le_bytes(self.data.bytes::<8>(at))
    }
    fn state(&self, id: usize, dictionary: &mut Dictionary, out: &mut impl Write) -> Result<()> {
        let at = 64 + id * 64;
        let start = self.u32(at + 12) as usize;
        let count = self.u32(at + 16) as usize;
        let mut encoded = Vec::with_capacity(count * dictionary.width());
        for index in start..start + count {
            let point = self.points + index * 24;
            for coordinate in [point, point + 8] {
                let value = f64::from_bits(self.u64(coordinate));
                if !value.is_finite() || !(-180.0..=180.0).contains(&value) {
                    return Err("invalid coordinate".into());
                }
                // E7 is bounded to +/- 1.8 billion by the coordinate range above.
                #[allow(clippy::cast_possible_truncation)]
                let fixed = (value * SCALE).round() as i32;
                encoded.extend_from_slice(&fixed.to_le_bytes());
            }
            if dictionary.elevation {
                encoded.extend_from_slice(&self.u64(point + 16).to_le_bytes());
            }
        }
        let (geometry, reverse) = dictionary.geometry(encoded)?;
        let name = self.data.with_slice(
            self.names + self.u32(at + 20) as usize,
            self.u32(at + 24) as usize,
            |bytes| std::str::from_utf8(bytes).map(str::to_owned),
        )?;
        let name_id = dictionary.name(&name)?;
        for value in [
            self.u32(at),
            self.u32(at + 4),
            geometry,
            name_id,
            self.u32(at + 28),
        ] {
            out.write_all(&value.to_le_bytes())?;
        }
        out.write_all(&self.data.bytes::<2>(at + 32))?;
        let flags =
            u16::from_le_bytes(self.data.bytes::<2>(at + 34)) | if reverse { REVERSED } else { 0 };
        out.write_all(&flags.to_le_bytes())?;
        out.write_all(&self.data.bytes::<8>(at + 40))?;
        out.write_all(&self.data.bytes::<8>(at + 56))?;
        Ok(())
    }
    fn write(&self, path: &Path, dictionary: &mut Dictionary) -> Result<()> {
        let mut out = BufWriter::new(File::create_new(path)?);
        let mut widths = 0u64;
        for dir in 0..2 {
            if self.arc_counts[dir] > u32::MAX as usize {
                widths |= 1 << dir;
            }
            if (0..self.arc_counts[dir])
                .any(|index| self.u64(self.arcs[dir] + index * 16 + 8) > u64::from(u32::MAX))
            {
                widths |= 4 << dir;
            }
        }
        let mut cells = Vec::new();
        for index in 0..self.grid_count {
            let key = u32::try_from(self.u64(self.grid + index * 12))?;
            if cells.last().is_none_or(|&(previous, _)| previous != key) {
                cells.push((key, index as u64));
            }
        }
        out.write_all(crate::storage::MAGIC)?;
        for value in [
            self.count as u64,
            self.graph_count as u64,
            self.kind,
            self.arc_counts[0] as u64,
            self.arc_counts[1] as u64,
            widths,
            cells.len() as u64,
            self.grid_count as u64,
        ] {
            out.write_all(&value.to_le_bytes())?;
        }
        debug_assert_eq!(HEADER, 8 + 8 * 8);
        for id in 0..self.count {
            self.state(id, dictionary, &mut out)?;
        }
        for dir in 0..2 {
            for id in 0..=self.graph_count {
                integer(
                    &mut out,
                    self.u64(self.offsets[dir] + id * 8),
                    widths & (1 << dir) != 0,
                )?;
            }
        }
        for dir in 0..2 {
            for index in 0..self.arc_counts[dir] {
                let at = self.arcs[dir] + index * 16;
                out.write_all(&self.data.bytes::<8>(at))?;
                integer(&mut out, self.u64(at + 8), widths & (4 << dir) != 0)?;
            }
        }
        for &(key, _) in &cells {
            out.write_all(&key.to_le_bytes())?;
        }
        for &(_, offset) in &cells {
            out.write_all(&offset.to_le_bytes())?;
        }
        out.write_all(&(self.grid_count as u64).to_le_bytes())?;
        for index in 0..self.grid_count {
            out.write_all(&self.data.bytes::<4>(self.grid + index * 12 + 8))?;
        }
        out.flush()?;
        Ok(())
    }
}
/// Preserve integer costs exactly; callers choose wide storage before writing any section.
fn integer(out: &mut impl Write, value: u64, wide: bool) -> Result<()> {
    if wide {
        out.write_all(&value.to_le_bytes())?;
    } else {
        out.write_all(&u32::try_from(value)?.to_le_bytes())?;
    }
    Ok(())
}
pub fn finish(directory: &Path, profiles: &[&str], elevation: bool) -> Result<()> {
    let started = std::time::Instant::now();
    let mut dictionary = Dictionary::new(directory, elevation)?;
    for profile in profiles {
        println!("[routing] profile={profile} compacting=true");
        let path = directory.join(format!("{profile}.routing"));
        let compact = path.with_extension("compact");
        let staged = Staged::open(&path)?;
        staged.write(&compact, &mut dictionary)?;
        drop(staged);
        std::fs::rename(compact, path)?;
    }
    let geometries = dictionary.geometries.len();
    let points = dictionary.bytes / dictionary.width() as u64;
    dictionary.finish(directory)?;
    println!(
        "[routing] compact_seconds={:.3} geometries={geometries} points={points}",
        started.elapsed().as_secs_f64()
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn dictionary_checks_collisions_and_reversed_buffered_geometry() {
        let directory = tempfile::tempdir().unwrap();
        let mut dictionary = Dictionary::new(directory.path(), false).unwrap();
        let first: Vec<u8> = [1i32, 2, 3, 4]
            .into_iter()
            .flat_map(i32::to_le_bytes)
            .collect();
        let reversed: Vec<u8> = [3i32, 4, 1, 2]
            .into_iter()
            .flat_map(i32::to_le_bytes)
            .collect();
        assert_eq!(dictionary.geometry(first.clone()).unwrap(), (0, false));
        assert_eq!(dictionary.geometry(reversed).unwrap(), (0, true));
        dictionary.points.flush().unwrap();
        assert_eq!(dictionary.geometry(first).unwrap(), (0, false));
        let other: Vec<u8> = [5i32, 6, 7, 8]
            .into_iter()
            .flat_map(i32::to_le_bytes)
            .collect();
        let mut hash = DefaultHasher::new();
        other.hash(&mut hash);
        dictionary.buckets.insert(hash.finish(), 0);
        assert_eq!(dictionary.geometry(other.clone()).unwrap(), (1, false));
        assert_eq!(dictionary.geometry(other).unwrap(), (1, false));
        dictionary.name("road").unwrap();
        dictionary.finish(directory.path()).unwrap();
        let shared =
            crate::shared::Shared::open(&directory.path().join(crate::shared::FILE)).unwrap();
        assert_eq!(shared.geometries, 2);
        assert_eq!(shared.names, 1);
    }
    #[test]
    fn compact_integer_boundaries_never_truncate() {
        let mut bytes = vec![];
        integer(&mut bytes, u64::from(u32::MAX), false).unwrap();
        assert_eq!(bytes, u32::MAX.to_le_bytes());
        assert!(integer(&mut bytes, u64::from(u32::MAX) + 1, false).is_err());
        bytes.clear();
        integer(&mut bytes, u64::from(u32::MAX) + 1, true).unwrap();
        assert_eq!(
            u64::from_le_bytes(bytes.try_into().unwrap()),
            u64::from(u32::MAX) + 1
        );
    }
}
