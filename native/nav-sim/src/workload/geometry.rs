//! Focused public route-API workloads, kept separate from app scenarios with straight-route truth.

use super::{Config, Outcome, Pass, Phase, Region, Run, Scenario, finish_phase};
use anyhow::{Result, anyhow, ensure};
use imu_nav_core::route::{GeoPoint, Projection, RouteGeometry};
use std::path::Path;

/// Prebuilt raw routes and query points exclude fixture generation from measurement.
pub(super) struct GeometryFixture {
    points: Vec<GeoPoint>,
    queries: Vec<(GeoPoint, f64)>,
}

impl GeometryFixture {
    pub(super) fn new(config: &Config, scenario: Scenario) -> Self {
        let points: Vec<_> = (0..config.route_points)
            .map(|index| {
                let fraction = f64::from(index) / f64::from(config.route_points - 1);
                let (north, east) = match scenario {
                    Scenario::Parallel if fraction <= 0.5 => (fraction * 2.0 * 10_000.0, 0.0),
                    Scenario::Parallel => ((1.0 - fraction) * 2.0 * 10_000.0, 40.0),
                    Scenario::Crossing => {
                        let angle = fraction * std::f64::consts::TAU;
                        (4000.0 * angle.sin(), 2000.0 * (2.0 * angle).sin())
                    }
                    _ => (
                        fraction * 20_000.0,
                        250.0 * (fraction * 12.0 * std::f64::consts::TAU).sin(),
                    ),
                };
                GeoPoint {
                    latitude_deg: 50.0 + north / 111_195.0,
                    longitude_deg: 30.0 + east / 71_500.0,
                }
            })
            .collect();
        let queries = (0..config.duration_s)
            .map(|index| {
                // The seed changes sampling along the route without changing route topology.
                let vertex = usize::try_from(
                    (u64::from(index) * 7919).wrapping_add(config.seed)
                        % u64::from(config.route_points),
                )
                .expect("route size is bounded");
                let mut point = points[vertex];
                if scenario == Scenario::Crossing && index % 3 == 0 {
                    point = GeoPoint {
                        latitude_deg: 50.0,
                        longitude_deg: 30.0,
                    };
                }
                if scenario == Scenario::Reacquisition {
                    point.longitude_deg += 0.02;
                }
                (point, if index % 2 == 0 { 40.0 } else { 2.0 })
            })
            .collect();
        Self { points, queries }
    }

    /// Measure construction, nearest/ambiguity queries and teardown with the same phase protocol.
    pub(super) fn run(&self, scenario: Scenario, pass: Pass, output: Option<&Path>) -> Result<Run> {
        let points = self.points.clone();
        let mut phases: Vec<Phase> = Vec::with_capacity(6);
        let region = Region::start(pass == Pass::Alloc);
        let route =
            RouteGeometry::new(points).map_err(|error| anyhow!("geometry route: {error:?}"))?;
        finish_phase(region, "route", 0, &mut phases, output)?;
        let mut outcome = Outcome::default();
        let mut region = Region::start(pass == Pass::Alloc);
        for (index, &(point, accuracy)) in self.queries.iter().enumerate() {
            if scenario == Scenario::Reacquisition {
                let local = route
                    .project(point, 0.0, 0.0, 0.0, f64::MAX)
                    .map_err(|error| anyhow!("local: {error:?}"))?;
                outcome.fallback_queries += u64::from(local.offset_m > 120.0);
                let projection = route
                    .project(point, 0.0, 0.0, 0.0, 120.0)
                    .map_err(|error| anyhow!("global: {error:?}"))?;
                record(&mut outcome, projection)?;
            } else if let Some(projection) = route
                .project_unambiguous(point, accuracy)
                .map_err(|error| anyhow!("coarse: {error:?}"))?
            {
                record(&mut outcome, projection)?;
            } else {
                outcome.hash(0);
                outcome.ambiguous_queries += 1;
            }
            outcome.geometry_queries += 1;
            outcome.ticks += 1;
            let completed_quarters = (index + 1) * 4 / self.queries.len();
            if completed_quarters > index * 4 / self.queries.len() {
                let name = match completed_quarters {
                    1 => "steady",
                    2 => "middle",
                    3 => "recovery",
                    _ => "late",
                };
                finish_phase(region, name, 0, &mut phases, output)?;
                region = Region::start(pass == Pass::Alloc);
            }
        }
        drop(region);
        let region = Region::start(pass == Pass::Alloc);
        drop(route);
        finish_phase(region, "teardown", 0, &mut phases, output)?;
        outcome.sessions = 1;
        outcome.validate(scenario)?;
        Ok(Run {
            scenario,
            outcome,
            phases,
        })
    }
}

fn record(outcome: &mut Outcome, projection: Projection) -> Result<()> {
    outcome.hash(1);
    outcome.hash(projection.segment as u64);
    for value in [
        projection.position_m,
        projection.offset_m,
        projection.point.latitude_deg,
        projection.point.longitude_deg,
    ] {
        ensure!(value.is_finite(), "non-finite geometry output");
        outcome.hash(value.to_bits());
    }
    outcome.accepted_geometry += 1;
    Ok(())
}
