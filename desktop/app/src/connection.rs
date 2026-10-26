//! A cancellable TCP worker publishes only the newest immutable display snapshot.

use imu_nav_protocol::{
    Fix, Frame, Guidance, HANDSHAKE, Lines, Route, RouteAssembly, VERSION, decode, parse_rmc,
};
use std::collections::VecDeque;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, Shutdown, SocketAddr, SocketAddrV4, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(2);
const READ_TIMEOUT: Duration = Duration::from_millis(200);
pub const STALE_AFTER: Duration = Duration::from_secs(3);
const MAX_TRAIL_POINTS: usize = 10_000;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Status {
    #[default]
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
    InvalidData,
    Unsupported,
}

/// All live data is transient. No socket worker writes positions or destinations to disk.
#[derive(Clone, Default)]
pub struct Snapshot {
    pub status: Status,
    pub mirror: bool,
    pub fix: Option<Fix>,
    pub guidance: Option<Arc<Guidance>>,
    pub route: Option<Arc<Route>>,
    pub trail: Arc<Vec<[f64; 2]>>,
    pub received: Option<Instant>,
    pub source_age: Duration,
}

impl Snapshot {
    #[must_use]
    pub fn stale(&self) -> bool {
        self.status != Status::Connected
            || self
                .received
                .is_none_or(|received| received.elapsed() >= STALE_AFTER)
    }
}

/// Closing the cloned socket interrupts reads; the condition variable interrupts retry delays.
pub struct Connection {
    snapshot: Arc<Mutex<Snapshot>>,
    cancel: Arc<AtomicBool>,
    socket: Arc<Mutex<Option<TcpStream>>>,
    wake: Arc<(Mutex<()>, Condvar)>,
}

impl Connection {
    pub fn start(address: SocketAddrV4, context: egui::Context) -> Self {
        let connection = Self {
            snapshot: Arc::default(),
            cancel: Arc::default(),
            socket: Arc::default(),
            wake: Arc::default(),
        };
        let snapshot = connection.snapshot.clone();
        let cancel = connection.cancel.clone();
        let socket = connection.socket.clone();
        let wake = connection.wake.clone();
        std::thread::spawn(move || {
            let mut backoff = 1;
            let mut view = Snapshot::default();
            while !cancel.load(Ordering::Relaxed) {
                view.status = if backoff == 1 {
                    Status::Connecting
                } else {
                    Status::Reconnecting
                };
                view.mirror = false;
                view.guidance = None;
                view.route = None;
                publish(&snapshot, &view, &context);
                if let Ok(mut stream) =
                    TcpStream::connect_timeout(&SocketAddr::V4(address), CONNECT_TIMEOUT)
                {
                    _ = stream.set_read_timeout(Some(READ_TIMEOUT));
                    _ = stream.set_write_timeout(Some(CONNECT_TIMEOUT));
                    _ = stream.set_nodelay(true);
                    *socket.lock().expect("socket lock") = stream.try_clone().ok();
                    if !cancel.load(Ordering::Relaxed) && stream.write_all(HANDSHAKE).is_ok() {
                        let mut receiver = Receiver::default();
                        let mut buffer = [0; 4096];
                        view.trail = Arc::default();
                        view.received = None;
                        let started = Instant::now();
                        loop {
                            if cancel.load(Ordering::Relaxed) {
                                break;
                            }
                            match stream.read(&mut buffer) {
                                Ok(0) => break,
                                Ok(count) => match receiver.accept(&buffer[..count], &mut view) {
                                    Ok(()) => {
                                        if view.received.is_some() {
                                            backoff = 1;
                                        }
                                        publish(&snapshot, &view, &context);
                                    }
                                    Err(status) => {
                                        view.status = status;
                                        publish(&snapshot, &view, &context);
                                        break;
                                    }
                                },
                                Err(error)
                                    if matches!(
                                        error.kind(),
                                        std::io::ErrorKind::WouldBlock
                                            | std::io::ErrorKind::TimedOut
                                    ) => {}
                                Err(_) => break,
                            }
                            // A silent peer cannot leave old guidance appearing live indefinitely.
                            if view
                                .received
                                .is_some_and(|received| received.elapsed() >= STALE_AFTER)
                                || (view.received.is_none() && started.elapsed() >= STALE_AFTER)
                            {
                                break;
                            }
                        }
                    }
                    _ = stream.shutdown(Shutdown::Both);
                    *socket.lock().expect("socket lock") = None;
                }
                if cancel.load(Ordering::Relaxed) {
                    break;
                }
                if view.status != Status::Unsupported && view.status != Status::InvalidData {
                    view.status = Status::Reconnecting;
                }
                view.guidance = None;
                view.route = None;
                publish(&snapshot, &view, &context);
                let (lock, condition) = &*wake;
                let guard = lock.lock().expect("retry lock");
                if !cancel.load(Ordering::Relaxed) {
                    _ = condition.wait_timeout(guard, Duration::from_secs(backoff));
                }
                backoff = (backoff * 2).min(30);
            }
        });
        connection
    }

    pub fn snapshot(&self) -> Snapshot {
        self.snapshot.lock().expect("snapshot lock").clone()
    }

    pub fn stop(&self) {
        self.cancel.store(true, Ordering::Relaxed);
        if let Some(socket) = self.socket.lock().expect("socket lock").as_ref() {
            _ = socket.shutdown(Shutdown::Both);
        }
        let (lock, condition) = &*self.wake;
        let _guard = lock.lock().expect("retry lock");
        condition.notify_all();
    }
}

impl Drop for Connection {
    fn drop(&mut self) {
        self.stop();
    }
}

fn publish(snapshot: &Mutex<Snapshot>, view: &Snapshot, context: &egui::Context) {
    *snapshot.lock().expect("snapshot lock") = view.clone();
    context.request_repaint();
}

/// Reject public addresses: the protocol is intentionally restricted to private networks/VPNs.
pub fn endpoint(address: &str, port: &str) -> Option<SocketAddrV4> {
    let address: Ipv4Addr = address.trim().parse().ok()?;
    let octets = address.octets();
    let private = address.is_private()
        || address.is_loopback()
        || (octets[0] == 100 && (64..=127).contains(&octets[1]));
    let port: u16 = port.trim().parse().ok()?;
    (private && port > 0).then(|| SocketAddrV4::new(address, port))
}

#[derive(Default)]
struct Receiver {
    lines: Lines,
    route: RouteAssembly,
    sequence: Option<imu_nav_protocol::Sequence>,
    elapsed: Option<imu_nav_protocol::ElapsedMs>,
    trail: VecDeque<[f64; 2]>,
}

impl Receiver {
    fn accept(&mut self, bytes: &[u8], view: &mut Snapshot) -> Result<(), Status> {
        for line in self.lines.push(bytes).map_err(|_| Status::InvalidData)? {
            if line.starts_with(b"$") && !view.mirror {
                let sentence = std::str::from_utf8(&line).map_err(|_| Status::InvalidData)?;
                // Bad RMC sentences never refresh the live-data clock.
                if let Ok(fix) = parse_rmc(sentence) {
                    self.position(fix, view);
                }
                continue;
            }
            match decode(&line).map_err(|error| match error {
                imu_nav_protocol::ProtocolError::UnsupportedVersion => Status::Unsupported,
                _ => Status::InvalidData,
            })? {
                Frame::Hello { version } => {
                    if version != VERSION {
                        return Err(Status::Unsupported);
                    }
                    view.mirror = true;
                }
                Frame::State { version, state } => {
                    if version != VERSION {
                        return Err(Status::Unsupported);
                    }
                    if !view.mirror
                        || !state.valid()
                        || self
                            .sequence
                            .is_some_and(|previous| state.sequence <= previous)
                        || self
                            .elapsed
                            .is_some_and(|elapsed| state.elapsed_ms <= elapsed)
                    {
                        return Err(Status::InvalidData);
                    }
                    self.sequence = Some(state.sequence);
                    self.elapsed = Some(state.elapsed_ms);
                    view.source_age = Duration::from_millis(state.age_ms.get().unsigned_abs());
                    view.guidance = state.guidance.map(Arc::new);
                    let revision = view
                        .guidance
                        .as_ref()
                        .and_then(|guidance| guidance.route_revision);
                    if view
                        .route
                        .as_ref()
                        .is_some_and(|route| Some(route.revision) != revision)
                    {
                        view.route = None;
                    }
                    self.position(
                        parse_rmc(&state.nmea).map_err(|_| Status::InvalidData)?,
                        view,
                    );
                }
                frame => {
                    if !view.mirror {
                        return Err(Status::InvalidData);
                    }
                    if let Some(route) =
                        self.route.accept(frame).map_err(|_| Status::InvalidData)?
                        && view
                            .guidance
                            .as_ref()
                            .and_then(|guidance| guidance.route_revision)
                            == Some(route.revision)
                    {
                        view.route = Some(Arc::new(route));
                    }
                }
            }
        }
        Ok(())
    }

    fn position(&mut self, fix: Fix, view: &mut Snapshot) {
        if self.trail.back() != Some(&fix.point) {
            self.trail.push_back(fix.point);
            if self.trail.len() > MAX_TRAIL_POINTS {
                self.trail.pop_front();
            }
            view.trail = Arc::new(self.trail.iter().copied().collect());
        }
        view.fix = Some(fix);
        view.received = Some(Instant::now());
        view.status = Status::Connected;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_private_explicit_endpoints_are_allowed() {
        assert!(endpoint("127.0.0.1", "10110").is_some());
        assert!(endpoint("100.77.0.1", "10111").is_some());
        assert!(endpoint("192.168.43.1", "0").is_none());
        assert!(endpoint("8.8.8.8", "10110").is_none());
    }

    #[test]
    fn malformed_or_unsupported_frames_cannot_become_live_data() {
        let mut receiver = Receiver::default();
        let mut view = Snapshot::default();
        assert_eq!(
            receiver.accept(b"{\"type\":\"hello\",\"version\":2}\n", &mut view),
            Err(Status::Unsupported)
        );
        assert!(view.received.is_none());
        assert!(view.stale());
        assert_eq!(
            receiver.accept(b"{}\n", &mut view),
            Err(Status::InvalidData)
        );
    }

    #[test]
    fn state_ordering_route_replacement_and_withdrawal_are_atomic() {
        let mut receiver = Receiver::default();
        let mut view = Snapshot::default();
        receiver
            .accept(
                &imu_nav_protocol::encode(&Frame::Hello { version: VERSION }).unwrap(),
                &mut view,
            )
            .unwrap();
        let bytes = include_bytes!("../../protocol/fixtures/state.ndjson");
        for byte in bytes {
            receiver.accept(&[*byte], &mut view).unwrap();
        }
        assert!(!view.stale());
        let route = Route {
            revision: imu_nav_protocol::Revision::new(1).unwrap(),
            geometry: vec![[0.0, 0.0], [0.01, 0.01]],
            summary: String::new(),
            destination: [0.01, 0.01],
        };
        for frame in route.frames().unwrap() {
            receiver.accept(&frame, &mut view).unwrap();
        }
        assert!(view.route.is_some());
        let Frame::State { version, mut state } = decode(bytes).unwrap() else {
            panic!("state fixture");
        };
        state.sequence = imu_nav_protocol::Sequence::new(2).unwrap();
        state.elapsed_ms = imu_nav_protocol::ElapsedMs::new(1500).unwrap();
        let guidance = state.guidance.as_mut().unwrap();
        guidance.source = "GPS".parse().unwrap();
        guidance.gps_state = "OK".parse().unwrap();
        guidance.gps_verdict = Some("GOOD".parse().unwrap());
        state.guidance.as_mut().unwrap().route_revision =
            Some(imu_nav_protocol::Revision::new(2).unwrap());
        receiver
            .accept(
                &imu_nav_protocol::encode(&Frame::State {
                    version,
                    state: state.clone(),
                })
                .unwrap(),
                &mut view,
            )
            .unwrap();
        assert!(view.route.is_none());
        assert_eq!(view.guidance.as_ref().unwrap().source.as_str(), "GPS");
        state.sequence = imu_nav_protocol::Sequence::new(3).unwrap();
        state.elapsed_ms = imu_nav_protocol::ElapsedMs::new(2000).unwrap();
        let guidance = state.guidance.as_mut().unwrap();
        guidance.source = "DR".parse().unwrap();
        guidance.gps_state = "LOST".parse().unwrap();
        guidance.rerouting = true;
        receiver
            .accept(
                &imu_nav_protocol::encode(&Frame::State {
                    version,
                    state: state.clone(),
                })
                .unwrap(),
                &mut view,
            )
            .unwrap();
        assert_eq!(view.guidance.as_ref().unwrap().source.as_str(), "DR");
        assert!(view.guidance.as_ref().unwrap().rerouting);
        state.sequence = imu_nav_protocol::Sequence::new(4).unwrap();
        state.elapsed_ms = imu_nav_protocol::ElapsedMs::new(2500).unwrap();
        state.guidance = None;
        let off = imu_nav_protocol::encode(&Frame::State { version, state }).unwrap();
        receiver.accept(&off, &mut view).unwrap();
        assert!(view.guidance.is_none());
        assert_eq!(receiver.accept(&off, &mut view), Err(Status::InvalidData));
        view.received = Instant::now().checked_sub(STALE_AFTER);
        assert!(view.stale());
    }

    #[test]
    fn tcp_worker_reconnects_and_cancels_without_blocking_the_ui() {
        let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let SocketAddr::V4(address) = listener.local_addr().unwrap() else {
            panic!("IPv4 listener");
        };
        listener.set_nonblocking(true).unwrap();
        let context = egui::Context::default();
        let connection = Connection::start(address, context);
        let accept = || {
            let deadline = Instant::now() + Duration::from_secs(10);
            loop {
                if let Ok((stream, _)) = listener.accept() {
                    stream.set_nonblocking(false).unwrap();
                    return stream;
                }
                assert!(Instant::now() < deadline);
                std::thread::sleep(Duration::from_millis(10));
            }
        };
        let mut first = accept();
        first
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        let mut request = [0; HANDSHAKE.len()];
        first.read_exact(&mut request).unwrap();
        assert_eq!(&request, HANDSHAKE);
        first
            .write_all(include_bytes!("../../protocol/fixtures/position.nmea"))
            .unwrap();
        let deadline = Instant::now() + Duration::from_secs(5);
        while connection.snapshot().fix.is_none() {
            assert!(Instant::now() < deadline);
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(!connection.snapshot().mirror);
        first.shutdown(Shutdown::Both).unwrap();
        drop(first);
        let mut second = accept();
        second
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        second.read_exact(&mut request).unwrap();
        second
            .write_all(&imu_nav_protocol::encode(&Frame::Hello { version: VERSION }).unwrap())
            .unwrap();
        second
            .write_all(include_bytes!("../../protocol/fixtures/state.ndjson"))
            .unwrap();
        let deadline = Instant::now() + Duration::from_secs(5);
        while connection.snapshot().guidance.is_none() {
            assert!(Instant::now() < deadline);
            std::thread::sleep(Duration::from_millis(10));
        }
        let started = Instant::now();
        connection.stop();
        assert!(started.elapsed() < Duration::from_secs(1));
        let mut byte = [0];
        assert_eq!(second.read(&mut byte).unwrap(), 0);
    }
}
