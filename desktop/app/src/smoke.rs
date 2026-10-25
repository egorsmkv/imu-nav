//! Opt-in developer screenshot of synthetic navigation; never captures a user's live session.

use crate::connection::{Snapshot, Status};
use imu_nav_protocol::{Frame, Route, decode, parse_rmc};
use std::path::PathBuf;
use std::sync::{Arc, mpsc};
use std::time::{Duration, Instant};

pub struct Smoke {
    path: PathBuf,
    started: Instant,
    requested: bool,
    saved: Option<mpsc::Receiver<bool>>,
}

impl Smoke {
    pub fn from_arguments() -> Option<Self> {
        let mut arguments = std::env::args_os().skip(1);
        if arguments.next()? != "--smoke-test" {
            return None;
        }
        let path = PathBuf::from(arguments.next()?);
        if arguments.next().is_some() {
            return None;
        }
        Some(Self {
            path,
            started: Instant::now(),
            requested: false,
            saved: None,
        })
    }

    pub fn snapshot() -> Snapshot {
        let frame = decode(include_bytes!("../../protocol/fixtures/state.ndjson"))
            .expect("validated synthetic fixture");
        let Frame::State { state, .. } = frame else {
            unreachable!("state fixture")
        };
        Snapshot {
            status: Status::Connected,
            mirror: true,
            fix: Some(parse_rmc(&state.nmea).expect("synthetic RMC")),
            guidance: state.guidance.map(Arc::new),
            route: Some(Arc::new(Route {
                revision: 1,
                geometry: vec![
                    [-50.501, -30.25],
                    [-50.5, -30.25],
                    [-50.5, -30.245],
                    [-50.497, -30.245],
                ],
                summary: "Synthetic test route".into(),
                destination: [-50.497, -30.245],
            })),
            trail: Arc::new(vec![[-50.501, -30.25], [-50.5, -30.25]]),
            received: Some(Instant::now()),
            source_age: Duration::ZERO,
        }
    }

    pub fn poll(&mut self, context: &egui::Context) {
        if self
            .saved
            .as_ref()
            .is_some_and(|saved| saved.try_recv().is_ok())
        {
            context.send_viewport_cmd(egui::ViewportCommand::Close);
            return;
        }
        let screenshot = context.input(|input| {
            input.events.iter().find_map(|event| {
                if let egui::Event::Screenshot { image, .. } = event {
                    Some(image.clone())
                } else {
                    None
                }
            })
        });
        if let Some(image) = screenshot {
            let path = self.path.clone();
            let context = context.clone();
            let (sender, receiver) = mpsc::sync_channel(1);
            self.saved = Some(receiver);
            std::thread::spawn(move || {
                let bytes: Vec<_> = image
                    .pixels
                    .iter()
                    .flat_map(egui::Color32::to_array)
                    .collect();
                let size = image
                    .size
                    .map(|value| u32::try_from(value).expect("window dimensions"));
                let image = image::RgbaImage::from_raw(size[0], size[1], bytes)
                    .expect("screenshot dimensions");
                if let Some(parent) = path.parent() {
                    _ = std::fs::create_dir_all(parent);
                }
                _ = sender.send(image.save(path).is_ok());
                context.request_repaint();
            });
        } else if !self.requested && self.started.elapsed() > Duration::from_secs(1) {
            self.requested = true;
            context.send_viewport_cmd(egui::ViewportCommand::Screenshot(egui::UserData::default()));
        }
        context.request_repaint_after(Duration::from_millis(100));
    }
}
