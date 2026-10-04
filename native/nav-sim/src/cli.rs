//! Process isolation ensures CPU, heap and timing instrumentation never overlap.

use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{Duration, Instant};

use anyhow::{Context, Result, ensure};
use clap::{Args, Parser, Subcommand, ValueEnum};
use pprof::protos::Message;
use serde::{Deserialize, Serialize};

use crate::measurement;
use crate::report::{self, Report};
use crate::workload::{self, Fixture, Scenario};

/// Identical input configuration is required for meaningful before/after comparisons.
#[derive(Args, Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct Config {
    #[arg(long, value_enum, default_value = "all")]
    pub scenario: Scenario,
    #[arg(long, default_value_t = 1)]
    pub seed: u64,
    #[arg(long, default_value_t = 1800, value_parser = clap::value_parser!(u32).range(120..=7200))]
    pub duration_s: u32,
    #[arg(long, default_value_t = 10_000, value_parser = clap::value_parser!(u32).range(100..=1_000_000))]
    pub route_points: u32,
    #[arg(long, default_value_t = 5, value_parser = clap::value_parser!(u32).range(1..=30))]
    pub repetitions: u32,
    /// Increase the route to at least 100,000 points without changing sensor rates.
    #[arg(long)]
    pub stress: bool,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            scenario: Scenario::All,
            seed: 1,
            duration_s: 1800,
            route_points: 10_000,
            repetitions: 5,
            stress: false,
        }
    }
}

/// Each mode runs in its own OS process, including when selected through All.
#[derive(Clone, Copy, Debug, Deserialize, Serialize, ValueEnum, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub enum Pass {
    All,
    Hotpath,
    Heap,
    Alloc,
    Cpu,
    Timing,
}

impl Pass {
    pub fn name(self) -> &'static str {
        match self {
            Self::All => "all",
            Self::Hotpath => "hotpath",
            Self::Heap => "heap",
            Self::Alloc => "alloc",
            Self::Cpu => "cpu",
            Self::Timing => "timing",
        }
    }
}

#[derive(Parser)]
#[command(about = "Deterministic native navigation profiling (Linux)")]
struct Cli {
    #[command(subcommand)]
    command: Action,
}

#[derive(Subcommand)]
enum Action {
    /// Generate profiles and metrics; the output directory must not already exist.
    Run {
        #[command(flatten)]
        config: Config,
        #[arg(long)]
        out: PathBuf,
        #[arg(long, value_enum, default_value = "all")]
        pass: Pass,
    },
    /// Compare two complete run directories, requiring identical fixtures and outcomes.
    Compare {
        baseline: PathBuf,
        candidate: PathBuf,
        #[arg(long)]
        out: Option<PathBuf>,
    },
}

pub fn execute() -> Result<()> {
    match Cli::parse().command {
        Action::Run {
            mut config,
            out,
            pass,
        } => {
            if config.stress {
                config.route_points = config.route_points.max(100_000);
            }
            run(&config, &out, pass)
        }
        Action::Compare {
            baseline,
            candidate,
            out,
        } => {
            let comparison = report::compare(&baseline, &candidate)?;
            if let Some(path) = out {
                report::write_new(&path, comparison.as_bytes())?;
            }
            print!("{comparison}");
            Ok(())
        }
    }
}

fn create_output(out: &Path) -> Result<()> {
    if let Some(parent) = out.parent().filter(|path| !path.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::create_dir(out)
        .with_context(|| format!("output must be a new directory: {}", out.display()))
}

fn run(config: &Config, out: &Path, pass: Pass) -> Result<()> {
    #[cfg(not(feature = "profiling"))]
    ensure!(
        pass != Pass::Hotpath,
        "hotpath pass requires --features imu-nav-sim/profiling"
    );
    create_output(out)?;
    measurement::require_sampling_disabled()?;
    if pass == Pass::All {
        run_children(config, out)?;
        return Ok(());
    }
    // All fixtures exist before heap activation or CPU sampling; their ownership lasts through capture.
    let fixtures: Vec<_> = config
        .scenario
        .cases()
        .into_iter()
        .map(|scenario| (scenario, Fixture::new(config, scenario)))
        .collect();
    if pass == Pass::Heap {
        measurement::activate_heap()?;
    }
    let mut runs = Vec::new();
    for (scenario, fixture) in &fixtures {
        let directory = out.join(scenario.name());
        std::fs::create_dir(&directory)?;
        if pass == Pass::Hotpath {
            #[cfg(feature = "profiling")]
            {
                let guard = hotpath::HotpathGuardBuilder::new("imu-nav-sim")
                    .format(hotpath::Format::JsonPretty)
                    .output_path(directory.join("hotpath.json"))
                    .build();
                let result = workload::run(fixture, *scenario, pass, None);
                drop(guard);
                runs.push(result?);
            }
        } else if pass == Pass::Timing {
            std::hint::black_box(workload::run(fixture, *scenario, pass, None)?); // warm-up excluded
            for _ in 0..config.repetitions {
                runs.push(workload::run(fixture, *scenario, pass, None)?);
            }
        } else if pass == Pass::Cpu {
            let guard = pprof::ProfilerGuardBuilder::default()
                .frequency(99)
                .blocklist(&["libc", "libgcc", "libpthread", "vdso"])
                .build()?;
            let started = Instant::now();
            let first = workload::run(fixture, *scenario, pass, None)?;
            let mut iterations = 1_u64;
            while started.elapsed() < Duration::from_secs(1) {
                let next = workload::run(fixture, *scenario, pass, None)?;
                ensure!(
                    next.outcome == first.outcome,
                    "CPU workload changed across repetitions"
                );
                iterations += 1;
            }
            let profile = guard.report().build()?;
            drop(guard);
            ensure!(
                !profile.data.is_empty(),
                "CPU profile has no samples; run a longer workload"
            );
            let mut protobuf = Vec::new();
            profile.pprof()?.encode(&mut protobuf)?;
            report::write_new(&directory.join("cpu.pb"), &protobuf)?;
            profile.flamegraph(std::fs::File::create(directory.join("cpu.svg"))?)?;
            report::write_new(
                &directory.join("iterations.txt"),
                format!("{iterations}\n").as_bytes(),
            )?;
            runs.push(first);
        } else {
            runs.push(workload::run(
                fixture,
                *scenario,
                pass,
                (pass == Pass::Heap).then_some(directory.as_path()),
            )?);
        }
    }
    let report = Report::new(config.clone(), pass, runs)?;
    report::write_new(
        &out.join("report.json"),
        &serde_json::to_vec_pretty(&report)?,
    )?;
    println!("{}: {} scenarios completed", pass.name(), fixtures.len());
    Ok(())
}

/// A new process per pass prevents allocator/profiler state from contaminating the next measurement.
fn run_children(config: &Config, out: &Path) -> Result<()> {
    run_children_with_executable(config, out, &std::env::current_exe()?)
}

// Passing the executable explicitly lets tests verify child failure without racing the filesystem.
fn run_children_with_executable(config: &Config, out: &Path, executable: &Path) -> Result<()> {
    std::fs::copy(executable, out.join("imu-nav-sim"))?;
    for child in [Pass::Heap, Pass::Alloc, Pass::Cpu, Pass::Timing] {
        let status = Command::new(executable)
            .args([
                "run",
                "--scenario",
                config.scenario.name(),
                "--seed",
                &config.seed.to_string(),
                "--duration-s",
                &config.duration_s.to_string(),
                "--route-points",
                &config.route_points.to_string(),
                "--repetitions",
                &config.repetitions.to_string(),
                "--pass",
                child.name(),
                "--out",
            ])
            .arg(out.join(child.name()))
            .args(if config.stress {
                vec!["--stress"]
            } else {
                vec![]
            })
            .status()?;
        ensure!(
            status.success(),
            "{} pass failed; partial output retained at {}",
            child.name(),
            out.display()
        );
    }
    report::write_new(
        &out.join("COMPLETE"),
        b"All four isolated passes completed.\n",
    )?;
    println!("profiles and matching binary: {}", out.display());
    Ok(())
}

#[cfg(test)]
mod tests;
