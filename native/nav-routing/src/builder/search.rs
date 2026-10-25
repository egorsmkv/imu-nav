//! Build the existing Tantivy schema directly from OSM, without SQLite or a Java graph.
// Coordinates, grid cells, speeds and file indices have explicit bounds in the importer/loader.
// Integer-to-float conversions here stay below the exact-integer range of f64.
#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss
)]
use super::{Road, osm::Tags};
use crate::{
    Result,
    geo::{self, Point},
};
use serde_json::{Value, json};
use std::{
    collections::{HashMap, HashSet},
    io::Write,
    path::Path,
};
#[derive(Default)]
pub struct Search {
    places: Vec<(Point, f64, Value)>,
    place_grid: HashMap<(i32, i32), Vec<usize>>,
    addresses: Vec<(Point, String, String)>,
    streets: HashMap<(String, Option<usize>), Vec<Point>>,
    seen: HashSet<(i64, i64, i64)>,
}
fn normalize(text: &str) -> String {
    let mut out = String::new();
    let mut space = false;
    for c in text.to_lowercase().chars() {
        let c = match c {
            'ё' => 'е',
            '’' | 'ʼ' | '`' => '\'',
            c => c,
        };
        if c.is_alphanumeric() || c == '\'' || c == '/' {
            out.push(c);
            space = false;
        } else if !space {
            out.push(' ');
            space = true;
        }
    }
    out.trim().to_owned()
}
fn grid(point: Point) -> (i32, i32) {
    (
        (point.lat / 0.05).floor() as i32,
        (point.lon / 0.05).floor() as i32,
    )
}
impl Search {
    pub fn node(&mut self, point: Point, tags: &Tags, addresses: bool) {
        if let (Some(kind), Some(name)) = (tags.get("place"), tags.get("name"))
            && matches!(
                kind.as_str(),
                "city" | "town" | "village" | "hamlet" | "suburb" | "quarter" | "neighbourhood"
            )
        {
            let reach = match kind.as_str() {
                "city" => 9000.,
                "town" => 3500.,
                "village" => 1500.,
                "hamlet" => 700.,
                _ => 0.,
            };
            let id = self.places.len();
            let aliases = [
                "name", "name:uk", "name:en", "name:ru", "old_name", "alt_name",
            ]
            .iter()
            .filter_map(|k| tags.get(*k))
            .map(String::as_str)
            .collect::<Vec<_>>()
            .join(" ");
            let population = tags
                .get("population")
                .and_then(|v| {
                    v.chars()
                        .filter(char::is_ascii_digit)
                        .take(9)
                        .collect::<String>()
                        .parse::<u64>()
                        .ok()
                })
                .unwrap_or(0);
            self.places.push((
                point,
                reach,
                json!({
                    "kind": "place",
                    "id": id + 1,
                    "name": name,
                    "text": normalize(&aliases).replace('\'', ""),
                    "place_kind": kind,
                    "population": population,
                    "lat": point.lat,
                    "lon": point.lon,
                }),
            ));
            if reach > 0. {
                self.place_grid.entry(grid(point)).or_default().push(id);
            }
        }
        if addresses
            && let (Some(number), Some(street)) =
                (tags.get("addr:housenumber"), tags.get("addr:street"))
        {
            self.addresses
                .push((point, normalize(street), normalize(number)));
        }
    }
    pub fn roads(&mut self, roads: &[Road]) {
        for road in roads {
            if road.name.is_empty()
                || !self
                    .seen
                    .insert((road.way, road.from.min(road.to), road.from.max(road.to)))
            {
                continue;
            }
            let point = road.geometry[road.geometry.len() / 2];
            let (cy, cx) = grid(point);
            let mut best = None;
            let mut score = 2.5;
            for dy in -3..=3 {
                for dx in -3..=3 {
                    for &id in self
                        .place_grid
                        .get(&(cy + dy, cx + dx))
                        .into_iter()
                        .flatten()
                    {
                        let (place, reach, _) = &self.places[id];
                        let value = geo::distance(point, *place) / reach;
                        if value < score {
                            score = value;
                            best = Some(id);
                        }
                    }
                }
            }
            self.streets
                .entry((road.name.clone(), best))
                .or_default()
                .push(point);
        }
    }
    pub fn build(self, path: &Path) -> Result<()> {
        let jsonl = path.with_extension("jsonl");
        let mut writer = std::io::BufWriter::new(std::fs::File::create(&jsonl)?);
        for (_, _, row) in &self.places {
            serde_json::to_writer(&mut writer, row)?;
            writeln!(writer)?;
        }
        let mut groups: Vec<_> = self.streets.into_iter().collect();
        groups.sort_by(|a, b| a.0.cmp(&b.0));
        let mut by_name: HashMap<String, Vec<(u64, Point)>> = HashMap::new();
        for (index, ((name, place), points)) in groups.into_iter().enumerate() {
            let center = Point {
                lat: points.iter().map(|p| p.lat).sum::<f64>() / points.len() as f64,
                lon: points.iter().map(|p| p.lon).sum::<f64>() / points.len() as f64,
                elevation: 0.,
            };
            let point = *points
                .iter()
                .min_by(|a, b| geo::distance(**a, center).total_cmp(&geo::distance(**b, center)))
                .unwrap();
            let id = index as u64 + 1;
            let row = json!({
                "kind": "street",
                "id": id,
                "name": name,
                "text": normalize(&name).replace('\'', ""),
                "place_id": place.map(|id| id + 1),
                "place_name": place.map(|id| &self.places[id].2["name"]),
                "lat": point.lat,
                "lon": point.lon,
            });
            serde_json::to_writer(&mut writer, &row)?;
            writeln!(writer)?;
            by_name
                .entry(normalize(&name))
                .or_default()
                .push((id, point));
        }
        for (index, (point, street, number)) in self.addresses.iter().enumerate() {
            if let Some((id, position)) = by_name.get(street).and_then(|rows| {
                rows.iter().min_by(|a, b| {
                    geo::distance(a.1, *point).total_cmp(&geo::distance(b.1, *point))
                })
            }) && geo::distance(*position, *point) <= 8000.
            {
                serde_json::to_writer(
                    &mut writer,
                    &json!({"kind":"address","id":index+1,"place_id":id,"number":number,"lat":point.lat,"lon":point.lon}),
                )?;
                writeln!(writer)?;
            }
        }
        writer.flush()?;
        drop(writer);
        imu_nav_search::build(std::io::BufReader::new(std::fs::File::open(&jsonl)?), path)?;
        std::fs::remove_file(jsonl)?;
        Ok(())
    }
}
