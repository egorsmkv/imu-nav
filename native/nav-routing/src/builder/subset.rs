//! Derive a single-mode pack without reimporting OSM, recontracting the graph, or changing search.
use super::compact::Dictionary;
use crate::{
    Result,
    shared::{REVERSED, SCALE},
    storage::{Network, State},
};
use std::{
    collections::HashMap,
    fs::File,
    io::{BufWriter, Read, Seek, SeekFrom, Write},
    path::Path,
};

/// Copy a declared profile, trimming shared data to referenced geometry and names.
/// # Errors
/// Rejects incompatible input, unknown modes, existing output and invalid routing data.
pub fn create(source: &Path, output: &Path, profile: &str) -> Result<serde_json::Value> {
    if !matches!(profile, "car" | "foot") {
        return Err("profile must be car or foot".into());
    }
    let parent = output
        .parent()
        .filter(|path| !path.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."));
    if parent.canonicalize()?.starts_with(source.canonicalize()?) {
        return Err("subset must be outside the source pack".into());
    }
    let metadata = File::open(source.join("pack.json"))?;
    if metadata.metadata()?.len() > 65_536 {
        return Err("routing metadata too large".into());
    }
    let mut info: serde_json::Value = serde_json::from_reader(metadata)?;
    if info["routingFormat"] != crate::FORMAT
        || !info["profiles"]
            .as_array()
            .is_some_and(|profiles| profiles.iter().any(|value| value == profile))
    {
        return Err("source does not contain the requested current-format profile".into());
    }
    let input_path = source.join(format!("{profile}.routing"));
    let network = Network::open(&input_path)?;
    let elevation = info["elevation"]
        .as_bool()
        .ok_or("missing elevation flag")?;
    if elevation != network.has_elevation() {
        return Err("elevation metadata mismatch".into());
    }
    std::fs::create_dir(output)?;
    let mut dictionary = Dictionary::new(output, elevation)?;
    rewrite(
        &input_path,
        &output.join(format!("{profile}.routing")),
        &network,
        &mut dictionary,
    )?;
    dictionary.finish(output)?;
    copy_search(source, output)?;
    info["profiles"] = serde_json::json!([profile]);
    info["sizeBytes"] = serde_json::json!(super::directory_size(output)?);
    std::fs::write(output.join("pack.json"), serde_json::to_vec_pretty(&info)?)?;
    Ok(info)
}
fn rewrite(
    input: &Path,
    output: &Path,
    network: &Network,
    dictionary: &mut Dictionary,
) -> Result<()> {
    let mut source = File::open(input)?;
    let mut destination = BufWriter::new(File::create_new(output)?);
    let mut header = [0; 72];
    source.read_exact(&mut header)?;
    destination.write_all(&header)?;
    let mut geometry_ids = HashMap::new();
    let mut name_ids = HashMap::new();
    for id in 0..network.count {
        let state = network.state(crate::ids::StateId::new(u32::try_from(id)?));
        let geometry = if let Some(&id) = geometry_ids.get(&state.geometry) {
            id
        } else {
            let id = geometry(network, state, dictionary)?;
            geometry_ids.insert(state.geometry, id);
            id
        };
        let name = if let Some(&id) = name_ids.get(&state.name_id) {
            id
        } else {
            let id = dictionary.name(&network.name(state))?;
            name_ids.insert(state.name_id, id);
            id
        };
        for value in [
            state.from.get(),
            state.to.get(),
            geometry,
            name,
            state.speed.to_bits(),
        ] {
            destination.write_all(&value.to_le_bytes())?;
        }
        destination.write_all(&state.maxspeed.to_le_bytes())?;
        destination.write_all(&state.flags.to_le_bytes())?;
        destination.write_all(&state.weight.to_le_bytes())?;
        destination.write_all(&state.distance.to_le_bytes())?;
    }
    // CSR, shortcut costs, topology IDs and snapping postings remain byte-for-byte identical.
    source.seek(SeekFrom::Start(72 + u64::try_from(network.count)? * 40))?;
    std::io::copy(&mut source, &mut destination)?;
    destination.flush()?;
    Ok(())
}
fn geometry(network: &Network, state: State, dictionary: &mut Dictionary) -> Result<u32> {
    let mut encoded = Vec::with_capacity(state.point_count as usize * dictionary.width());
    for index in 0..state.point_count {
        let point = network.point(state, index);
        for coordinate in [point.lat, point.lon] {
            // The validated E7 reader returns values bounded to +/- 180 degrees; this recovers the exact integer.
            #[allow(clippy::cast_possible_truncation)]
            let fixed = (coordinate * SCALE).round() as i32;
            encoded.extend_from_slice(&fixed.to_le_bytes());
        }
        if dictionary.width() == 16 {
            encoded.extend_from_slice(&point.elevation.to_le_bytes());
        }
    }
    let (id, reversed) = dictionary.geometry(encoded)?;
    if reversed != (state.flags & REVERSED != 0) {
        return Err("geometry orientation changed during subset creation".into());
    }
    Ok(id)
}
fn copy_search(source: &Path, output: &Path) -> Result<()> {
    let search = source.join("search.tantivy");
    if search.is_dir() {
        std::fs::create_dir(output.join("search.tantivy"))?;
        for entry in std::fs::read_dir(search)? {
            let entry = entry?;
            if !entry.file_type()?.is_file() {
                return Err("search index must contain regular files".into());
            }
            std::fs::copy(
                entry.path(),
                output.join("search.tantivy").join(entry.file_name()),
            )?;
        }
    } else if source.join("search.db").is_file() {
        std::fs::copy(source.join("search.db"), output.join("search.db"))?;
    }
    Ok(())
}
