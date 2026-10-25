//! Fixed-width, checked, memory-mapped routing data. No deserialization of attacker-controlled vectors.
// Coordinates, grid cells, speeds and file indices have explicit bounds in the importer/loader.
// Integer-to-float conversions here stay below the exact-integer range of f64.
#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss
)]
use crate::file_data::FileData;
use crate::{Result, geo::Point};
use std::path::Path;
pub const NONE: u32 = u32::MAX;
pub const STATE_BYTES: usize = 64;
pub const MAGIC: &[u8; 8] = b"IMURT001";
#[derive(Clone, Copy, Debug)]
pub struct State {
    pub from: u32,
    pub to: u32,
    pub reverse: u32,
    pub point_start: u32,
    pub point_count: u32,
    pub name_start: u32,
    pub name_len: u32,
    pub speed: f32,
    pub maxspeed: u16,
    pub flags: u16,
    pub weight: u64,
    pub millis: u64,
    pub distance: f64,
}
#[derive(Clone, Copy, Debug)]
pub struct Arc {
    pub head: u32,
    pub skipped: u32,
    pub weight: u64,
}
/// Each profile is immutable while installed; the owner prevents replacement during reads.
pub struct Network {
    data: FileData,
    pub count: usize,
    pub point_count: usize,
    pub graph_count: usize,
    pub node_based: bool,
    states: usize,
    points: usize,
    names: usize,
    name_len: usize,
    offsets: [usize; 2],
    arcs: [usize; 2],
    arc_counts: [usize; 2],
    grid: usize,
    grid_count: usize,
}
impl Network {
    /// # Errors
    /// Returns malformed input, unavailable graph data, I/O, or request cancellation errors.
    pub fn open(path: &Path) -> Result<Self> {
        let data = FileData::open(path)?;
        if data.len() < 64 || &data.bytes::<8>(0) != MAGIC {
            return Err("unsupported routing data".into());
        }
        let read = |at| -> Result<u64> { Ok(u64::from_le_bytes(data.bytes::<8>(at))) };
        let count = usize::try_from(read(8)?)?;
        let points_count = usize::try_from(read(16)?)?;
        let name_len = usize::try_from(read(24)?)?;
        let up_count = usize::try_from(read(32)?)?;
        let down_count = usize::try_from(read(40)?)?;
        let graph_count = usize::try_from(read(48)?)?;
        let kind = read(56)?;
        let node_based = kind == 1;
        if kind > 1
            || graph_count == 0
            || graph_count > 30_000_000
            || !node_based && graph_count != count
            || points_count > u32::MAX as usize
            || count == 0
            || count > 30_000_000
        {
            return Err("invalid state count".into());
        }
        let mut cursor = 64usize;
        let mut section = |count: usize, size: usize| -> Result<usize> {
            let start = cursor;
            cursor = cursor
                .checked_add(count.checked_mul(size).ok_or("section overflow")?)
                .ok_or("section overflow")?;
            if cursor > data.len() {
                return Err("truncated routing data".into());
            }
            Ok(start)
        };
        let states = section(count, STATE_BYTES)?;
        let points = section(points_count, 24)?;
        let names = section(name_len, 1)?;
        let offsets = [section(graph_count + 1, 8)?, section(graph_count + 1, 8)?];
        let arcs = [section(up_count, 16)?, section(down_count, 16)?];
        let grid = cursor;
        if !(data.len() - grid).is_multiple_of(12) {
            return Err("invalid snap grid".into());
        }
        let grid_count = (data.len() - grid) / 12;
        let network = Self {
            data,
            count,
            point_count: points_count,
            graph_count,
            node_based,
            states,
            points,
            names,
            name_len,
            offsets,
            arcs,
            arc_counts: [up_count, down_count],
            grid,
            grid_count,
        };
        network.validate()?;
        Ok(network)
    }
    fn u32(&self, at: usize) -> u32 {
        u32::from_le_bytes(self.data.bytes::<4>(at))
    }
    fn u64(&self, at: usize) -> u64 {
        u64::from_le_bytes(self.data.bytes::<8>(at))
    }
    #[must_use]
    /// # Panics
    /// The ID/range must belong to this validated network.
    pub fn state(&self, id: u32) -> State {
        let at = self.states + id as usize * STATE_BYTES;
        State {
            from: self.u32(at),
            to: self.u32(at + 4),
            reverse: self.u32(at + 8),
            point_start: self.u32(at + 12),
            point_count: self.u32(at + 16),
            name_start: self.u32(at + 20),
            name_len: self.u32(at + 24),
            speed: f32::from_bits(self.u32(at + 28)),
            maxspeed: u16::from_le_bytes(self.data.bytes::<2>(at + 32)),
            flags: u16::from_le_bytes(self.data.bytes::<2>(at + 34)),
            weight: self.u64(at + 40),
            millis: self.u64(at + 48),
            distance: f64::from_bits(self.u64(at + 56)),
        }
    }
    #[must_use]
    /// # Panics
    /// The ID/range must belong to this validated network.
    pub fn point(&self, id: u32) -> Point {
        let at = self.points + id as usize * 24;
        Point {
            lat: f64::from_bits(self.u64(at)),
            lon: f64::from_bits(self.u64(at + 8)),
            elevation: f64::from_bits(self.u64(at + 16)),
        }
    }
    #[must_use]
    /// # Panics
    /// The ID/range must belong to this validated network.
    pub fn name(&self, state: State) -> String {
        self.data.with_slice(
            self.names + state.name_start as usize,
            state.name_len as usize,
            |bytes| std::str::from_utf8(bytes).unwrap_or("").to_owned(),
        )
    }
    #[must_use]
    /// # Panics
    /// The ID/range must belong to this validated network.
    pub fn arc(&self, dir: usize, index: usize) -> Arc {
        let at = self.arcs[dir] + index * 16;
        Arc {
            head: self.u32(at),
            skipped: self.u32(at + 4),
            weight: self.u64(at + 8),
        }
    }
    #[must_use]
    /// # Panics
    /// The ID/range must belong to this validated network.
    pub fn outgoing(&self, dir: usize, id: u32) -> std::ops::Range<usize> {
        self.u64(self.offsets[dir] + id as usize * 8) as usize
            ..self.u64(self.offsets[dir] + (id as usize + 1) * 8) as usize
    }
    #[must_use]
    pub fn child(&self, dir: usize, tail: u32, head: u32) -> Option<Arc> {
        self.outgoing(dir, tail)
            .map(|i| self.arc(dir, i))
            .filter(|a| a.head == head)
            .min_by_key(|a| a.weight)
    }
    #[must_use]
    pub fn from_node(&self, node: u32) -> std::ops::Range<u32> {
        let bound = |upper: bool| {
            let mut lo = 0u32;
            let mut hi = self.count as u32;
            while lo < hi {
                let mid = lo + (hi - lo) / 2;
                let from = self.state(mid).from;
                if from < node || upper && from == node {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            lo
        };
        bound(false)..bound(true)
    }
    pub fn cell(&self, key: u64) -> impl Iterator<Item = u32> + '_ {
        let mut lo = 0;
        let mut hi = self.grid_count;
        while lo < hi {
            let mid = usize::midpoint(lo, hi);
            if self.u64(self.grid + mid * 12) < key {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        (lo..self.grid_count)
            .take_while(move |&i| self.u64(self.grid + i * 12) == key)
            .map(|i| self.u32(self.grid + i * 12 + 8))
    }
    fn validate(&self) -> Result<()> {
        for id in 0..self.count as u32 {
            let s = self.state(id);
            if id > 0 && self.state(id - 1).from > s.from {
                return Err("unsorted road states".into());
            }
            if s.point_count < 2
                || u64::from(s.point_start) + u64::from(s.point_count) > self.point_count as u64
                || u64::from(s.name_start) + u64::from(s.name_len) > self.name_len as u64
                || !(s.speed > 0.
                    && s.speed.is_finite()
                    && s.distance >= 0.
                    && s.distance.is_finite())
                || s.weight > 1_000_000_000_000
                || s.millis > 1_000_000_000_000
                || s.reverse != NONE && s.reverse as usize >= self.count
            {
                return Err("invalid road state".into());
            }
            if s.name_len > 65536
                || !self.data.with_slice(
                    self.names + s.name_start as usize,
                    s.name_len as usize,
                    |bytes| std::str::from_utf8(bytes).is_ok(),
                )
            {
                return Err("invalid road name".into());
            }
        }
        for i in 0..self.point_count {
            if !crate::geo::valid(self.point(i as u32)) {
                return Err("invalid geometry".into());
            }
        }
        self.validate_hierarchy()?;
        let mut previous = 0;
        for index in 0..self.grid_count {
            let key = self.u64(self.grid + index * 12);
            if key < previous || self.u32(self.grid + index * 12 + 8) as usize >= self.count {
                return Err("invalid snap index".into());
            }
            previous = key;
        }
        Ok(())
    }
    fn validate_hierarchy(&self) -> Result<()> {
        // Validate CSR before any traversals; then topologically validate both upward graphs together.
        let mut indegree = vec![0u32; self.graph_count];
        for dir in 0..2 {
            let mut previous = 0;
            for node in 0..=self.graph_count {
                let offset = self.u64(self.offsets[dir] + node * 8);
                if offset < previous
                    || offset > self.arc_counts[dir] as u64
                    || node == 0 && offset != 0
                {
                    return Err("invalid hierarchy offsets".into());
                }
                previous = offset;
            }
            if previous != self.arc_counts[dir] as u64 {
                return Err("invalid hierarchy length".into());
            }
            for tail in 0..self.graph_count as u32 {
                for index in self.outgoing(dir, tail) {
                    let arc = self.arc(dir, index);
                    if arc.head as usize >= self.graph_count
                        || arc.head == tail
                        || arc.skipped != NONE && arc.skipped as usize >= self.graph_count
                        || arc.weight > 1_000_000_000_000
                    {
                        return Err("invalid hierarchy arc".into());
                    }
                    indegree[arc.head as usize] = indegree[arc.head as usize]
                        .checked_add(1)
                        .ok_or("excessive degree")?;
                }
            }
        }
        let mut queue: std::collections::VecDeque<_> = indegree
            .iter()
            .enumerate()
            .filter(|(_, v)| **v == 0)
            .map(|(i, _)| i as u32)
            .collect();
        let mut visited = 0;
        while let Some(node) = queue.pop_front() {
            visited += 1;
            for dir in 0..2 {
                for i in self.outgoing(dir, node) {
                    let head = self.arc(dir, i).head as usize;
                    indegree[head] -= 1;
                    if indegree[head] == 0 {
                        queue.push_back(head as u32);
                    }
                }
            }
        }
        if visited != self.graph_count {
            return Err("cyclic hierarchy".into());
        }
        for dir in 0..2 {
            for tail in 0..self.graph_count as u32 {
                for index in self.outgoing(dir, tail) {
                    let a = self.arc(dir, index);
                    if a.skipped == NONE {
                        let (from, to) = if dir == 0 {
                            (tail, a.head)
                        } else {
                            (a.head, tail)
                        };
                        if if self.node_based {
                            !self.from_node(from).any(|id| {
                                let road = self.state(id);
                                road.to == to && road.weight == a.weight
                            })
                        } else {
                            self.state(from).to != self.state(to).from
                                || a.weight < self.state(to).weight
                        } {
                            return Err("disconnected transition".into());
                        }
                    } else {
                        let left = self
                            .child(1 - dir, a.skipped, tail)
                            .ok_or("missing shortcut child")?;
                        let right = self
                            .child(dir, a.skipped, a.head)
                            .ok_or("missing shortcut child")?;
                        if left.weight.checked_add(right.weight) != Some(a.weight) {
                            return Err("invalid shortcut cost".into());
                        }
                    }
                }
            }
        }
        Ok(())
    }
}
#[must_use]
pub fn cell_key(lat: i32, lon: i32) -> u64 {
    (lat.clamp(0, 8999) as u64) * 18000 + lon.rem_euclid(18000) as u64
}
#[must_use]
pub fn cell_of(point: Point) -> (i32, i32) {
    (
        ((point.lat + 90.) / 0.02).floor() as i32,
        ((point.lon + 180.) / 0.02).floor() as i32,
    )
}
