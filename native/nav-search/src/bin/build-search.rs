//! Offline search-index builder; input is exported from relational staging data.
fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let args: Vec<_> = std::env::args_os().collect();
    if args.len() != 3 {
        return Err("usage: build-search INPUT.jsonl NEW_OUTPUT_DIRECTORY".into());
    }
    imu_nav_search::build(
        std::io::BufReader::new(std::fs::File::open(&args[1])?),
        std::path::Path::new(&args[2]),
    )
}
