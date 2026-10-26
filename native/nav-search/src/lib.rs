//! Tantivy candidate retrieval for offline routing packs.
//! Indexes are immutable; cancellation is checked while enumerating terms and postings.
use imu_nav_types::Coordinate;
pub use imu_nav_types::{SearchId, SearchKind};
use serde::{Deserialize, Serialize};
use std::cmp::Ordering;
use std::collections::{BinaryHeap, HashMap, HashSet};
use std::io::BufRead;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering as AtomicOrdering};
use tantivy::columnar::Column;
use tantivy::schema::{FAST, Field, IndexRecordOption, STORED, STRING, Schema, Value};
use tantivy::{DocAddress, DocSet, Index, IndexReader, ReloadPolicy, TERMINATED, TantivyDocument};

mod bridge;
type Result<T> = std::result::Result<T, Box<dyn std::error::Error + Send + Sync>>;
const FORMAT: &str = "imu-nav-tantivy-1\n";

/// Builder input preserves SQLite IDs, aliases and display text without another normalization pass.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Row {
    pub kind: SearchKind,
    pub id: SearchId,
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub place_kind: String,
    #[serde(default)]
    pub population: i64,
    #[serde(default)]
    pub place_id: Option<SearchId>,
    #[serde(default)]
    pub place_name: Option<String>,
    #[serde(default)]
    pub number: String,
    pub lat: f64,
    pub lon: f64,
}

impl Row {
    /// # Errors
    /// Rejects geographic bounds and missing address street references before indexing or use.
    pub fn validate(&self) -> Result<()> {
        Coordinate::new(self.lat, self.lon)?;
        if self.kind == SearchKind::Address && self.place_id.is_none() {
            return Err("address has no street".into());
        }
        Ok(())
    }
}

fn schema() -> Schema {
    let mut builder = Schema::builder();
    for name in ["place", "street", "address"] {
        builder.add_text_field(name, STRING);
    }
    for name in ["id", "place_id"] {
        builder.add_u64_field(name, FAST);
    }
    for name in ["lat", "lon"] {
        builder.add_f64_field(name, FAST);
    }
    builder.add_text_field("row", STORED);
    builder.build()
}

/// Match FTS4's simple tokenizer: ASCII punctuation separates; non-ASCII bytes remain token characters.
fn tokens(text: &str) -> impl Iterator<Item = &str> {
    text.split(|c: char| c.is_ascii() && !c.is_ascii_alphanumeric())
        .filter(|s| !s.is_empty())
}

/// Build a new disposable index, refusing to overwrite an existing index.
/// # Errors
/// Returns malformed input, index creation, or filesystem errors.
pub fn build(input: impl BufRead, directory: &Path) -> Result<()> {
    std::fs::create_dir(directory)?;
    let schema = schema();
    let index = Index::create_in_dir(directory, schema.clone())?;
    let mut writer = index.writer_with_num_threads(1, 50_000_000)?;
    for line in input.lines() {
        let row: Row = serde_json::from_str(&line?)?;
        row.validate()?;
        let mut doc = TantivyDocument::default();
        let field = schema.get_field(row.kind.as_str())?;
        match row.kind {
            SearchKind::Place | SearchKind::Street => {
                for token in tokens(&row.text) {
                    doc.add_text(field, token);
                }
            }
            SearchKind::Address => doc.add_text(
                field,
                format!(
                    "{}\t{}",
                    row.place_id.ok_or("address has no street")?,
                    row.number
                ),
            ),
        }
        doc.add_u64(schema.get_field("id")?, row.id.get());
        doc.add_u64(
            schema.get_field("place_id")?,
            row.place_id.map_or(0, SearchId::get),
        );
        doc.add_f64(schema.get_field("lat")?, row.lat);
        doc.add_f64(schema.get_field("lon")?, row.lon);
        doc.add_text(schema.get_field("row")?, serde_json::to_string(&row)?);
        writer.add_document(doc)?;
    }
    writer.commit()?;
    let segments = index.searchable_segment_ids()?;
    if segments.len() > 1 {
        writer.merge(&segments).wait()?;
    }
    writer.wait_merging_threads()?;
    std::fs::write(directory.join("search-format"), FORMAT)?;
    Ok(())
}

/// The JNI request contains the shared Kotlin plan, not a second natural-language parser.
#[derive(Deserialize)]
pub struct Request {
    pub words: Vec<String>,
    pub splits: Vec<(Vec<String>, Vec<String>)>,
    pub number: Option<String>,
    pub near: Option<(f64, f64)>,
    #[serde(default)]
    pub count: bool,
}
#[derive(Serialize)]
pub struct Lookup {
    pub words: Vec<String>,
    pub limit: usize,
    pub ids: Vec<SearchId>,
    pub rows: Vec<Row>,
    pub count: Option<u64>,
}
#[derive(Serialize)]
pub struct Snapshot {
    pub places: Vec<Lookup>,
    pub streets: Vec<Lookup>,
    pub addresses: Vec<Row>,
}

/// No writer or query-duration mutex: concurrent searches own a stable reader and cancellation flag.
pub struct SearchIndex {
    reader: IndexReader,
    schema: Schema,
}

fn check(cancel: &AtomicBool) -> Result<()> {
    if cancel.load(AtomicOrdering::Relaxed) {
        Err(Cancelled.into())
    } else {
        Ok(())
    }
}

#[derive(Debug)]
struct Candidate {
    distance: f64,
    id: u64,
    address: DocAddress,
}
impl PartialEq for Candidate {
    fn eq(&self, other: &Self) -> bool {
        self.cmp(other) == Ordering::Equal
    }
}
impl Eq for Candidate {}
impl PartialOrd for Candidate {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}
impl Ord for Candidate {
    fn cmp(&self, other: &Self) -> Ordering {
        self.distance
            .total_cmp(&other.distance)
            .then(self.id.cmp(&other.id))
            .then(self.address.cmp(&other.address))
    }
}

impl SearchIndex {
    /// Reject unknown formats and open only an immutable reader; existing SQLite packs are untouched.
    /// # Errors
    /// Returns incompatible format, damaged index, or filesystem errors.
    pub fn open(directory: &Path) -> Result<Self> {
        if std::fs::read_to_string(directory.join("search-format"))? != FORMAT {
            return Err("unsupported search format".into());
        }
        let index = Index::open_in_dir(directory)?;
        if index.schema() != schema() {
            return Err("unexpected search schema".into());
        }
        Ok(Self {
            reader: index
                .reader_builder()
                .reload_policy(ReloadPolicy::Manual)
                .try_into()?,
            schema: index.schema(),
        })
    }

    /// Enumerate prefix postings with cancellation inside the expensive loops, then intersect words.
    fn matches(
        segment: &tantivy::SegmentReader,
        field: Field,
        terms: &[(String, bool)],
        cancel: &AtomicBool,
    ) -> Result<Vec<u64>> {
        let inverted = segment.inverted_index(field)?;
        let len = (segment.max_doc() as usize).div_ceil(64);
        let mut intersection = vec![u64::MAX; len];
        for (word, prefix) in terms {
            check(cancel)?;
            let mut bits = vec![0u64; len];
            let mut found = false;
            let mut stream = inverted.terms().range().ge(word.as_bytes()).into_stream()?;
            while stream.advance() {
                check(cancel)?;
                if if *prefix {
                    !stream.key().starts_with(word.as_bytes())
                } else {
                    stream.key() != word.as_bytes()
                } {
                    break;
                }
                found = true;
                let mut postings = inverted
                    .read_postings_from_terminfo(stream.value(), IndexRecordOption::Basic)?;
                let mut steps = 0u32;
                while postings.doc() != TERMINATED {
                    let doc = postings.doc() as usize;
                    bits[doc / 64] |= 1u64 << (doc % 64);
                    postings.advance();
                    steps += 1;
                    if steps.is_multiple_of(256) {
                        check(cancel)?;
                    }
                }
            }
            if !found {
                return Ok(Vec::new());
            }
            let mut any = false;
            for (offset, (target, found)) in intersection.iter_mut().zip(bits).enumerate() {
                if offset.is_multiple_of(256) {
                    check(cancel)?;
                }
                *target &= found;
                any |= *target != 0;
            }
            if !any {
                return Ok(Vec::new());
            }
        }
        if terms.is_empty() {
            intersection.fill(0);
        }
        Ok(intersection)
    }

    /// Retrieve all stages in one JNI request; Kotlin applies the existing final scoring and deduplication.
    /// # Errors
    /// Returns cancellation, invalid plan, or index read errors.
    pub fn search(&self, request: &Request, cancel: &AtomicBool) -> Result<Snapshot> {
        check(cancel)?;
        if request.words.len() > 64
            || request.splits.len() > 70
            || request.words.iter().any(|s| s.len() > 1024)
        {
            return Err("search plan too large".into());
        }
        if request
            .near
            .is_some_and(|(lat, lon)| Coordinate::new(lat, lon).is_err())
        {
            return Err("invalid reference".into());
        }
        let searcher = self.reader.searcher();
        let mut context = SearchContext {
            columns: (0..searcher.segment_readers().len())
                .map(|_| None)
                .collect(),
            searcher,
            index: self,
            request,
            cancel,
        };
        let mut places = vec![context.lookup(SearchKind::Place, &request.words, 30, &[])?];
        let mut streets = vec![context.lookup(SearchKind::Street, &request.words, 80, &[])?];
        let mut place_keys = HashMap::new();
        let mut street_keys = HashSet::new();
        for (town, street) in &request.splits {
            let index = if let Some(index) = place_keys.get(town) {
                *index
            } else {
                let index = places.len();
                places.push(context.lookup(SearchKind::Place, town, 10, &[])?);
                place_keys.insert(town.clone(), index);
                index
            };
            let mut ids: Vec<_> = places[index]
                .rows
                .iter()
                .filter(|row| {
                    matches!(
                        row.place_kind.as_str(),
                        "city" | "town" | "village" | "hamlet"
                    )
                })
                .map(|row| row.id)
                .collect();
            ids.sort_unstable();
            if !ids.is_empty() && street_keys.insert((street.clone(), ids.clone())) {
                streets.push(context.lookup(SearchKind::Street, street, 40, &ids)?);
            }
        }
        let mut addresses = Vec::new();
        if let Some(number) = &request.number {
            let ids: HashSet<_> = streets
                .iter()
                .flat_map(|lookup| lookup.rows.iter().map(|row| row.id))
                .collect();
            addresses = context.addresses(number, &ids)?;
        }
        check(cancel)?;
        Ok(Snapshot {
            places,
            streets,
            addresses,
        })
    }
}

/// Fast-field opening decodes column metadata. Share those handles across stages of one request.
/// The handles are discarded with the request, so pack replacement needs no cache invalidation.
struct SegmentColumns {
    ids: Column<u64>,
    places: Column<u64>,
    latitudes: Column<f64>,
    longitudes: Column<f64>,
}

impl SegmentColumns {
    fn open(segment: &tantivy::SegmentReader) -> Result<Self> {
        let fields = segment.fast_fields();
        Ok(Self {
            ids: fields.u64("id")?,
            places: fields.u64("place_id")?,
            latitudes: fields.f64("lat")?,
            longitudes: fields.f64("lon")?,
        })
    }
}

/// One stable searcher and lazy column handles for all interpretations of the query.
struct SearchContext<'a> {
    index: &'a SearchIndex,
    request: &'a Request,
    cancel: &'a AtomicBool,
    searcher: tantivy::Searcher,
    columns: Vec<Option<SegmentColumns>>,
}

impl SearchContext<'_> {
    /// House keys are sparse: visit their postings directly instead of allocating and scanning
    /// a segment-sized bitset for every candidate street. Exact and space-suffix keys are disjoint.
    fn addresses(&self, number: &str, ids: &HashSet<SearchId>) -> Result<Vec<Row>> {
        let mut rows = Vec::new();
        let field = self.index.schema.get_field("address")?;
        for (ordinal, segment) in self.searcher.segment_readers().iter().enumerate() {
            let inverted = segment.inverted_index(field)?;
            for street_id in ids {
                for (term, prefix) in [
                    (format!("{street_id}\t{number}"), false),
                    (format!("{street_id}\t{number} "), true),
                ] {
                    check(self.cancel)?;
                    let mut stream = inverted.terms().range().ge(term.as_bytes()).into_stream()?;
                    while stream.advance() {
                        check(self.cancel)?;
                        if if prefix {
                            !stream.key().starts_with(term.as_bytes())
                        } else {
                            stream.key() != term.as_bytes()
                        } {
                            break;
                        }
                        let mut postings = inverted.read_postings_from_terminfo(
                            stream.value(),
                            IndexRecordOption::Basic,
                        )?;
                        let mut steps = 0u32;
                        while postings.doc() != TERMINATED {
                            let doc = postings.doc();
                            if !segment.is_deleted(doc) {
                                rows.push(self.row(DocAddress::new(u32::try_from(ordinal)?, doc))?);
                            }
                            postings.advance();
                            steps += 1;
                            if steps.is_multiple_of(256) {
                                check(self.cancel)?;
                            }
                        }
                    }
                }
            }
        }
        Ok(rows)
    }

    fn row(&self, address: DocAddress) -> Result<Row> {
        let document: TantivyDocument = self.searcher.doc(address)?;
        let row: Row = serde_json::from_str(
            document
                .get_first(self.index.schema.get_field("row")?)
                .and_then(|value| value.as_str())
                .ok_or("missing stored row")?,
        )?;
        row.validate()?;
        Ok(row)
    }

    fn lookup(
        &mut self,
        kind: SearchKind,
        words: &[String],
        limit: usize,
        ids: &[SearchId],
    ) -> Result<Lookup> {
        let request = self.request;
        let cancel = self.cancel;
        let terms: Vec<_> = words
            .iter()
            .flat_map(|word| {
                let clean = word.replace('\'', "");
                let parts: Vec<_> = tokens(&clean).map(str::to_owned).collect();
                let last = parts.len().saturating_sub(1);
                parts
                    .into_iter()
                    .enumerate()
                    .map(move |(index, part)| (part, index == last))
            })
            .collect();
        let searcher = &self.searcher;
        let mut heap = BinaryHeap::new();
        let mut count = 0;
        let longitude_weight = request.near.map(|p| p.0.to_radians().cos().powi(2));
        for (ordinal, segment) in searcher.segment_readers().iter().enumerate() {
            let bits = SearchIndex::matches(
                segment,
                self.index.schema.get_field(kind.as_str())?,
                &terms,
                cancel,
            )?;
            if bits.is_empty() {
                continue;
            }
            if self.columns[ordinal].is_none() {
                self.columns[ordinal] = Some(SegmentColumns::open(segment)?);
            }
            let columns = self.columns[ordinal]
                .as_ref()
                .ok_or("missing segment columns")?;
            for (block, mut mask) in bits.into_iter().enumerate() {
                check(cancel)?;
                while mask != 0 {
                    let doc = u32::try_from(block * 64 + mask.trailing_zeros() as usize)?;
                    mask &= mask - 1;
                    if doc >= segment.max_doc() || segment.is_deleted(doc) {
                        continue;
                    }
                    if !ids.is_empty()
                        && !ids
                            .iter()
                            .any(|id| id.get() == columns.places.first(doc).unwrap_or(0))
                    {
                        continue;
                    }
                    count += 1;
                    let distance = request.near.map_or(0.0, |(lat, lon)| {
                        let delta_lat = columns.latitudes.first(doc).unwrap_or(0.0) - lat;
                        let delta_lon = (columns.longitudes.first(doc).unwrap_or(0.0) - lon).abs();
                        delta_lat * delta_lat
                            + delta_lon.min(360.0 - delta_lon).powi(2)
                                * longitude_weight.unwrap_or(1.0)
                    });
                    let candidate = Candidate {
                        distance,
                        id: SearchId::new(columns.ids.first(doc).unwrap_or(0))?.get(),
                        address: DocAddress::new(u32::try_from(ordinal)?, doc),
                    };
                    if heap.len() == limit && heap.peek().is_some_and(|worst| candidate >= *worst) {
                        continue;
                    }
                    heap.push(candidate);
                    if heap.len() > limit {
                        heap.pop();
                    }
                }
            }
        }
        let mut rows = Vec::new();
        for candidate in heap.into_sorted_vec() {
            check(cancel)?;
            rows.push(self.row(candidate.address)?);
        }
        Ok(Lookup {
            words: words.to_vec(),
            limit,
            ids: ids.to_vec(),
            rows,
            count: request.count.then_some(count),
        })
    }
}

/// Search cancellation is a typed control-flow outcome at the JNI boundary.
#[derive(Clone, Copy, Debug)]
pub struct Cancelled;
impl std::fmt::Display for Cancelled {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("search cancelled")
    }
}
impl std::error::Error for Cancelled {}
