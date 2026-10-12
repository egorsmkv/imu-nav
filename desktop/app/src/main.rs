#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

//! Display-only navigation: the Android engine remains authoritative for every maneuver.

mod connection;
mod language;
mod maps;
mod online;
mod smoke;
mod viewer;

fn main() -> eframe::Result {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1200.0, 800.0])
            .with_min_inner_size([800.0, 560.0]),
        ..Default::default()
    };
    eframe::run_native(
        "IMU Nav Desktop",
        options,
        Box::new(|context| Ok(Box::new(viewer::Viewer::new(context)))),
    )
}
