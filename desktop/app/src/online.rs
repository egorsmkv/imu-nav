//! HTTPS-only raster fetching with bounded bodies, image decoding and conditional HTTP caching.

use crate::maps::decode_raster;
use reqwest::blocking::Client;
use reqwest::header::{
    AGE, CACHE_CONTROL, DATE, ETAG, EXPIRES, HeaderMap, IF_MODIFIED_SINCE, IF_NONE_MATCH,
    LAST_MODIFIED,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::io::Read;
use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use walkers::TileId;

const MAX_TILE_BYTES: u64 = 4 * 1024 * 1024;
const MAX_METADATA_BYTES: u64 = 4096;
const DEFAULT_CACHE_SECONDS: u64 = 7 * 24 * 60 * 60;

#[derive(Default, Serialize, Deserialize)]
struct CacheEntry {
    expires: u64,
    lifetime_s: u64,
    etag: Option<String>,
    modified: Option<String>,
}

/// One private cache and one client per worker; navigation coordinates are never sent to this client.
pub struct Fetcher {
    template: String,
    client: Client,
    cache: PathBuf,
}

impl Fetcher {
    pub fn new(template: String) -> Result<Self, ()> {
        let client = Client::builder()
            .https_only(true)
            .user_agent("IMU-Nav-Desktop/0.1.0")
            .connect_timeout(Duration::from_secs(3))
            .timeout(Duration::from_secs(5))
            .redirect(reqwest::redirect::Policy::limited(3))
            .build()
            .map_err(|_| ())?;
        let cache = std::env::var_os("LOCALAPPDATA")
            .or_else(|| std::env::var_os("XDG_CACHE_HOME"))
            .map_or_else(std::env::temp_dir, PathBuf::from)
            .join("imu-nav-desktop/tile-cache");
        _ = std::fs::create_dir_all(&cache);
        Ok(Self {
            template,
            client,
            cache,
        })
    }

    pub fn fetch(&self, tile: TileId) -> Result<Option<egui::ColorImage>, ()> {
        let url = self
            .template
            .replace("{z}", &tile.zoom.to_string())
            .replace("{x}", &tile.x.to_string())
            .replace("{y}", &tile.y.to_string());
        let key = format!("{:x}", Sha256::digest(url.as_bytes()));
        let path = self.cache.join(&key);
        let metadata_path = self.cache.join(format!("{key}.json"));
        let metadata: Option<CacheEntry> = bounded_file(&metadata_path, MAX_METADATA_BYTES)
            .and_then(|bytes| serde_json::from_slice(&bytes).ok());
        let cached = bounded_file(&path, MAX_TILE_BYTES);
        let now = seconds();
        if metadata.as_ref().is_some_and(|entry| entry.expires > now)
            && let Some(bytes) = &cached
        {
            return decode_raster(bytes).map(Some);
        }
        let mut request = self.client.get(url);
        if cached.is_some()
            && let Some(entry) = &metadata
        {
            if let Some(etag) = &entry.etag {
                request = request.header(IF_NONE_MATCH, etag);
            }
            if let Some(modified) = &entry.modified {
                request = request.header(IF_MODIFIED_SINCE, modified);
            }
        }
        let response = request.send().map_err(|_| ())?;
        let previous = metadata
            .as_ref()
            .filter(|_| response.status() == reqwest::StatusCode::NOT_MODIFIED);
        let policy = cache_policy(response.headers(), now, previous);
        if response.status() == reqwest::StatusCode::NOT_MODIFIED {
            let image = decode_raster(cached.as_ref().ok_or(())?).map(Some)?;
            if let Some(entry) = policy {
                _ = std::fs::write(metadata_path, serde_json::to_vec(&entry).map_err(|_| ())?);
            }
            return Ok(image);
        }
        if !response.status().is_success()
            || response
                .content_length()
                .is_some_and(|length| length > MAX_TILE_BYTES)
        {
            return Err(());
        }
        let mut bytes = Vec::new();
        response
            .take(MAX_TILE_BYTES + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| ())?;
        if bytes.len() > usize::try_from(MAX_TILE_BYTES).map_err(|_| ())? {
            return Err(());
        }
        let image = decode_raster(&bytes)?;
        if let Some(entry) = policy {
            _ = std::fs::write(path, bytes);
            _ = std::fs::write(metadata_path, serde_json::to_vec(&entry).map_err(|_| ())?);
        } else {
            // These hash-named files belong only to our tile cache; honor a new no-store policy.
            _ = std::fs::remove_file(path);
            _ = std::fs::remove_file(metadata_path);
        }
        Ok(Some(image))
    }
}

fn bounded_file(path: &std::path::Path, limit: u64) -> Option<Vec<u8>> {
    let file = std::fs::File::open(path).ok()?;
    if file.metadata().ok()?.len() > limit {
        return None;
    }
    let mut bytes = Vec::new();
    file.take(limit + 1).read_to_end(&mut bytes).ok()?;
    (u64::try_from(bytes.len()).ok()? <= limit).then_some(bytes)
}

fn seconds() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn cache_policy(
    headers: &HeaderMap,
    now: u64,
    previous: Option<&CacheEntry>,
) -> Option<CacheEntry> {
    let control = headers
        .get(CACHE_CONTROL)
        .and_then(|header| header.to_str().ok())
        .unwrap_or_default();
    let directives = control.split(',').map(str::trim);
    if directives
        .clone()
        .any(|value| value.eq_ignore_ascii_case("no-store"))
    {
        return None;
    }
    let max_age = directives.clone().find_map(|directive| {
        let (key, value) = directive.split_once('=')?;
        key.eq_ignore_ascii_case("max-age")
            .then(|| value.trim_matches('"').parse::<u64>().ok())
            .flatten()
    });
    let expires = headers
        .get(EXPIRES)
        .and_then(|header| header.to_str().ok())
        .and_then(|text| httpdate::parse_http_date(text).ok())
        .and_then(|date| date.duration_since(UNIX_EPOCH).ok())
        .map(|duration| duration.as_secs());
    let age = headers
        .get(AGE)
        .and_then(|header| header.to_str().ok())
        .and_then(|text| text.parse::<u64>().ok())
        .unwrap_or_default();
    let server_date = headers
        .get(DATE)
        .and_then(|header| header.to_str().ok())
        .and_then(|text| httpdate::parse_http_date(text).ok())
        .and_then(|date| date.duration_since(UNIX_EPOCH).ok())
        .map_or(now, |duration| duration.as_secs());
    let lifetime_s = max_age
        .or_else(|| expires.map(|expires| expires.saturating_sub(server_date)))
        .or_else(|| previous.map(|entry| entry.lifetime_s))
        .unwrap_or(DEFAULT_CACHE_SECONDS);
    let expiration = if directives
        .clone()
        .any(|value| value.eq_ignore_ascii_case("no-cache"))
    {
        now
    } else {
        now.saturating_add(lifetime_s.saturating_sub(age))
    };
    let header_text = |name| {
        headers
            .get(name)
            .and_then(|header| header.to_str().ok())
            .filter(|text| text.len() <= 1024)
            .map(str::to_owned)
    };
    Some(CacheEntry {
        expires: expiration,
        lifetime_s,
        etag: header_text(ETAG).or_else(|| previous.and_then(|entry| entry.etag.clone())),
        modified: header_text(LAST_MODIFIED)
            .or_else(|| previous.and_then(|entry| entry.modified.clone())),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn client_refuses_cleartext_even_when_a_template_is_bypassed() {
        let fetcher = Fetcher::new("http://127.0.0.1:1/{z}/{x}/{y}.png".into()).unwrap();
        assert!(
            fetcher
                .fetch(TileId {
                    zoom: 0,
                    x: 0,
                    y: 0
                })
                .is_err()
        );
    }

    #[test]
    fn cache_file_reads_have_hard_limits() {
        let file = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(file.path(), b"test").unwrap();
        assert!(bounded_file(file.path(), 3).is_none());
        assert_eq!(bounded_file(file.path(), 4), Some(b"test".to_vec()));
    }

    #[test]
    fn http_cache_policy_honors_expiry_age_revalidation_and_no_store() {
        let mut headers = HeaderMap::new();
        headers.insert(CACHE_CONTROL, "public,max-age=600".parse().unwrap());
        headers.insert(AGE, "30".parse().unwrap());
        headers.insert(ETAG, "\"example\"".parse().unwrap());
        let entry = cache_policy(&headers, 1000, None).unwrap();
        assert_eq!(entry.expires, 1570);
        let refreshed = cache_policy(&HeaderMap::new(), 2000, Some(&entry)).unwrap();
        assert_eq!(refreshed.expires, 2600);
        assert_eq!(refreshed.etag, entry.etag);
        headers.insert(CACHE_CONTROL, "no-cache".parse().unwrap());
        assert_eq!(cache_policy(&headers, 1000, None).unwrap().expires, 1000);
        headers.insert(CACHE_CONTROL, "no-store".parse().unwrap());
        assert!(cache_policy(&headers, 1000, None).is_none());
        assert_eq!(
            cache_policy(&HeaderMap::new(), 1000, None).unwrap().expires,
            1000 + DEFAULT_CACHE_SECONDS
        );
    }

    #[test]
    fn cache_directive_scans_preserve_case_whitespace_and_precedence() {
        let mut headers = HeaderMap::new();
        headers.insert(
            CACHE_CONTROL,
            " public, MAX-AGE=\"600\", max-age=5 ".parse().unwrap(),
        );
        assert_eq!(cache_policy(&headers, 1000, None).unwrap().expires, 1600);
        headers.insert(CACHE_CONTROL, " max-age=600, No-Cache ".parse().unwrap());
        assert_eq!(cache_policy(&headers, 1000, None).unwrap().expires, 1000);
        headers.insert(
            CACHE_CONTROL,
            " max-age=600, No-Store, no-cache ".parse().unwrap(),
        );
        assert!(cache_policy(&headers, 1000, None).is_none());
    }
}
