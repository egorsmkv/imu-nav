//! Versioned evidence records and strict comparison of matching deterministic workloads.

use pprof::protos::{Message, Profile};
use std::fmt::Write as _;
use std::io::{Read, Write as _};
use std::path::Path;

use anyhow::{Result, ensure};
use serde::{Deserialize, Serialize};

use crate::cli::{Config, Pass};
use crate::workload::{Run, Scenario};

/// Metadata is embedded at compile time; copied baseline executables retain their original identity.
#[derive(Debug, Deserialize, Serialize)]
pub struct Report {
    schema: u32,
    pub config: Config,
    pub pass: Pass,
    core_hash: String,
    harness_hash: String,
    rustc: String,
    optimization: String,
    rustflags: String,
    host: String,
    pub runs: Vec<Run>,
}

impl Report {
    pub fn new(config: Config, pass: Pass, runs: Vec<Run>) -> Result<Self> {
        Ok(Self {
            schema: 2,
            config,
            pass,
            core_hash: env!("SIM_CORE_HASH").into(),
            harness_hash: env!("SIM_HARNESS_HASH").into(),
            rustc: env!("SIM_RUSTC").into(),
            optimization: env!("SIM_OPT_LEVEL").into(),
            rustflags: str::to_owned(env!("SIM_RUSTFLAGS")),
            host: String::from_utf8(
                std::process::Command::new("uname")
                    .arg("-a")
                    .output()?
                    .stdout,
            )?
            .trim()
            .into(),
            runs,
        })
    }
}

pub fn write_new(path: &Path, bytes: &[u8]) -> Result<()> {
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)?;
    file.write_all(bytes)?;
    Ok(())
}

fn load(root: &Path, pass: Pass) -> Result<Report> {
    ensure!(
        root.join("COMPLETE").is_file(),
        "{} is not a complete four-pass run",
        root.display()
    );
    let report: Report =
        serde_json::from_slice(&std::fs::read(root.join(pass.name()).join("report.json"))?)?;
    ensure!(
        report.schema == 2 && report.pass == pass,
        "unexpected report schema or pass"
    );
    let cases = report.config.scenario.cases();
    let repetitions = if pass == Pass::Timing {
        report.config.repetitions as usize
    } else {
        1
    };
    ensure!(
        repetitions > 0 && report.runs.len() == cases.len() * repetitions,
        "incomplete scenario coverage"
    );
    for (index, run) in report.runs.iter().enumerate() {
        ensure!(
            run.scenario == cases[index / repetitions],
            "unexpected scenario order"
        );
        run.outcome.validate(run.scenario)?;
        ensure!(
            !run.phases.is_empty()
                && run
                    .phases
                    .iter()
                    .all(|phase| phase.elapsed_ms.is_finite() && phase.elapsed_ms >= 0.0),
            "invalid phase measurements"
        );
    }
    Ok(report)
}

fn compatible(first: &Report, second: &Report) -> Result<()> {
    ensure!(
        first.schema == second.schema && first.config == second.config && first.pass == second.pass,
        "incompatible workload configuration or report schema"
    );
    ensure!(
        first.harness_hash == second.harness_hash
            && first.rustc == second.rustc
            && first.optimization == second.optimization
            && first.rustflags == second.rustflags
            && first.host == second.host,
        "comparison requires the same harness, compiler, optimization level and host"
    );
    ensure!(
        first.runs.len() == second.runs.len(),
        "different repetition counts"
    );
    for (before, after) in first.runs.iter().zip(&second.runs) {
        ensure!(
            before.scenario == after.scenario && before.outcome == after.outcome,
            "navigation outputs differ for {:?}",
            before.scenario
        );
    }
    Ok(())
}

fn totals(report: &Report, scenario: Scenario) -> Vec<(f64, u64, u64)> {
    report
        .runs
        .iter()
        .filter(|run| run.scenario == scenario)
        .map(|run| {
            run.phases
                .iter()
                .fold((0.0, 0, 0), |(time, calls, bytes), phase| {
                    (
                        time + phase.elapsed_ms,
                        calls + phase.allocations.calls,
                        bytes + phase.allocations.requested_bytes,
                    )
                })
        })
        .collect()
}

fn timing(report: &Report, scenario: Scenario) -> (f64, f64, f64) {
    let mut times: Vec<_> = totals(report, scenario)
        .iter()
        .map(|value| value.0)
        .collect();
    times.sort_by(f64::total_cmp);
    (times[times.len() / 2], times[0], times[times.len() - 1])
}

/// Exact output equality is required before performance differences are presented as improvements.
pub fn compare(baseline: &Path, candidate: &Path) -> Result<String> {
    for pass in [Pass::Heap, Pass::Alloc, Pass::Cpu, Pass::Timing] {
        compatible(&load(baseline, pass)?, &load(candidate, pass)?)?;
    }
    let before_alloc = load(baseline, Pass::Alloc)?;
    let after_alloc = load(candidate, Pass::Alloc)?;
    let before_time = load(baseline, Pass::Timing)?;
    let after_time = load(candidate, Pass::Timing)?;
    let mut output = format!(
        "# Native simulation comparison\n\nCore `{}` → `{}`. All deterministic outputs match.\n\n",
        before_alloc.core_hash, after_alloc.core_hash
    );
    output.push_str("| Scenario | Allocation calls before → after | Requested bytes before → after | Median ms before → after | Time change |\n|---|---:|---:|---:|---:|\n");
    for scenario in before_alloc.config.scenario.cases() {
        let before = totals(&before_alloc, scenario)[0];
        let after = totals(&after_alloc, scenario)[0];
        let timing_before = timing(&before_time, scenario);
        let timing_after = timing(&after_time, scenario);
        writeln!(
            output,
            "| {} | {} → {} | {} → {} | {:.3} → {:.3} | {:+.1}% |",
            scenario.name(),
            before.1,
            after.1,
            before.2,
            after.2,
            timing_before.0,
            timing_after.0,
            (timing_after.0 / timing_before.0 - 1.0) * 100.0
        )?;
    }
    output.push_str("\nTiming ranges (min–max milliseconds, after one excluded warm-up):\n\n");
    for scenario in before_time.config.scenario.cases() {
        let before = timing(&before_time, scenario);
        let after = timing(&after_time, scenario);
        writeln!(
            output,
            "- {}: {:.3}–{:.3} → {:.3}–{:.3}",
            scenario.name(),
            before.1,
            before.2,
            after.1,
            after.2
        )?;
    }
    append_heap_comparison(&mut output, baseline, candidate, &before_alloc.config)?;
    output.push_str("\nHeap profiles report sampled live bytes. Allocation counts report churn, including full reallocation requests. Neither is RSS. CPU profiles are separate from timings; compare normalized sample percentages. Synthetic host results do not establish Android performance.\n");
    Ok(output)
}

/// Read the actual pprof samples rather than treating allocator statistics as sampled live bytes.
fn sampled_bytes(root: &Path, scenario: Scenario, phase: &str) -> Result<i64> {
    let path = root
        .join("heap")
        .join(scenario.name())
        .join(format!("session-0-{phase}.pb.gz"));
    let mut bytes = Vec::new();
    flate2::read::GzDecoder::new(std::fs::File::open(path)?).read_to_end(&mut bytes)?;
    let profile = Profile::decode(bytes.as_slice())?;
    let column = profile
        .sample_type
        .iter()
        .position(|sample| {
            usize::try_from(sample.ty)
                .ok()
                .and_then(|index| profile.string_table.get(index))
                .is_some_and(|name| name == "inuse_space")
        })
        .ok_or_else(|| anyhow::anyhow!("missing inuse_space sample type"))?;
    Ok(profile
        .sample
        .iter()
        .map(|sample| sample.value.get(column).copied().unwrap_or(0))
        .sum())
}

fn append_heap_comparison(
    output: &mut String,
    baseline: &Path,
    candidate: &Path,
    config: &Config,
) -> Result<()> {
    output.push_str("\nSampled live bytes from heap profiles (stochastic estimates, including harness overhead):\n\n| Scenario | Steady before → after | Teardown before → after |\n|---|---:|---:|\n");
    for scenario in config.scenario.cases() {
        writeln!(
            output,
            "| {} | {} → {} | {} → {} |",
            scenario.name(),
            sampled_bytes(baseline, scenario, "steady")?,
            sampled_bytes(candidate, scenario, "steady")?,
            sampled_bytes(baseline, scenario, "teardown")?,
            sampled_bytes(candidate, scenario, "teardown")?
        )?;
    }
    Ok(())
}

#[cfg(test)]
mod tests;
