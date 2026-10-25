//! Offline SRTM HGT sampling. Missing or void data fails an elevation build rather than inventing heights.
// Coordinates, grid cells, speeds and file indices have explicit bounds in the importer/loader.
// Integer-to-float conversions here stay below the exact-integer range of f64.
#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss
)]
use crate::{Result, geo::Point};
use memmap2::{Mmap, MmapOptions};
use std::{collections::HashMap, path::PathBuf};
pub struct Terrain {
    directory: PathBuf,
    tiles: HashMap<(i32, i32), (Mmap, usize)>,
}
impl Terrain {
    pub fn new(directory: PathBuf) -> Self {
        Self {
            directory,
            tiles: HashMap::new(),
        }
    }
    pub fn height(&mut self, point: Point) -> Result<f64> {
        let lat = point.lat.floor() as i32;
        let lon = point.lon.floor() as i32;
        let key = (lat, lon);
        if !self.tiles.contains_key(&key) {
            let name = format!(
                "{}{:02}{}{:03}.hgt",
                if lat >= 0 { "N" } else { "S" },
                lat.abs(),
                if lon >= 0 { "E" } else { "W" },
                lon.abs()
            );
            let file = std::fs::File::open(self.directory.join(name))?;
            // Desktop DEM tiles are read-only input for the duration of a build.
            let map = unsafe { MmapOptions::new().map(&file)? };
            let side = ((map.len() / 2) as f64).sqrt() as usize;
            if !(2..=3601).contains(&side) || side * side * 2 != map.len() {
                return Err("invalid HGT tile".into());
            }
            self.tiles.insert(key, (map, side));
        }
        let (map, side) = &self.tiles[&key];
        let y = (f64::from(lat + 1) - point.lat) * (side - 1) as f64;
        let x = (point.lon - f64::from(lon)) * (side - 1) as f64;
        let row = (y.floor() as usize).min(side - 2);
        let col = (x.floor() as usize).min(side - 2);
        let mut value = 0.;
        for (dy, wy) in [(0, 1. - (y - row as f64)), (1, y - row as f64)] {
            for (dx, wx) in [(0, 1. - (x - col as f64)), (1, x - col as f64)] {
                let index = ((row + dy) * side + col + dx) * 2;
                let height = i16::from_be_bytes(map[index..index + 2].try_into().unwrap());
                if height == i16::MIN {
                    return Err("void HGT elevation sample".into());
                }
                value += f64::from(height) * wx * wy;
            }
        }
        Ok(value)
    }
}
