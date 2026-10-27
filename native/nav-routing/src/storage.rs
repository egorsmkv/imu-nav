//! Fixed-width, checked, memory-mapped routing data. No deserialization of attacker-controlled vectors.
// Coordinates, grid cells, speeds and file indices have explicit bounds in the importer/loader.
// Integer-to-float conversions here stay below the exact-integer range of f64.
#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss
)]
use crate::file_data::FileData;
use crate::ids::{ArcIndex, Direction, GeometryId, NameId, NodeId, StateId, VertexId};
use crate::shared::{REVERSED, Shared, section};
use crate::{Result, geo::Point};
use std::{path::Path, sync::Arc as Owner};
pub(crate) const NONE: u32 = u32::MAX;
pub const STATE_BYTES: usize = 40;
pub const MAGIC: &[u8; 8] = b"IMURT003";
/// A purpose-limited road can be used to reach a selected endpoint, never for through traffic.
pub const LOCAL_ACCESS: u16 = 64;
/// Paired local-access states distinguish leaving an origin from approaching a destination.
pub const LOCAL_EXIT: u16 = 128;
/// Graph transitions cannot turn a local visit into permission for through traffic.
#[must_use]
pub const fn local_transition_allowed(from: u16, to: u16) -> bool {
    if from & LOCAL_ACCESS != 0 && from & LOCAL_EXIT == 0 && to & LOCAL_ACCESS == 0 {
        return false;
    }
    if to & LOCAL_ACCESS != 0 {
        let exit = from & LOCAL_ACCESS != 0 && from & LOCAL_EXIT != 0;
        return (to & LOCAL_EXIT != 0) == exit;
    }
    true
}
#[derive(Clone, Copy, Debug)]
pub struct State {
    pub from: NodeId,
    pub to: NodeId,
    pub geometry: GeometryId,
    pub point_start: usize,
    pub point_count: u32,
    pub name_id: NameId,
    pub speed: f32,
    pub maxspeed: u16,
    pub flags: u16,
    pub weight: u64,
    pub distance: f64,
}
#[derive(Clone, Copy, Debug)]
pub struct Arc {
    pub head: VertexId,
    pub skipped: Option<VertexId>,
    pub weight: u64,
}
/// Each profile is immutable while installed; the owner prevents replacement during reads.
pub struct Network {
    data: FileData,
    pub count: usize,
    shared: Owner<Shared>,
    pub graph_count: usize,
    pub node_based: bool,
    states: usize,
    offset_widths: [usize; 2],
    weight_widths: [usize; 2],
    offsets: [usize; 2],
    arcs: [usize; 2],
    arc_counts: [usize; 2],
    grid: usize,
    grid_count: usize,
    grid_offsets: usize,
    postings: usize,
    posting_count: usize,
}
impl Network {
    /// # Errors
    /// Returns malformed input, unavailable graph data, I/O, or request cancellation errors.
    pub fn open(path: &Path) -> Result<Self> {
        let shared = Owner::new(Shared::open(&path.with_file_name(crate::shared::FILE))?);
        Self::with_shared(path, shared, true)
    }
    /// `full == false` skips the record and hierarchy scans; see `Shared::open_checked`.
    pub(crate) fn with_shared(path: &Path, shared: Owner<Shared>, full: bool) -> Result<Self> {
        let data = FileData::open(path)?;
        if data.len() < 72 || &data.bytes::<8>(0) != MAGIC {
            return Err("unsupported routing data".into());
        }
        let read =
            |at| -> Result<usize> { Ok(usize::try_from(u64::from_le_bytes(data.bytes::<8>(at)))?) };
        let count = read(8)?;
        let graph_count = read(16)?;
        let kind = read(24)?;
        let arc_counts = [read(32)?, read(40)?];
        let widths = read(48)?;
        let grid_count = read(56)?;
        let posting_count = read(64)?;
        let node_based = kind == 1;
        if kind > 1
            || widths > 15
            || count == 0
            || count > 30_000_000
            || graph_count == 0
            || graph_count > 30_000_000
            || !node_based && graph_count != count
        {
            return Err("invalid routing header".into());
        }
        let offset_widths = [
            if widths & 1 == 0 { 4 } else { 8 },
            if widths & 2 == 0 { 4 } else { 8 },
        ];
        let weight_widths = [
            if widths & 4 == 0 { 4 } else { 8 },
            if widths & 8 == 0 { 4 } else { 8 },
        ];
        let mut cursor = 72;
        let states = section(&mut cursor, count, STATE_BYTES, data.len())?;
        let offsets = [
            section(&mut cursor, graph_count + 1, offset_widths[0], data.len())?,
            section(&mut cursor, graph_count + 1, offset_widths[1], data.len())?,
        ];
        let arcs = [
            section(&mut cursor, arc_counts[0], 8 + weight_widths[0], data.len())?,
            section(&mut cursor, arc_counts[1], 8 + weight_widths[1], data.len())?,
        ];
        let grid = section(&mut cursor, grid_count, 4, data.len())?;
        let grid_offsets = section(&mut cursor, grid_count + 1, 8, data.len())?;
        let postings = section(&mut cursor, posting_count, 4, data.len())?;
        if cursor != data.len() {
            return Err("trailing routing data".into());
        }
        let network = Self {
            data,
            count,
            shared,
            graph_count,
            node_based,
            states,
            offset_widths,
            weight_widths,
            offsets,
            arcs,
            arc_counts,
            grid,
            grid_count,
            grid_offsets,
            postings,
            posting_count,
        };
        if full {
            network.validate()?;
        }
        Ok(network)
    }
    #[must_use]
    pub fn has_elevation(&self) -> bool {
        self.shared.has_elevation()
    }
    fn integer(&self, at: usize, width: usize) -> u64 {
        if width == 4 {
            u64::from(self.u32(at))
        } else {
            self.u64(at)
        }
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
    pub fn state(&self, id: StateId) -> State {
        assert!(
            (id.get() as usize) < self.count,
            "road state outside network"
        );
        let at = self.states + id.get() as usize * STATE_BYTES;
        let geometry = GeometryId::new(self.u32(at + 8));
        let (point_start, point_count) = self.shared.geometry(geometry);
        State {
            from: NodeId::new(self.u32(at)),
            to: NodeId::new(self.u32(at + 4)),
            geometry,
            point_start,
            point_count,
            name_id: NameId::new(self.u32(at + 12)),
            speed: f32::from_bits(self.u32(at + 16)),
            maxspeed: u16::from_le_bytes(self.data.bytes::<2>(at + 20)),
            flags: u16::from_le_bytes(self.data.bytes::<2>(at + 22)),
            weight: self.u64(at + 24),
            distance: f64::from_bits(self.u64(at + 32)),
        }
    }
    #[must_use]
    /// # Panics
    /// The ID/range must belong to this validated network.
    pub fn point(&self, state: State, index: u32) -> Point {
        let (start, count) = (state.point_start, state.point_count);
        assert!(index < count, "geometry point outside road");
        let offset = if state.flags & REVERSED == 0 {
            index
        } else {
            count - 1 - index
        };
        self.shared.point(start + offset as usize)
    }
    #[must_use]
    pub fn name(&self, state: State) -> String {
        self.shared.name(state.name_id)
    }
    #[must_use]
    /// # Panics
    /// The ID/range must belong to this validated network.
    pub fn arc(&self, direction: Direction, index: ArcIndex) -> Arc {
        let dir = direction.index();
        assert!(index.get() < self.arc_counts[dir], "arc outside network");
        let at = self.arcs[dir] + index.get() * (8 + self.weight_widths[dir]);
        let skipped = self.u32(at + 4);
        Arc {
            head: VertexId::new(self.u32(at)),
            skipped: (skipped != NONE).then(|| VertexId::new(skipped)),
            weight: self.integer(at + 8, self.weight_widths[dir]),
        }
    }
    /// # Panics
    /// The ID/range must belong to this validated network.
    pub fn outgoing(&self, direction: Direction, id: VertexId) -> impl Iterator<Item = ArcIndex> {
        let dir = direction.index();
        assert!(
            (id.get() as usize) < self.graph_count,
            "vertex outside network"
        );
        let id = id.get();
        (self.integer(
            self.offsets[dir] + id as usize * self.offset_widths[dir],
            self.offset_widths[dir],
        ) as usize
            ..self.integer(
                self.offsets[dir] + (id as usize + 1) * self.offset_widths[dir],
                self.offset_widths[dir],
            ) as usize)
            .map(ArcIndex::new)
    }
    #[must_use]
    pub fn child(&self, dir: Direction, tail: VertexId, head: VertexId) -> Option<Arc> {
        self.outgoing(dir, tail)
            .map(|i| self.arc(dir, i))
            .filter(|a| a.head == head)
            .min_by_key(|a| a.weight)
    }
    pub fn from_node(&self, node: NodeId) -> impl Iterator<Item = StateId> + '_ {
        let node = node.get();
        let bound = |upper: bool| {
            let mut lo = 0u32;
            let mut hi = self.count as u32;
            while lo < hi {
                let mid = lo + (hi - lo) / 2;
                let from = self.u32(self.states + mid as usize * STATE_BYTES);
                if from < node || upper && from == node {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            lo
        };
        (bound(false)..bound(true)).map(StateId::new)
    }
    pub fn cell(&self, key: u64) -> impl Iterator<Item = StateId> + '_ {
        let mut lo = 0;
        let mut hi = self.grid_count;
        while lo < hi {
            let mid = usize::midpoint(lo, hi);
            if u64::from(self.u32(self.grid + mid * 4)) < key {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        let range = if lo < self.grid_count && u64::from(self.u32(self.grid + lo * 4)) == key {
            self.u64(self.grid_offsets + lo * 8) as usize
                ..self.u64(self.grid_offsets + (lo + 1) * 8) as usize
        } else {
            0..0
        };
        range.map(|i| StateId::new(self.u32(self.postings + i * 4)))
    }
    fn validate(&self) -> Result<()> {
        for id in 0..self.count as u32 {
            let at = self.states + id as usize * STATE_BYTES;
            if self.u32(at + 8) as usize >= self.shared.geometries
                || self.u32(at + 12) as usize >= self.shared.names
            {
                return Err("invalid shared reference".into());
            }
            let s = self.state(StateId::new(id));
            if id > 0 && self.u32(self.states + (id as usize - 1) * STATE_BYTES) > s.from.get() {
                return Err("unsorted road states".into());
            }
            if !(s.speed > 0. && s.speed.is_finite() && s.distance >= 0. && s.distance.is_finite())
                || s.weight > 1_000_000_000_000
                || s.flags & !255 != 0
                || s.flags & LOCAL_EXIT != 0 && s.flags & LOCAL_ACCESS == 0
            {
                return Err("invalid road state".into());
            }
            if s.flags & LOCAL_ACCESS != 0 {
                let twin = if s.flags & LOCAL_EXIT == 0 {
                    id.checked_add(1)
                } else {
                    id.checked_sub(1)
                }
                .filter(|&twin| (twin as usize) < self.count)
                .ok_or("missing local-access twin")?;
                let other = self.state(StateId::new(twin));
                if s.from != other.from
                    || s.to != other.to
                    || s.geometry != other.geometry
                    || s.name_id != other.name_id
                    || s.flags ^ other.flags != LOCAL_EXIT
                    || s.weight != other.weight
                    || s.speed.to_bits() != other.speed.to_bits()
                    || s.maxspeed != other.maxspeed
                    || s.distance.to_bits() != other.distance.to_bits()
                {
                    return Err("invalid local-access twin".into());
                }
            }
        }
        self.validate_hierarchy()?;
        let mut previous = 0;
        for index in 0..=self.grid_count {
            let offset = self.u64(self.grid_offsets + index * 8);
            if offset < previous || offset > self.posting_count as u64 || index == 0 && offset != 0
            {
                return Err("invalid snap offsets".into());
            }
            if index < self.grid_count
                && (self.u32(self.grid + index * 4) >= 162_000_000
                    || index > 0
                        && self.u32(self.grid + (index - 1) * 4) >= self.u32(self.grid + index * 4))
            {
                return Err("invalid snap cells".into());
            }
            previous = offset;
        }
        if previous != self.posting_count as u64 {
            return Err("invalid snap length".into());
        }
        for index in 0..self.posting_count {
            if self.u32(self.postings + index * 4) as usize >= self.count {
                return Err("invalid snap road".into());
            }
        }
        Ok(())
    }
    fn validate_hierarchy(&self) -> Result<()> {
        // Validate CSR before any traversals; then topologically validate both upward graphs together.
        let mut indegree = vec![0u32; self.graph_count];
        for dir in 0..2 {
            let mut previous = 0;
            for node in 0..=self.graph_count {
                let offset = self.integer(
                    self.offsets[dir] + node * self.offset_widths[dir],
                    self.offset_widths[dir],
                );
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
                for index in self.outgoing(Direction::ALL[dir], VertexId::new(tail)) {
                    let arc = self.arc(Direction::ALL[dir], index);
                    if arc.head.get() as usize >= self.graph_count
                        || arc.head.get() == tail
                        || arc
                            .skipped
                            .is_some_and(|id| id.get() as usize >= self.graph_count)
                        || arc.weight > 1_000_000_000_000
                    {
                        return Err("invalid hierarchy arc".into());
                    }
                    indegree[arc.head.get() as usize] = indegree[arc.head.get() as usize]
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
                for i in self.outgoing(Direction::ALL[dir], VertexId::new(node)) {
                    let head = self.arc(Direction::ALL[dir], i).head.get() as usize;
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
                for index in self.outgoing(Direction::ALL[dir], VertexId::new(tail)) {
                    let a = self.arc(Direction::ALL[dir], index);
                    if a.skipped.is_none() {
                        let (from, to) = if dir == 0 {
                            (VertexId::new(tail), a.head)
                        } else {
                            (a.head, VertexId::new(tail))
                        };
                        if if self.node_based {
                            !self.from_node(NodeId::new(from.get())).any(|id| {
                                let road = self.state(id);
                                road.to.get() == to.get() && road.weight == a.weight
                            })
                        } else {
                            self.state(StateId::new(from.get())).to
                                != self.state(StateId::new(to.get())).from
                                || a.weight < self.state(StateId::new(to.get())).weight
                                || !local_transition_allowed(
                                    self.state(StateId::new(from.get())).flags,
                                    self.state(StateId::new(to.get())).flags,
                                )
                        } {
                            return Err("disconnected transition".into());
                        }
                    } else if let Some(middle) = a.skipped {
                        let left = self
                            .child(Direction::ALL[dir].opposite(), middle, VertexId::new(tail))
                            .ok_or("missing shortcut child")?;
                        let right = self
                            .child(Direction::ALL[dir], middle, a.head)
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
