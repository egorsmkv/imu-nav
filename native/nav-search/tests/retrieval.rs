//! Small immutable fixtures test storage semantics independently of the Kotlin parity suite.
use imu_nav_search::{Request, Row, SearchIndex, build};
use std::sync::atomic::{AtomicBool, Ordering};

fn row(kind: &str, id: u64, text: &str, lon: f64) -> Row {
    Row {
        kind: kind.into(),
        id,
        text: text.into(),
        name: text.into(),
        place_kind: "town".into(),
        population: 1000,
        place_id: Some(1),
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
    assert_eq!(response.streets[0].rows[0].id, 101);
    assert_eq!(response.streets[0].count, Some(101));
    query.near = None;
    query.count = false;
    let response = index.search(&query, &AtomicBool::new(false)).unwrap();
    assert_eq!(response.streets[0].rows[0].id, 1);
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
