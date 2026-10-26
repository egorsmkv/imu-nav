//! Small immutable fixtures test storage semantics independently of the Kotlin parity suite.
use imu_nav_search::{Request, Row, SearchIndex, build};
use std::sync::atomic::{AtomicBool, Ordering};

#[test]
fn checked_row_and_query_boundaries_reject_invalid_geography_and_references() {
    let directory = tempfile::tempdir().unwrap();
    for (index, (latitude, longitude, reference)) in [
        (91.0, 30.0, Some(1)),
        (50.0, 181.0, Some(1)),
        (50.0, 30.0, Some(0)),
        (50.0, 30.0, None),
    ]
    .into_iter()
    .enumerate()
    {
        let input = serde_json::json!({
            "kind": "address",
            "id": 1,
            "lat": latitude,
            "lon": longitude,
            "place_id": reference,
        });
        let output = directory.path().join(format!("invalid-{index}"));
        assert!(build(std::io::Cursor::new(input.to_string()), &output).is_err());
        assert!(!output.join("search-format").exists());
    }
    let output = directory.path().join("valid");
    let input = serde_json::to_string(&row("street", 1, "main", 30.0)).unwrap();
    build(std::io::Cursor::new(input), &output).unwrap();
    let index = SearchIndex::open(&output).unwrap();
    let cancel = AtomicBool::new(false);
    for near in [(91.0, 30.0), (50.0, -181.0)] {
        let mut query = request(&["main"]);
        query.near = Some(near);
        assert!(index.search(&query, &cancel).is_err());
    }
    let mut query = request(&["main"]);
    query.near = Some((-90.0, 180.0));
    assert!(index.search(&query, &cancel).is_ok());
}

fn row(kind: &str, id: u64, text: &str, lon: f64) -> Row {
    Row {
        kind: kind.parse().unwrap(),
        id: imu_nav_search::SearchId::new(id).unwrap(),
        text: text.into(),
        name: text.into(),
        place_kind: "town".into(),
        population: 1000,
        place_id: Some(imu_nav_search::SearchId::new(1).unwrap()),
        place_name: Some("New Town".into()),
        number: String::new(),
        lat: 0.0,
        lon,
    }
}
fn request(words: &[&str]) -> Request {
    Request {
        words: words.iter().map(|word| (*word).into()).collect(),
        splits: vec![],
        number: None,
        near: None,
        count: false,
    }
}
fn fixture(rows: &[Row]) -> (tempfile::TempDir, SearchIndex) {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("index");
    let data = rows
        .iter()
        .map(|row| serde_json::to_string(row).unwrap())
        .collect::<Vec<_>>()
        .join("\n");
    build(data.as_bytes(), &path).unwrap();
    let index = SearchIndex::open(&path).unwrap();
    (directory, index)
}

#[test]
fn prefixes_conjoin_and_wrap_before_limit() {
    let mut rows: Vec<_> = (1..=100)
        .map(|id| row("street", id, "main головна", 20.0))
        .collect();
    rows.push(row("street", 101, "main головна", -179.99));
    rows.push(row("street", 102, "other", 179.99));
    let (_directory, index) = fixture(&rows);
    let mut query = request(&["ma", "го"]);
    query.near = Some((0.0, 179.99));
    query.count = true;
    let response = index.search(&query, &AtomicBool::new(false)).unwrap();
    assert_eq!(response.streets[0].rows.len(), 80);
    assert_eq!(response.streets[0].rows[0].id.get(), 101);
    assert_eq!(response.streets[0].count, Some(101));
    query.near = None;
    query.count = false;
    let response = index.search(&query, &AtomicBool::new(false)).unwrap();
    assert_eq!(response.streets[0].rows[0].id.get(), 1);
    assert_eq!(response.streets[0].count, None);
    assert!(
        index
            .search(&request(&["absent"]), &AtomicBool::new(false))
            .unwrap()
            .streets[0]
            .rows
            .is_empty()
    );
}

#[test]
fn house_boundaries_and_read_only_reopen() {
    let mut rows = vec![row("street", 1, "main", 0.0)];
    for (id, number) in [(1, "22"), (2, "22 к1"), (3, "220")] {
        let mut address = row("address", id, "", 0.0);
        address.number = number.into();
        rows.push(address);
    }
    let (directory, index) = fixture(&rows);
    let mut query = request(&["ma"]);
    query.number = Some("22".into());
    let mut numbers: Vec<_> = index
        .search(&query, &AtomicBool::new(false))
        .unwrap()
        .addresses
        .into_iter()
        .map(|row| row.number)
        .collect();
    numbers.sort();
    assert_eq!(numbers, ["22", "22 к1"]);
    // Holding a writer lock does not prevent read-only opens.
    let underlying = tantivy::Index::open_in_dir(directory.path().join("index")).unwrap();
    let _writer = underlying
        .writer::<tantivy::TantivyDocument>(50_000_000)
        .unwrap();
    assert!(SearchIndex::open(&directory.path().join("index")).is_ok());
    assert!(build("".as_bytes(), &directory.path().join("index")).is_err());
    std::fs::write(
        directory.path().join("index/search-format"),
        "future-format",
    )
    .unwrap();
    assert!(SearchIndex::open(&directory.path().join("index")).is_err());
}

#[test]
fn cancellation_is_request_local() {
    let (_directory, index) = fixture(&[row("street", 1, "main", 0.0)]);
    let cancelled = AtomicBool::new(true);
    assert!(index.search(&request(&["ma"]), &cancelled).is_err());
    assert_eq!(
        index
            .search(&request(&["ma"]), &AtomicBool::new(false))
            .unwrap()
            .streets[0]
            .rows
            .len(),
        1
    );
    cancelled.store(false, Ordering::Relaxed);
    assert!(index.search(&request(&["ma"]), &cancelled).is_ok());
}

#[test]
fn cancellation_interrupts_broad_posting_work() {
    let rows: Vec<_> = (1..=30_000)
        .map(|id| row("street", id, "main", 0.0))
        .collect();
    let (_directory, index) = fixture(&rows);
    let mut query = request(&["ma"]);
    // Repeated conjunctions deliberately keep the posting loop busy without a timing assertion.
    query.words = vec!["ma".into(); 64];
    let cancelled = AtomicBool::new(false);
    std::thread::scope(|scope| {
        let worker = scope.spawn(|| index.search(&query, &cancelled));
        std::thread::sleep(std::time::Duration::from_millis(1));
        cancelled.store(true, Ordering::Relaxed);
        assert!(worker.join().unwrap().is_err());
    });
    assert!(
        index
            .search(&request(&["ma"]), &AtomicBool::new(false))
            .is_ok()
    );
}

#[test]
fn staged_lookups_keep_segment_columns_and_deletions_separate() {
    let mut address = row("address", 1, "", 20.0);
    address.number = "22".into();
    let rows = [
        row("place", 1, "new town", 20.0),
        row("street", 1, "main", 20.0),
        row("street", 2, "main obsolete", 20.0),
        address,
    ];
    let (directory, _original_reader) = fixture(&rows);
    let underlying = tantivy::Index::open_in_dir(directory.path().join("index")).unwrap();
    let schema = underlying.schema();
    let mut writer = underlying
        .writer_with_num_threads::<tantivy::TantivyDocument>(1, 50_000_000)
        .unwrap();
    writer.set_merge_policy(Box::new(tantivy::merge_policy::NoMergePolicy));
    writer.delete_term(tantivy::Term::from_field_text(
        schema.get_field("street").unwrap(),
        "obsolete",
    ));
    // A second segment restarts document IDs but has different row IDs and coordinates.
    for (kind, text) in [("place", "new town"), ("street", "main"), ("address", "")] {
        let mut value = row(kind, 10, text, -179.99);
        value.place_id = Some(imu_nav_search::SearchId::new(10).unwrap());
        value.number = "22".into();
        let mut document = tantivy::TantivyDocument::default();
        let field = schema.get_field(kind).unwrap();
        if kind == "address" {
            document.add_text(field, "10\t22");
        } else {
            for token in text.split_whitespace() {
                document.add_text(field, token);
            }
        }
        document.add_u64(schema.get_field("id").unwrap(), 10);
        document.add_u64(schema.get_field("place_id").unwrap(), 10);
        document.add_f64(schema.get_field("lat").unwrap(), 0.0);
        document.add_f64(schema.get_field("lon").unwrap(), -179.99);
        document.add_text(
            schema.get_field("row").unwrap(),
            serde_json::to_string(&value).unwrap(),
        );
        writer.add_document(document).unwrap();
    }
    writer.commit().unwrap();
    writer.wait_merging_threads().unwrap();
    assert_eq!(underlying.searchable_segment_ids().unwrap().len(), 2);
    let index = SearchIndex::open(&directory.path().join("index")).unwrap();
    let mut query = request(&["new", "town", "ma"]);
    query.splits = vec![(vec!["new".into(), "town".into()], vec!["ma".into()])];
    query.near = Some((0.0, 179.99));
    query.number = Some("22".into());
    query.count = true;
    let cancel = AtomicBool::new(false);
    let response = index.search(&query, &cancel).unwrap();
    assert!(response.streets[0].rows.is_empty());
    assert_eq!(response.streets[1].count, Some(2));
    assert_eq!(response.streets[1].rows[0].id.get(), 10);
    assert_eq!(response.streets[1].rows[1].id.get(), 1);
    assert_eq!(response.addresses.len(), 2);
    let empty = index
        .search(&request(&["main", "absent"]), &cancel)
        .unwrap();
    assert!(empty.streets[0].rows.is_empty());
    query.near = None;
    assert_eq!(
        index.search(&query, &cancel).unwrap().streets[1].rows[0]
            .id
            .get(),
        1
    );
}
