//! Versioned, bounded navigation output shared by the desktop viewer and VPN relay.
//! Coordinates and guidance are transient output, never positioning inputs.

use serde::{Deserialize, Serialize};

pub const HANDSHAKE: &[u8] = b"IMUNAV NAV/1\r\n";
pub const VERSION: u8 = 1;
pub const MAX_FRAME_BYTES: usize = 16 * 1024;
pub const MAX_ROUTE_BYTES: usize = 1024 * 1024;
pub const MAX_ROUTE_POINTS: usize = 100_000;
pub const CHUNK_POINTS: usize = 128;

/// Maneuver vocabulary follows the phone's OSRM-compatible steps.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct Maneuver {
    pub kind: String,
    pub modifier: Option<String>,
    pub name: String,
    pub roundabout_exit: Option<u32>,
}

/// Phone-owned guidance. A missing verdict means that no GPS fix has been classified.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
// These independent phone flags may coexist (for example off-route while rerouting).
#[allow(clippy::struct_excessive_bools)]
pub struct Guidance {
    pub route_revision: Option<u64>,
    pub route_unavailable: bool,
    pub next: Option<Maneuver>,
    pub then: Option<Maneuver>,
    pub distance_to_next_m: f64,
    pub remaining_m: f64,
    pub remaining_s: f64,
    pub progress_m: f64,
    pub travel_mode: String,
    pub speed_limit_kmh: Option<u32>,
    pub uncertainty_m: f64,
    pub source: String,
    pub gps_state: String,
    pub gps_verdict: Option<String>,
    pub jammed: bool,
    pub off_route: bool,
    pub rerouting: bool,
    pub arrived: bool,
}

impl Guidance {
    /// Validate untrusted publishers without treating navigation allowance as GPS accuracy.
    #[must_use]
    pub fn valid(&self) -> bool {
        self.route_revision != Some(0)
            && [
                self.distance_to_next_m,
                self.remaining_m,
                self.remaining_s,
                self.progress_m,
                self.uncertainty_m,
            ]
            .iter()
            .all(|value| value.is_finite() && *value >= 0.0)
            && matches!(self.travel_mode.as_str(), "CAR" | "FOOT")
            && matches!(
                self.source.as_str(),
                "GPS" | "GPS_SUSPECT" | "DR" | "DR_NET" | "DR_OBD" | "DR_STOPPED" | "CELL"
            )
            && matches!(self.gps_state.as_str(), "OK" | "DEGRADED" | "LOST")
            && self
                .gps_verdict
                .as_deref()
                .is_none_or(|verdict| matches!(verdict, "GOOD" | "SUSPECT" | "BAD"))
            && self
                .speed_limit_kmh
                .is_none_or(|speed| speed > 0 && speed <= 1000)
            && [&self.next, &self.then].iter().all(|step| {
                step.as_ref().is_none_or(|step| {
                    step.kind.len() <= 64
                        && step
                            .modifier
                            .as_ref()
                            .is_none_or(|modifier| modifier.len() <= 64)
                        && step.name.len() <= 1024
                        && step.roundabout_exit.is_none_or(|exit| exit <= 100)
                })
            })
    }
}

/// Position and guidance are encoded together, so a later tick cannot change their relationship.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct State {
    pub sequence: u64,
    pub elapsed_ms: u64,
    pub utc_ms: i64,
    pub age_ms: u64,
    pub nmea: String,
    pub guidance: Option<Guidance>,
}

impl State {
    #[must_use]
    pub fn valid(&self) -> bool {
        self.sequence > 0
            && (0..=4_102_444_799_999).contains(&self.utc_ms)
            && self.age_ms <= 5000
            && parse_rmc(&self.nmea).is_ok()
            && self.guidance.as_ref().is_none_or(Guidance::valid)
    }
}

/// Full geometry lives once per session, independently of small, frequent state updates.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct Route {
    pub revision: u64,
    pub geometry: Vec<[f64; 2]>,
    pub summary: String,
    pub destination: [f64; 2],
}

impl Route {
    #[must_use]
    pub fn valid(&self) -> bool {
        self.revision > 0
            && (2..=MAX_ROUTE_POINTS).contains(&self.geometry.len())
            && self.summary.len() <= 1024
            && point_valid(&self.destination)
            && self.geometry.iter().all(point_valid)
    }

    /// Chunk geometry rather than buffering an entire route in each TCP writer.
    ///
    /// # Errors
    /// Rejects invalid geometry and routes exceeding the retained-payload limit.
    pub fn frames(&self) -> Result<Vec<Vec<u8>>, ProtocolError> {
        if !self.valid() || serde_json::to_vec(self)?.len() > MAX_ROUTE_BYTES {
            return Err(ProtocolError::Invalid);
        }
        let mut frames = vec![encode(&Frame::RouteStart {
            revision: self.revision,
            points: self.geometry.len(),
            summary: self.summary.clone(),
            destination: self.destination,
        })?];
        for (index, points) in self.geometry.chunks(CHUNK_POINTS).enumerate() {
            frames.push(encode(&Frame::RouteChunk {
                revision: self.revision,
                offset: index * CHUNK_POINTS,
                geometry: points.to_vec(),
            })?);
        }
        frames.push(encode(&Frame::RouteEnd {
            revision: self.revision,
        })?);
        Ok(frames)
    }
}

#[must_use]
pub fn point_valid(point: &[f64; 2]) -> bool {
    (-90.0..=90.0).contains(&point[0]) && (-180.0..=180.0).contains(&point[1])
}

/// A hello acknowledges switching from plain RMC to the mirror stream at a line boundary.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum Frame {
    Hello {
        version: u8,
    },
    State {
        version: u8,
        state: Box<State>,
    },
    RouteStart {
        revision: u64,
        points: usize,
        summary: String,
        destination: [f64; 2],
    },
    RouteChunk {
        revision: u64,
        offset: usize,
        geometry: Vec<[f64; 2]>,
    },
    RouteEnd {
        revision: u64,
    },
}

#[derive(Debug, thiserror::Error)]
pub enum ProtocolError {
    #[error("Invalid navigation data")]
    Invalid,
    #[error("Navigation message exceeds limit")]
    Oversized,
    #[error("Invalid navigation encoding")]
    Json(#[from] serde_json::Error),
}

/// # Errors
/// Returns an error for malformed or oversized JSON; callers must validate the frame contents.
pub fn decode(bytes: &[u8]) -> Result<Frame, ProtocolError> {
    if bytes.len() > MAX_FRAME_BYTES {
        return Err(ProtocolError::Oversized);
    }
    Ok(serde_json::from_slice(bytes)?)
}

/// # Errors
/// Rejects frames exceeding the wire limit, including their newline delimiter.
pub fn encode(frame: &Frame) -> Result<Vec<u8>, ProtocolError> {
    let mut bytes = serde_json::to_vec(frame)?;
    bytes.push(b'\n');
    if bytes.len() > MAX_FRAME_BYTES {
        return Err(ProtocolError::Oversized);
    }
    Ok(bytes)
}

/// A TCP read can split any delimiter. This decoder retains at most one bounded partial line.
#[derive(Default)]
pub struct Lines {
    partial: Vec<u8>,
}

impl Lines {
    /// # Errors
    /// Rejects unterminated oversized input before it can allocate an unbounded buffer.
    pub fn push(&mut self, bytes: &[u8]) -> Result<Vec<Vec<u8>>, ProtocolError> {
        let mut lines = Vec::new();
        for byte in bytes {
            self.partial.push(*byte);
            if self.partial.len() > MAX_FRAME_BYTES {
                return Err(ProtocolError::Oversized);
            }
            if *byte == b'\n' {
                lines.push(std::mem::take(&mut self.partial));
            }
        }
        Ok(lines)
    }
}

/// A partial route is never visible. Revision and sequential offsets reject mixed route transfers.
#[derive(Default)]
pub struct RouteAssembly {
    pending: Option<(Route, usize, usize)>,
}

impl RouteAssembly {
    /// # Errors
    /// Rejects mismatched revisions, missing chunks and resource-limit violations.
    pub fn accept(&mut self, frame: Frame) -> Result<Option<Route>, ProtocolError> {
        match frame {
            Frame::RouteStart {
                revision,
                points,
                summary,
                destination,
            } => {
                self.pending = None;
                if revision == 0
                    || !(2..=MAX_ROUTE_POINTS).contains(&points)
                    || summary.len() > 1024
                    || !point_valid(&destination)
                {
                    return Err(ProtocolError::Invalid);
                }
                self.pending = Some((
                    Route {
                        revision,
                        geometry: Vec::new(),
                        summary,
                        destination,
                    },
                    points,
                    0,
                ));
            }
            Frame::RouteChunk {
                revision,
                offset,
                geometry,
            } => {
                let Some((route, expected, bytes)) = self.pending.as_mut() else {
                    return Err(ProtocolError::Invalid);
                };
                *bytes += serde_json::to_vec(&geometry)?.len();
                if revision != route.revision
                    || offset != route.geometry.len()
                    || geometry.is_empty()
                    || route.geometry.len() + geometry.len() > *expected
                    || *bytes > MAX_ROUTE_BYTES
                    || !geometry.iter().all(point_valid)
                {
                    self.pending = None;
                    return Err(ProtocolError::Invalid);
                }
                route.geometry.extend(geometry);
            }
            Frame::RouteEnd { revision } => {
                let Some((route, expected, _)) = self.pending.take() else {
                    return Err(ProtocolError::Invalid);
                };
                if route.revision != revision
                    || route.geometry.len() != expected
                    || !route.valid()
                    || serde_json::to_vec(&route)?.len() > MAX_ROUTE_BYTES
                {
                    return Err(ProtocolError::Invalid);
                }
                return Ok(Some(route));
            }
            Frame::Hello { .. } | Frame::State { .. } => {}
        }
        Ok(None)
    }
}

/// Parsed RMC position; estimated mode does not establish GPS trust or accuracy.
#[derive(Clone, Debug, PartialEq)]
pub struct Fix {
    pub point: [f64; 2],
    pub speed_mps: f64,
    pub bearing_deg: f64,
}

/// # Errors
/// Requires an active, checksummed RMC sentence with finite geographic values and valid UTC.
pub fn parse_rmc(sentence: &str) -> Result<Fix, ProtocolError> {
    let sentence = sentence.trim_end_matches(['\r', '\n']);
    if sentence.len() > 82 || !sentence.is_ascii() {
        return Err(ProtocolError::Invalid);
    }
    let (body, checksum) = sentence
        .strip_prefix('$')
        .and_then(|text| text.split_once('*'))
        .ok_or(ProtocolError::Invalid)?;
    if checksum.len() != 2
        || u8::from_str_radix(checksum, 16).ok()
            != Some(body.bytes().fold(0, |sum, byte| sum ^ byte))
    {
        return Err(ProtocolError::Invalid);
    }
    // RMC needs twelve fields plus an optional mode. Borrow them without a heap table.
    let mut parts = body.split(',');
    let mut fields = [""; 12];
    for field in &mut fields {
        *field = parts.next().ok_or(ProtocolError::Invalid)?;
    }
    if !matches!(fields[0], "GPRMC" | "GNRMC") || fields[2] != "A" || parts.next() == Some("N") {
        return Err(ProtocolError::Invalid);
    }
    validate_utc(fields[1], fields[9])?;
    let point = [
        coordinate(fields[3], fields[4], true)?,
        coordinate(fields[5], fields[6], false)?,
    ];
    let speed_mps = number(fields[7])? * 0.514_444_444_444_444_5;
    let bearing_deg = number(fields[8])?;
    if !(0.0..=1000.0).contains(&speed_mps) || !(0.0..=360.0).contains(&bearing_deg) {
        return Err(ProtocolError::Invalid);
    }
    Ok(Fix {
        point,
        speed_mps,
        bearing_deg: bearing_deg % 360.0,
    })
}

fn number(text: &str) -> Result<f64, ProtocolError> {
    text.parse::<f64>()
        .ok()
        .filter(|number| number.is_finite())
        .ok_or(ProtocolError::Invalid)
}

fn coordinate(text: &str, hemisphere: &str, latitude: bool) -> Result<f64, ProtocolError> {
    let width = if latitude { 2 } else { 3 };
    if !text.is_ascii() || text.len() < width + 2 {
        return Err(ProtocolError::Invalid);
    }
    let degrees = text[..width]
        .parse::<u16>()
        .map_err(|_| ProtocolError::Invalid)?;
    let minutes = number(&text[width..])?;
    if !(0.0..60.0).contains(&minutes) {
        return Err(ProtocolError::Invalid);
    }
    let sign = match (latitude, hemisphere) {
        (true, "N") | (false, "E") => 1.0,
        (true, "S") | (false, "W") => -1.0,
        _ => return Err(ProtocolError::Invalid),
    };
    let value = sign * (f64::from(degrees) + minutes / 60.0);
    if value.abs() > if latitude { 90.0 } else { 180.0 } {
        return Err(ProtocolError::Invalid);
    }
    Ok(value)
}

fn validate_utc(clock: &str, date: &str) -> Result<(), ProtocolError> {
    if !clock.is_ascii() || !date.is_ascii() || clock.len() < 6 || date.len() != 6 {
        return Err(ProtocolError::Invalid);
    }
    let integer = |text: &str| text.parse::<u8>().map_err(|_| ProtocolError::Invalid);
    time::Time::from_hms(
        integer(&clock[..2])?,
        integer(&clock[2..4])?,
        integer(&clock[4..6])?,
    )
    .map_err(|_| ProtocolError::Invalid)?;
    if clock.len() > 6
        && (!clock[6..].starts_with('.')
            || clock.len() == 7
            || !clock[7..].bytes().all(|byte| byte.is_ascii_digit()))
    {
        return Err(ProtocolError::Invalid);
    }
    let month = time::Month::try_from(integer(&date[2..4])?).map_err(|_| ProtocolError::Invalid)?;
    time::Date::from_calendar_date(
        2000 + i32::from(integer(&date[4..6])?),
        month,
        integer(&date[..2])?,
    )
    .map_err(|_| ProtocolError::Invalid)?;
    Ok(())
}

#[cfg(test)]
mod tests;
