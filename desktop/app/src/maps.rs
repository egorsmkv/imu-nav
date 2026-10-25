//! Offline tile queries and image decoding run on one bounded worker; no network fallback exists.

use egui::{ColorImage, Context, Rect, TextureHandle};
use image::ImageReader;
use rusqlite::{Connection, OpenFlags, OptionalExtension};
use std::collections::{HashMap, HashSet, VecDeque};
use std::io::Cursor;
use std::path::PathBuf;
use std::sync::mpsc::{Receiver, SyncSender, sync_channel};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use walkers::sources::Attribution;
use walkers::{Tile, TileId, TilePiece, Tiles};

const MAX_TILE_BYTES: i64 = 4 * 1024 * 1024;
const CACHE_TILES: usize = 128;
const QUEUED_TILES: usize = 32;

pub fn valid_template(template: &str) -> bool {
    template.starts_with("https://")
        && ["{z}", "{x}", "{y}"]
            .iter()
            .all(|part| template.contains(part))
        && !template.contains('@')
        && template.len() <= 2048
}

#[derive(Clone)]
pub struct MapInfo {
    pub name: String,
    pub attribution: String,
    pub bounds: Option<[f64; 4]>,
    pub min_zoom: u8,
    pub max_zoom: u8,
}

enum ResultMessage {
    Open(Result<MapInfo, ()>),
    Tile(TileId, Option<TextureHandle>),
}

/// Each selected file owns a worker and a small cache. Dropping the sender terminates the worker.
pub struct MbTiles {
    requests: SyncSender<TileId>,
    results: Receiver<ResultMessage>,
    cache: HashMap<TileId, Option<TextureHandle>>,
    order: VecDeque<TileId>,
    pending: HashSet<TileId>,
    pub info: Option<MapInfo>,
    pub failed: bool,
    cancel: Arc<AtomicBool>,
}

impl MbTiles {
    /// Online mode uses the same bounded tile queue; cancellation prevents queued network requests.
    pub fn online(template: String, context: Context) -> Self {
        let (requests, incoming) = sync_channel::<TileId>(QUEUED_TILES);
        let (outgoing, results) = sync_channel(QUEUED_TILES);
        let cancel = Arc::new(AtomicBool::new(false));
        let stopped = cancel.clone();
        std::thread::spawn(move || {
            let Ok(fetcher) = crate::online::Fetcher::new(template) else {
                _ = outgoing.send(ResultMessage::Open(Err(())));
                context.request_repaint();
                return;
            };
            while let Ok(tile) = incoming.recv() {
                if stopped.load(Ordering::Relaxed) {
                    break;
                }
                let image = fetcher.fetch(tile).ok().flatten();
                let texture = image.map(|image| {
                    context.load_texture("online tile", image, egui::TextureOptions::LINEAR)
                });
                if outgoing.send(ResultMessage::Tile(tile, texture)).is_err() {
                    break;
                }
                context.request_repaint();
            }
        });
        Self {
            requests,
            results,
            cache: HashMap::new(),
            order: VecDeque::new(),
            pending: HashSet::new(),
            info: Some(MapInfo {
                name: String::new(),
                attribution: String::new(),
                bounds: None,
                min_zoom: 0,
                max_zoom: 19,
            }),
            failed: false,
            cancel,
        }
    }

    pub fn open(path: PathBuf, context: Context) -> Self {
        let (requests, incoming) = sync_channel::<TileId>(QUEUED_TILES);
        let (outgoing, results) = sync_channel(QUEUED_TILES);
        let cancel = Arc::new(AtomicBool::new(false));
        let stopped = cancel.clone();
        std::thread::spawn(move || {
            let opened = open_database(&path);
            let Ok((database, info)) = opened else {
                _ = outgoing.send(ResultMessage::Open(Err(())));
                context.request_repaint();
                return;
            };
            if outgoing
                .send(ResultMessage::Open(Ok(info.clone())))
                .is_err()
            {
                return;
            }
            context.request_repaint();
            while let Ok(tile) = incoming.recv() {
                if stopped.load(Ordering::Relaxed) {
                    break;
                }
                let image = read_tile(&database, tile, &info).ok().flatten();
                let texture = image.map(|image| {
                    context.load_texture("offline tile", image, egui::TextureOptions::LINEAR)
                });
                if outgoing.send(ResultMessage::Tile(tile, texture)).is_err() {
                    break;
                }
                context.request_repaint();
            }
        });
        Self {
            requests,
            results,
            cache: HashMap::new(),
            order: VecDeque::new(),
            pending: HashSet::new(),
            info: None,
            failed: false,
            cancel,
        }
    }

    pub fn poll(&mut self) {
        while let Ok(message) = self.results.try_recv() {
            match message {
                ResultMessage::Open(Ok(info)) => self.info = Some(info),
                ResultMessage::Open(Err(())) => self.failed = true,
                ResultMessage::Tile(tile, texture) => {
                    self.pending.remove(&tile);
                    self.cache.insert(tile, texture);
                    self.order.push_back(tile);
                    while self.cache.len() > CACHE_TILES {
                        if let Some(oldest) = self.order.pop_front() {
                            self.cache.remove(&oldest);
                        }
                    }
                }
            }
        }
    }
}

impl Drop for MbTiles {
    fn drop(&mut self) {
        self.cancel.store(true, Ordering::Relaxed);
    }
}

impl Tiles for MbTiles {
    fn at(&mut self, tile: TileId) -> Option<TilePiece> {
        self.poll();
        if let Some(texture) = self.cache.get(&tile) {
            return texture.as_ref().map(|texture| {
                TilePiece::new(
                    Tile::Raster(texture.clone()),
                    Rect::from_min_max(egui::Pos2::ZERO, egui::pos2(1.0, 1.0)),
                )
            });
        }
        let info = self.info.as_ref()?;
        if self.failed || tile.zoom < info.min_zoom || tile.zoom > info.max_zoom {
            return None;
        }
        if !self.pending.contains(&tile) && self.requests.try_send(tile).is_ok() {
            self.pending.insert(tile);
        }
        None
    }
    fn attribution(&self) -> Attribution {
        Attribution {
            text: "Offline map",
            url: "",
            logo_light: None,
            logo_dark: None,
        }
    }
    fn tile_size(&self) -> u32 {
        256
    }
}

fn open_database(path: &std::path::Path) -> Result<(Connection, MapInfo), ()> {
    let database = Connection::open_with_flags(
        path,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )
    .map_err(|_| ())?;
    database
        .busy_timeout(std::time::Duration::from_millis(100))
        .map_err(|_| ())?;
    database
        .execute_batch("PRAGMA trusted_schema=OFF; PRAGMA query_only=ON;")
        .map_err(|_| ())?;
    let metadata = |name: &str| -> Result<Option<String>, ()> {
        database
            .query_row(
                "SELECT value FROM metadata WHERE name=?1 AND length(value)<=4096 LIMIT 1",
                [name],
                |row| row.get(0),
            )
            .optional()
            .map_err(|_| ())
    };
    if !matches!(metadata("format")?.as_deref(), Some("png" | "jpg" | "jpeg")) {
        return Err(());
    }
    let min_zoom = metadata("minzoom")?
        .unwrap_or_else(|| "0".into())
        .parse::<u8>()
        .map_err(|_| ())?;
    let max_zoom = metadata("maxzoom")?
        .unwrap_or_else(|| "19".into())
        .parse::<u8>()
        .map_err(|_| ())?;
    if min_zoom > max_zoom || max_zoom > 22 {
        return Err(());
    }
    database.prepare("SELECT tile_data FROM tiles WHERE zoom_level=?1 AND tile_column=?2 AND tile_row=?3 AND length(tile_data)<=?4 LIMIT 1").map_err(|_| ())?;
    let bounds = metadata("bounds")?
        .map(|text| {
            let values: Vec<f64> = text
                .split(',')
                .map(|part| part.trim().parse().map_err(|_| ()))
                .collect::<Result<_, _>>()?;
            let bounds: [f64; 4] = values.try_into().map_err(|_| ())?;
            if !imu_nav_protocol::point_valid(&[bounds[1], bounds[0]])
                || !imu_nav_protocol::point_valid(&[bounds[3], bounds[2]])
                || bounds[0] > bounds[2]
                || bounds[1] > bounds[3]
            {
                return Err(());
            }
            Ok(bounds)
        })
        .transpose()?;
    let info = MapInfo {
        name: metadata("name")?.unwrap_or_else(|| "MBTiles".into()),
        attribution: metadata("attribution")?.unwrap_or_default(),
        bounds,
        min_zoom,
        max_zoom,
    };
    Ok((database, info))
}

fn read_tile(
    database: &Connection,
    tile: TileId,
    info: &MapInfo,
) -> Result<Option<ColorImage>, ()> {
    if tile.zoom < info.min_zoom || tile.zoom > info.max_zoom {
        return Ok(None);
    }
    let row = tms_row(tile.zoom, tile.y).ok_or(())?;
    // Decode while SQLite still owns the row: no temporary copy of the compressed BLOB.
    let image = database
        .query_row(
            "SELECT tile_data FROM tiles WHERE zoom_level=?1 AND tile_column=?2 AND tile_row=?3 AND length(tile_data)<=?4 LIMIT 1",
            rusqlite::params![tile.zoom, tile.x, row, MAX_TILE_BYTES],
            |row| {
                let data = row.get_ref(0)?;
                Ok(data.as_blob().map_err(|_| ()).and_then(decode_raster))
            },
        )
        .optional()
        .map_err(|_| ())?;
    image.transpose()
}

pub fn decode_raster(data: &[u8]) -> Result<ColorImage, ()> {
    let mut reader = ImageReader::new(Cursor::new(data))
        .with_guessed_format()
        .map_err(|_| ())?;
    if !matches!(
        reader.format(),
        Some(image::ImageFormat::Png | image::ImageFormat::Jpeg)
    ) {
        return Err(());
    }
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(512);
    limits.max_image_height = Some(512);
    limits.max_alloc = Some(8 * 1024 * 1024);
    reader.limits(limits);
    let image = reader.decode().map_err(|_| ())?.into_rgba8();
    if image.width() != image.height() || !matches!(image.width(), 256 | 512) {
        return Err(());
    }
    let size = [
        usize::try_from(image.width()).map_err(|_| ())?,
        usize::try_from(image.height()).map_err(|_| ())?,
    ];
    Ok(ColorImage::from_rgba_unmultiplied(size, image.as_raw()))
}

fn tms_row(zoom: u8, y: u32) -> Option<u32> {
    (1u32.checked_shl(u32::from(zoom))?)
        .checked_sub(1)?
        .checked_sub(y)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tms_rows_are_inverted_and_invalid_rows_rejected() {
        assert_eq!(tms_row(3, 0), Some(7));
        assert_eq!(tms_row(3, 7), Some(0));
        assert_eq!(tms_row(3, 8), None);
        assert_eq!(tms_row(40, 0), None);
    }

    #[test]
    fn import_is_read_only_and_missing_tiles_are_not_network_requests() {
        let file = tempfile::NamedTempFile::new().unwrap();
        let database = Connection::open(file.path()).unwrap();
        database.execute_batch("CREATE TABLE metadata(name TEXT,value TEXT); INSERT INTO metadata VALUES ('format','png'),('bounds','-10,-5,10,5'); CREATE TABLE tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB);").unwrap();
        drop(database);
        let (database, info) = open_database(file.path()).unwrap();
        assert_eq!(info.bounds, Some([-10.0, -5.0, 10.0, 5.0]));
        assert!(database.execute("DELETE FROM metadata", []).is_err());
        assert!(
            read_tile(
                &database,
                TileId {
                    zoom: 1,
                    x: 0,
                    y: 0
                },
                &info
            )
            .unwrap()
            .is_none()
        );
    }

    #[test]
    fn corrupt_or_vector_maps_and_cleartext_templates_are_rejected() {
        let file = tempfile::NamedTempFile::new().unwrap();
        assert!(open_database(file.path()).is_err());
        assert!(!valid_template("http://example.org/{z}/{x}/{y}.png"));
        assert!(!valid_template("https://token@example.org/{z}/{x}/{y}.png"));
        assert!(valid_template(
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        ));
        let database = Connection::open(file.path()).unwrap();
        database.execute_batch("CREATE TABLE metadata(name TEXT,value TEXT); INSERT INTO metadata VALUES ('format','pbf'); CREATE TABLE tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB);").unwrap();
        drop(database);
        assert!(open_database(file.path()).is_err());
    }

    #[test]
    fn png_tiles_decode_with_tms_rows_and_corrupt_images_are_rejected() {
        let file = tempfile::NamedTempFile::new().unwrap();
        let database = Connection::open(file.path()).unwrap();
        database.execute_batch("CREATE TABLE metadata(name TEXT,value TEXT); INSERT INTO metadata VALUES ('format','png'); CREATE TABLE tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB);").unwrap();
        let image = image::RgbaImage::from_pixel(256, 256, image::Rgba([20, 40, 80, 255]));
        let mut encoded = Cursor::new(Vec::new());
        image
            .write_to(&mut encoded, image::ImageFormat::Png)
            .unwrap();
        database
            .execute(
                "INSERT INTO tiles VALUES (2,0,3,?1)",
                [encoded.into_inner()],
            )
            .unwrap();
        database
            .execute_batch(
                "INSERT INTO tiles VALUES (2,1,3,'not a BLOB');
             INSERT INTO tiles VALUES (2,2,3,X'000102');",
            )
            .unwrap();
        database
            .execute(
                "INSERT INTO tiles VALUES (2,3,3,zeroblob(?1))",
                [MAX_TILE_BYTES + 1],
            )
            .unwrap();
        drop(database);
        let (database, info) = open_database(file.path()).unwrap();
        let decoded = read_tile(
            &database,
            TileId {
                zoom: 2,
                x: 0,
                y: 0,
            },
            &info,
        )
        .unwrap()
        .unwrap();
        assert_eq!(decoded.size, [256, 256]);
        assert_eq!(decoded.pixels[0], egui::Color32::from_rgb(20, 40, 80));
        for x in [1, 2] {
            assert!(read_tile(&database, TileId { zoom: 2, x, y: 0 }, &info).is_err());
        }
        assert!(
            read_tile(
                &database,
                TileId {
                    zoom: 2,
                    x: 3,
                    y: 0
                },
                &info
            )
            .unwrap()
            .is_none()
        );
        assert!(
            read_tile(
                &database,
                TileId {
                    zoom: 2,
                    x: 0,
                    y: 3
                },
                &info
            )
            .unwrap()
            .is_none()
        );
        assert!(decode_raster(b"broken image").is_err());
        let oversized = image::RgbaImage::from_pixel(1024, 1024, image::Rgba([0, 0, 0, 255]));
        let mut encoded = Cursor::new(Vec::new());
        oversized
            .write_to(&mut encoded, image::ImageFormat::Png)
            .unwrap();
        assert!(decode_raster(encoded.get_ref()).is_err());
    }
}
