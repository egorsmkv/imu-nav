//! Speed fusion and network-fix regression used while satellite positioning is unavailable.

use crate::milliseconds_to_seconds;

const MAX_SPEED_MPS: f64 = 150.0 / 3.6;
const ROUTE_PRIOR_SIGMA_MPS: f64 = 6.0;
const GPS_SIGMA_MPS: f64 = 1.5;
const GPS_SIGMA_PER_SECOND: f64 = 0.08;
const MIN_NETWORK_SIGMA_MPS: f64 = 0.3;
const MAX_NETWORK_SIGMA_MPS: f64 = 4.0;
const MAX_SAMPLES: usize = 60;
const MIN_POINTS: usize = 4;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SpeedEstimate {
    pub speed_mps: f64,
    pub sigma_mps: f64,
    pub samples: usize,
    pub span_s: f64,
}

/// Inverse-variance fusion of fresh GNSS speed, a route prior and network regression.
#[must_use]
pub fn fuse_speed(
    last_gps_speed_mps: Option<f64>,
    gps_age_ms: i64,
    route_prior_mps: Option<f64>,
    network: Option<SpeedEstimate>,
) -> Option<SpeedEstimate> {
    let mut weighted_sum = 0.0;
    let mut weight_sum = 0.0;
    let mut count = 0;
    let mut add = |speed: f64, sigma: f64| {
        if speed.is_finite() && sigma.is_finite() && sigma > 0.0 {
            let weight = 1.0 / (sigma * sigma);
            weighted_sum += speed * weight;
            weight_sum += weight;
            count += 1;
        }
    };
    if let Some(speed) = last_gps_speed_mps {
        add(
            speed,
            GPS_SIGMA_MPS + milliseconds_to_seconds(gps_age_ms.max(0)) * GPS_SIGMA_PER_SECOND,
        );
    }
    if let Some(speed) = route_prior_mps {
        add(speed, ROUTE_PRIOR_SIGMA_MPS);
    }
    if let Some(estimate) = network {
        add(
            estimate.speed_mps,
            estimate.sigma_mps.max(MIN_NETWORK_SIGMA_MPS),
        );
    }
    if weight_sum == 0.0 {
        return None;
    }
    Some(SpeedEstimate {
        speed_mps: (weighted_sum / weight_sum).clamp(0.0, MAX_SPEED_MPS),
        sigma_mps: (1.0 / weight_sum).sqrt(),
        samples: count,
        span_s: network.map_or(0.0, |estimate| estimate.span_s),
    })
}

#[derive(Clone, Copy, Debug)]
struct Sample {
    elapsed_ms: i64,
    position_m: f64,
    accuracy_m: f64,
}

#[derive(Clone, Copy, Debug)]
struct Line {
    intercept_m: f64,
    slope_mps: f64,
    slope_sigma_mps: f64,
    origin_ms: i64,
}

impl Line {
    fn position_at(self, elapsed_ms: i64) -> f64 {
        self.intercept_m
            + self.slope_mps * milliseconds_to_seconds(elapsed_ms.saturating_sub(self.origin_ms))
    }
}

#[derive(Clone, Debug, Default)]
pub struct NetworkSpeedEstimator {
    samples: Vec<Sample>,
}

impl NetworkSpeedEstimator {
    pub fn clear(&mut self) {
        self.samples.clear();
    }

    /// Invalid, duplicate-time and out-of-order samples are ignored.
    pub fn add(&mut self, position_m: f64, accuracy_m: f64, elapsed_ms: i64) {
        if !position_m.is_finite()
            || !accuracy_m.is_finite()
            || accuracy_m < 0.0
            || self
                .samples
                .last()
                .is_some_and(|sample| elapsed_ms <= sample.elapsed_ms)
        {
            return;
        }
        self.samples.push(Sample {
            elapsed_ms,
            position_m,
            accuracy_m,
        });
        if self.samples.len() > MAX_SAMPLES {
            self.samples.remove(0);
        }
    }

    #[must_use]
    pub fn estimate(&self, now_ms: i64) -> Option<SpeedEstimate> {
        self.estimate_window(now_ms, 30_000, 15.0)
            .or_else(|| self.estimate_window(now_ms, 40_000, 20.0))
            .or_else(|| self.strict_estimate(now_ms))
    }

    #[must_use]
    pub fn strict_estimate(&self, now_ms: i64) -> Option<SpeedEstimate> {
        self.estimate_window(now_ms, 90_000, 30.0)
    }

    fn estimate_window(
        &self,
        now_ms: i64,
        window_ms: i64,
        minimum_span_s: f64,
    ) -> Option<SpeedEstimate> {
        let mut samples: Vec<Sample> = self
            .samples
            .iter()
            .copied()
            .filter(|sample| now_ms.saturating_sub(sample.elapsed_ms) <= window_ms)
            .collect();
        if samples.len() < MIN_POINTS {
            return None;
        }
        let mut line = fit(&samples)?;
        let inliers: Vec<Sample> = samples
            .iter()
            .copied()
            .filter(|sample| {
                (sample.position_m - line.position_at(sample.elapsed_ms)).abs()
                    <= 250.0_f64.max(weight_accuracy(*sample) * 3.0)
            })
            .collect();
        if inliers.len() < samples.len() && inliers.len() >= MIN_POINTS {
            line = fit(&inliers)?;
            samples = inliers;
        }
        let span_s = milliseconds_to_seconds(
            samples
                .last()?
                .elapsed_ms
                .saturating_sub(samples.first()?.elapsed_ms),
        );
        if span_s < minimum_span_s {
            return None;
        }
        let sigma_mps = line.slope_sigma_mps * 1.5;
        if sigma_mps > MAX_NETWORK_SIGMA_MPS {
            return None;
        }
        Some(SpeedEstimate {
            speed_mps: line.slope_mps.max(0.0),
            sigma_mps,
            samples: samples.len(),
            span_s,
        })
    }
}

fn weight_accuracy(sample: Sample) -> f64 {
    sample.accuracy_m.max(20.0)
}

fn fit(samples: &[Sample]) -> Option<Line> {
    if samples.len() < 2 {
        return None;
    }
    let origin_ms = samples.first()?.elapsed_ms;
    let weight_sum: f64 = samples
        .iter()
        .map(|sample| 1.0 / weight_accuracy(*sample).powi(2))
        .sum();
    let seconds =
        |sample: Sample| milliseconds_to_seconds(sample.elapsed_ms.saturating_sub(origin_ms));
    let mean_time = samples
        .iter()
        .map(|sample| seconds(*sample) / weight_accuracy(*sample).powi(2))
        .sum::<f64>()
        / weight_sum;
    let mean_position = samples
        .iter()
        .map(|sample| sample.position_m / weight_accuracy(*sample).powi(2))
        .sum::<f64>()
        / weight_sum;
    let time_variance = samples
        .iter()
        .map(|sample| (seconds(*sample) - mean_time).powi(2) / weight_accuracy(*sample).powi(2))
        .sum::<f64>();
    if time_variance <= 1.0e-9 {
        return None;
    }
    let covariance = samples
        .iter()
        .map(|sample| {
            (seconds(*sample) - mean_time) * (sample.position_m - mean_position)
                / weight_accuracy(*sample).powi(2)
        })
        .sum::<f64>();
    let slope_mps = covariance / time_variance;
    Some(Line {
        intercept_m: mean_position - mean_time * slope_mps,
        slope_mps,
        slope_sigma_mps: (1.0 / time_variance).sqrt(),
        origin_ms,
    })
}

#[cfg(test)]
mod tests;

#[cfg(kani)]
pub(crate) mod kani_proofs;
