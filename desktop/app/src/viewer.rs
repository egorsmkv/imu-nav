//! Map and navigation panel rendering; only preferences are serialized by eframe.

use crate::connection::{Connection, Snapshot, Status, endpoint};
use crate::language::Language;
use crate::maps::{MbTiles, valid_template};
use crate::smoke::Smoke;
use egui::{Color32, Stroke};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::mpsc::{Receiver, sync_channel};
use std::time::Duration;
use walkers::{Map, MapMemory, Projector, Tiles, lat_lon};

#[derive(Serialize, Deserialize)]
#[serde(default)]
struct Preferences {
    address: String,
    port: String,
    language: Language,
    dark: bool,
    tile_url: String,
    attribution: String,
    offline_file: Option<PathBuf>,
}

impl Default for Preferences {
    fn default() -> Self {
        Self {
            address: String::new(),
            port: "10110".into(),
            language: Language::English,
            dark: true,
            tile_url: "https://tile.openstreetmap.org/{z}/{x}/{y}.png".into(),
            attribution: "© OpenStreetMap contributors".into(),
            offline_file: None,
        }
    }
}

/// Workers are optional and exist only after an explicit connection or map-source selection.
// These independent UI choices/error indicators can coexist.
#[allow(clippy::struct_excessive_bools)]
pub struct Viewer {
    preferences: Preferences,
    connection: Option<Connection>,
    view: Snapshot,
    memory: MapMemory,
    online: bool,
    online_tiles: Option<MbTiles>,
    offline_tiles: Option<MbTiles>,
    file_dialog: Option<Receiver<Option<PathBuf>>>,
    settings: bool,
    endpoint_error: bool,
    map_error: bool,
    smoke: Option<Smoke>,
}

impl Viewer {
    pub fn new(context: &eframe::CreationContext<'_>) -> Self {
        let smoke = Smoke::from_arguments();
        let preferences: Preferences = context
            .storage
            .filter(|_| smoke.is_none())
            .and_then(|storage| eframe::get_value(storage, "preferences"))
            .unwrap_or_default();
        let offline_tiles = preferences
            .offline_file
            .clone()
            .map(|path| MbTiles::open(path, context.egui_ctx.clone()));
        let mut memory = MapMemory::default();
        _ = memory.set_zoom(13.0);
        let view = if smoke.is_some() {
            Smoke::snapshot()
        } else {
            Snapshot::default()
        };
        Self {
            preferences,
            connection: None,
            view,
            memory,
            online: false,
            online_tiles: None,
            offline_tiles,
            file_dialog: None,
            settings: false,
            endpoint_error: false,
            map_error: false,
            smoke,
        }
    }

    fn start_online(&mut self, context: &egui::Context) {
        self.map_error = !valid_template(&self.preferences.tile_url);
        if self.map_error {
            return;
        }
        self.online_tiles = Some(MbTiles::online(
            self.preferences.tile_url.clone(),
            context.clone(),
        ));
    }

    fn stop_online(&mut self) {
        self.online_tiles = None;
    }

    fn toolbar(&mut self, ui: &mut egui::Ui) {
        let language = self.preferences.language;
        ui.horizontal_wrapped(|ui| {
            ui.strong("IMU Nav");
            ui.label(language.text("address"));
            ui.add_enabled(
                self.connection.is_none(),
                egui::TextEdit::singleline(&mut self.preferences.address).desired_width(140.0),
            );
            ui.label(language.text("port"));
            ui.add_enabled(
                self.connection.is_none(),
                egui::TextEdit::singleline(&mut self.preferences.port).desired_width(55.0),
            );
            if self.connection.is_some() {
                if ui.button(language.text("disconnect")).clicked() {
                    self.connection = None;
                    self.view = Snapshot::default();
                }
            } else if ui.button(language.text("connect")).clicked() {
                self.endpoint_error = false;
                if let Some(address) = endpoint(&self.preferences.address, &self.preferences.port) {
                    self.connection = Some(Connection::start(address, ui.ctx().clone()));
                } else {
                    self.endpoint_error = true;
                }
            }
            let status = match self.view.status {
                Status::Disconnected => "disconnected",
                Status::Connecting => "connecting",
                Status::Connected => "connected",
                Status::Reconnecting => "retrying",
                Status::InvalidData => "invalid",
                Status::Unsupported => "unsupported",
            };
            ui.label(language.text(status));
            ui.toggle_value(&mut self.settings, language.text("settings"));
        });
        if self.endpoint_error {
            ui.colored_label(Color32::LIGHT_RED, language.text("endpoint"));
        }
        self.map_toolbar(ui);
        if self.settings {
            self.preferences_panel(ui);
        }
    }

    fn map_toolbar(&mut self, ui: &mut egui::Ui) {
        let language = self.preferences.language;
        ui.horizontal_wrapped(|ui| {
            let before = self.online;
            ui.selectable_value(&mut self.online, false, language.text("offline"));
            ui.selectable_value(&mut self.online, true, language.text("online"));
            if self.online != before {
                if self.online {
                    self.start_online(ui.ctx());
                } else {
                    self.stop_online();
                }
            }
            if ui
                .add_enabled(
                    self.file_dialog.is_none(),
                    egui::Button::new(language.text("import")),
                )
                .clicked()
            {
                let (sender, receiver) = sync_channel(1);
                let context = ui.ctx().clone();
                self.file_dialog = Some(receiver);
                std::thread::spawn(move || {
                    let file = rfd::FileDialog::new()
                        .set_title(language.text("import"))
                        .add_filter("MBTiles", &["mbtiles"])
                        .pick_file();
                    _ = sender.send(file);
                    context.request_repaint();
                });
            }
            if ui.button("+").clicked() {
                _ = self.memory.zoom_in();
            }
            if ui.button("−").clicked() {
                _ = self.memory.zoom_out();
            }
            if ui.button(language.text("follow")).clicked() {
                self.memory.follow_my_position();
            }
            if ui
                .add_enabled(
                    self.view.route.is_some(),
                    egui::Button::new(language.text("fit")),
                )
                .clicked()
            {
                self.fit_route(ui.available_size());
            }
        });
    }

    fn preferences_panel(&mut self, ui: &mut egui::Ui) {
        let language = self.preferences.language;
        ui.horizontal(|ui| {
            ui.selectable_value(&mut self.preferences.language, Language::English, "English");
            ui.selectable_value(
                &mut self.preferences.language,
                Language::Ukrainian,
                "Українська",
            );
            ui.selectable_value(&mut self.preferences.language, Language::Russian, "Русский");
            ui.checkbox(&mut self.preferences.dark, language.text("dark"));
        });
        ui.label(language.text("map_url"));
        ui.text_edit_singleline(&mut self.preferences.tile_url);
        ui.label(language.text("attribution"));
        ui.text_edit_singleline(&mut self.preferences.attribution);
        if ui.button(language.text("apply")).clicked() {
            self.stop_online();
            if self.online {
                self.start_online(ui.ctx());
            }
        }
        if self.map_error {
            ui.colored_label(Color32::LIGHT_RED, language.text("bad_map"));
        }
        ui.small(language.text("privacy"));
        ui.small(language.text("private"));
    }

    fn navigation(&self, ui: &mut egui::Ui) {
        let language = self.preferences.language;
        ui.heading(language.text("navigation"));
        if self.view.fix.is_some() && self.view.stale() {
            ui.colored_label(Color32::YELLOW, language.text("stale"));
        }
        if let Some(received) = self.view.received {
            ui.label(format!(
                "{}: {:.1} s",
                language.text("age"),
                (received.elapsed() + self.view.source_age).as_secs_f64()
            ));
        }
        if let Some(fix) = &self.view.fix {
            ui.heading(format!(
                "{:.0} {}",
                fix.speed_mps * 3.6,
                language.text("kmh")
            ));
            ui.monospace(format!("{:.6}, {:.6}", fix.point[0], fix.point[1]));
            ui.label(format!("{:.0}°", fix.bearing_deg));
        }
        ui.separator();
        let Some(guidance) = &self.view.guidance else {
            ui.heading(language.text("position_only"));
            ui.label(language.text("enable"));
            return;
        };
        ui.label(language.text(if guidance.travel_mode == "FOOT" {
            "foot"
        } else {
            "car"
        }));
        for (flag, key) in [
            (guidance.arrived, "arrived"),
            (guidance.off_route, "off_route"),
            (guidance.rerouting, "rerouting"),
            (guidance.jammed, "jammed"),
        ] {
            if flag {
                ui.colored_label(Color32::YELLOW, language.text(key));
            }
        }
        if let Some(next) = &guidance.next {
            ui.small(language.text("next"));
            ui.heading(language.maneuver(next));
            ui.heading(format!(
                "{:.0} {}",
                guidance.distance_to_next_m,
                language.text("meters")
            ));
        }
        if let Some(then) = &guidance.then {
            ui.label(format!(
                "{}: {}",
                language.text("then"),
                language.maneuver(then)
            ));
        }
        ui.separator();
        ui.label(language.text("remaining"));
        ui.heading(format!(
            "{:.1} km · {:.0} {}",
            guidance.remaining_m / 1000.0,
            (guidance.remaining_s / 60.0).ceil(),
            language.text("minutes")
        ));
        if let Some(limit) = guidance.speed_limit_kmh {
            ui.label(format!("≤ {limit} {}", language.text("kmh")));
        }
        ui.label(format!(
            "{}: {}",
            language.text("source"),
            language.text(&guidance.source)
        ));
        ui.label(format!(
            "{}: {} / {}",
            language.text("gps"),
            language.text(&guidance.gps_state),
            guidance
                .gps_verdict
                .as_ref()
                .map_or("—", |verdict| language.text(verdict))
        ));
        ui.label(format!(
            "{}: {:.0} {}",
            language.text("allowance"),
            guidance.uncertainty_m,
            language.text("meters")
        ));
        if guidance.route_unavailable {
            ui.colored_label(Color32::YELLOW, language.text("route_missing"));
        } else if guidance.route_revision.is_some() && self.view.route.is_none() {
            ui.label(language.text("waiting_route"));
        }
        if let Some(route) = &self.view.route {
            ui.label(&route.summary);
        }
    }

    fn poll_workers(&mut self, context: &egui::Context) {
        if let Some(connection) = &self.connection {
            self.view = connection.snapshot();
        }
        if let Some(receiver) = &self.file_dialog
            && let Ok(file) = receiver.try_recv()
        {
            self.file_dialog = None;
            if let Some(path) = file {
                self.preferences.offline_file = Some(path.clone());
                self.offline_tiles = Some(MbTiles::open(path, context.clone()));
                self.online = false;
                self.stop_online();
            }
        }
        if let Some(tiles) = &mut self.online_tiles {
            tiles.poll();
        }
        if let Some(tiles) = &mut self.offline_tiles {
            tiles.poll();
        }
    }

    fn fit_route(&mut self, available: egui::Vec2) {
        let Some(route) = &self.view.route else {
            return;
        };
        let first_lon = route.geometry[0][1];
        let (mut min_x, mut max_x, mut min_y, mut max_y) = (
            f64::INFINITY,
            f64::NEG_INFINITY,
            f64::INFINITY,
            f64::NEG_INFINITY,
        );
        for [latitude, longitude] in &route.geometry {
            let longitude = first_lon + (longitude - first_lon + 180.0).rem_euclid(360.0) - 180.0;
            let x = (longitude + 180.0) / 360.0;
            let y = mercator_y(*latitude);
            min_x = min_x.min(x);
            max_x = max_x.max(x);
            min_y = min_y.min(y);
            max_y = max_y.max(y);
        }
        let width = f64::from((available.x - 300.0).max(200.0));
        let height = f64::from(available.y.max(300.0));
        let zoom = (width / ((max_x - min_x).max(0.00001) * 256.0))
            .log2()
            .min((height / ((max_y - min_y).max(0.00001) * 256.0)).log2())
            - 1.0;
        _ = self.memory.set_zoom(zoom.clamp(0.0, 18.0));
        let latitude = ((std::f64::consts::PI * (1.0 - min_y - max_y)).sinh().atan()).to_degrees();
        let longitude = (f64::midpoint(min_x, max_x) * 360.0).rem_euclid(360.0) - 180.0;
        self.memory.center_at(lat_lon(latitude, longitude));
    }
}

impl eframe::App for Viewer {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        if let Some(smoke) = &mut self.smoke {
            smoke.poll(ui.ctx());
        }
        ui.ctx().set_visuals(if self.preferences.dark {
            egui::Visuals::dark()
        } else {
            egui::Visuals::light()
        });
        self.poll_workers(ui.ctx());
        let language = self.preferences.language;
        egui::Panel::top("toolbar").show(ui, |ui| self.toolbar(ui));
        egui::Panel::bottom("attribution").show(ui, |ui| {
            if self.online {
                ui.label(&self.preferences.attribution);
            } else if let Some(info) = self
                .offline_tiles
                .as_ref()
                .and_then(|tiles| tiles.info.as_ref())
            {
                ui.label(format!("{} · {}", info.name, info.attribution));
            }
            ui.small(language.text("prototype"));
        });
        egui::Panel::left("navigation")
            .default_size(270.0)
            .resizable(true)
            .show(ui, |ui| {
                egui::ScrollArea::vertical().show(ui, |ui| self.navigation(ui));
            });
        egui::CentralPanel::default().show(ui, |ui| {
            if !self.online {
                let info = self
                    .offline_tiles
                    .as_ref()
                    .and_then(|tiles| tiles.info.as_ref());
                if let Some(info) = info {
                    _ = self.memory.set_zoom(
                        self.memory
                            .zoom()
                            .clamp(f64::from(info.min_zoom), f64::from(info.max_zoom)),
                    );
                    if let Some(bounds) = info.bounds
                        && self.view.fix.is_none()
                        && self.memory.detached().is_none()
                    {
                        self.memory.center_at(lat_lon(
                            f64::midpoint(bounds[1], bounds[3]),
                            f64::midpoint(bounds[0], bounds[2]),
                        ));
                    }
                } else {
                    ui.label(
                        language.text(
                            if self
                                .offline_tiles
                                .as_ref()
                                .is_some_and(|tiles| tiles.failed)
                            {
                                "bad_map"
                            } else if self.offline_tiles.is_some() {
                                "loading"
                            } else {
                                "no_map"
                            },
                        ),
                    );
                }
            }
            let position = self.view.fix.as_ref().map_or_else(
                || lat_lon(50.45, 30.52),
                |fix| lat_lon(fix.point[0], fix.point[1]),
            );
            let tiles: Option<&mut dyn Tiles> = if self.online {
                self.online_tiles
                    .as_mut()
                    .map(|tiles| tiles as &mut dyn Tiles)
            } else {
                self.offline_tiles
                    .as_mut()
                    .map(|tiles| tiles as &mut dyn Tiles)
            };
            let view = &self.view;
            Map::new(tiles, &mut self.memory, position).show(ui, |ui, response, projector, _| {
                draw_navigation(ui, projector, view);
                if response.dragged() {
                    ui.ctx().request_repaint();
                }
            });
        });
        if self.connection.is_some() {
            ui.ctx().request_repaint_after(Duration::from_millis(250));
        }
    }

    fn save(&mut self, storage: &mut dyn eframe::Storage) {
        if self.smoke.is_none() {
            eframe::set_value(storage, "preferences", &self.preferences);
        }
    }
}

fn mercator_y(latitude: f64) -> f64 {
    (1.0 - latitude
        .clamp(-85.051_128_78, 85.051_128_78)
        .to_radians()
        .tan()
        .asinh()
        / std::f64::consts::PI)
        / 2.0
}

// Pixel-space drawing is deliberately f32, matching egui's viewport coordinates.
#[allow(clippy::cast_possible_truncation)]
fn draw_navigation(ui: &egui::Ui, projector: &Projector, view: &Snapshot) {
    let painter = ui.painter().with_clip_rect(ui.max_rect());
    let project = |point: &[f64; 2]| projector.project(lat_lon(point[0], point[1])).to_pos2();
    let draw_path = |points: &[[f64; 2]], color, width| {
        let mut previous: Option<egui::Pos2> = None;
        for point in points {
            let point = project(point);
            if let Some(last) = previous
                && last.distance_sq(point) > 1.0
            {
                if (last.x - point.x).abs() < ui.max_rect().width() * 4.0 {
                    painter.line_segment([last, point], Stroke::new(width, color));
                }
                previous = Some(point);
            } else if previous.is_none() {
                previous = Some(point);
            }
        }
    };
    if let Some(route) = &view.route {
        draw_path(&route.geometry, Color32::from_rgb(80, 160, 255), 5.0);
        painter.circle_filled(project(&route.destination), 8.0, Color32::LIGHT_RED);
    }
    draw_path(&view.trail, Color32::from_rgb(80, 210, 150), 2.0);
    if let Some(fix) = &view.fix {
        let center = project(&fix.point);
        let color = if view.stale() {
            Color32::GRAY
        } else {
            Color32::from_rgb(40, 200, 245)
        };
        if let Some(guidance) = &view.guidance {
            let radius = (guidance.uncertainty_m as f32
                * projector.scale_pixel_per_meter(lat_lon(fix.point[0], fix.point[1])))
            .clamp(0.0, 5000.0);
            painter.circle_filled(center, radius, color.gamma_multiply(0.1));
            painter.circle_stroke(center, radius, Stroke::new(1.0, color.gamma_multiply(0.5)));
        }
        let angle = fix.bearing_deg.to_radians() as f32;
        let forward = egui::vec2(angle.sin(), -angle.cos());
        let right = egui::vec2(forward.y, -forward.x);
        let points = vec![
            center + forward * 16.0,
            center - forward * 9.0 + right * 9.0,
            center - forward * 4.0,
            center - forward * 9.0 - right * 9.0,
        ];
        painter.add(egui::Shape::convex_polygon(
            points,
            color,
            Stroke::new(2.0, Color32::WHITE),
        ));
    }
}
