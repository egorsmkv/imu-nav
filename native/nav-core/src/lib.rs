#![forbid(unsafe_code)]

//! Numerical core for route-constrained navigation.
//!
//! The filter tracks distance along the planned route and along-route velocity. Security gates
//! (spoofing classification, physical reachability and route ambiguity) must run before calling a
//! measurement update. The innovation gate here rejects ordinary statistical outliers; it is not
//! an anti-spoofing boundary by itself.

pub mod estimator;
pub mod network;
pub mod route;
pub mod speed;
pub mod trust;

const MIN_VARIANCE: f64 = 1.0e-9;
const SYMMETRY_TOLERANCE: f64 = 1.0e-8;

/// State covariance for `[distance_along_route_m, speed_mps]`.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Covariance2 {
    /// Along-route position variance, m².
    pub position: f64,
    /// Position-speed covariance, m²/s.
    pub position_speed: f64,
    /// Speed variance, (m/s)².
    pub speed: f64,
}

impl Covariance2 {
    /// Diagonal covariance from position and speed standard deviations.
    pub fn diagonal(position_sigma_m: f64, speed_sigma_mps: f64) -> Result<Self, FilterError> {
        validate_sigma(position_sigma_m)?;
        validate_sigma(speed_sigma_mps)?;
        Ok(Self {
            position: position_sigma_m * position_sigma_m,
            position_speed: 0.0,
            speed: speed_sigma_mps * speed_sigma_mps,
        })
    }

    fn is_valid(self) -> bool {
        if !self.position.is_finite()
            || !self.position_speed.is_finite()
            || !self.speed.is_finite()
            || self.position < 0.0
            || self.speed < 0.0
        {
            return false;
        }
        // A symmetric 2x2 matrix is positive semidefinite exactly when both diagonal entries and
        // its determinant are non-negative. Permit a tiny floating-point tolerance.
        let determinant = self.position * self.speed - self.position_speed * self.position_speed;
        determinant >= -SYMMETRY_TOLERANCE * (1.0 + self.position * self.speed)
    }

    fn floored(self) -> Self {
        Self {
            position: self.position.max(MIN_VARIANCE),
            position_speed: self.position_speed,
            speed: self.speed.max(MIN_VARIANCE),
        }
    }
}

/// Complete estimator output. Covariance is statistical; the safety radius also includes a
/// distance-proportional allowance for persistent model and speed-scale errors.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Estimate {
    pub position_m: f64,
    pub speed_mps: f64,
    pub covariance: Covariance2,
    pub systematic_drift_m: f64,
}

impl Estimate {
    pub fn position_sigma_m(self) -> f64 {
        self.covariance.position.sqrt()
    }

    pub fn speed_sigma_mps(self) -> f64 {
        self.covariance.speed.sqrt()
    }

    /// Conservative user-facing radius. `sigma_multiplier` is normally 2.0 for an approximate
    /// 95% random-error interval before adding the non-Gaussian systematic allowance.
    pub fn safety_radius_m(self, sigma_multiplier: f64) -> Result<f64, FilterError> {
        if !sigma_multiplier.is_finite() || sigma_multiplier < 0.0 {
            return Err(FilterError::InvalidSigma);
        }
        Ok(sigma_multiplier * self.position_sigma_m() + self.systematic_drift_m)
    }
}

/// Diagnostics returned for every measurement update.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct UpdateOutcome {
    pub accepted: bool,
    pub innovation: f64,
    pub innovation_variance: f64,
    pub normalized_innovation_squared: f64,
}

/// Invalid inputs never mutate filter state.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FilterError {
    NonFinite,
    InvalidTimeStep,
    InvalidSigma,
    InvalidGate,
    InvalidCovariance,
}

/// Fixed-size linear Kalman filter for `[s, v]`, where `s` is metres along the route.
#[derive(Clone, Debug)]
pub struct RouteFilter {
    estimate: Estimate,
}

impl RouteFilter {
    pub fn new(
        position_m: f64,
        speed_mps: f64,
        position_sigma_m: f64,
        speed_sigma_mps: f64,
        systematic_drift_m: f64,
    ) -> Result<Self, FilterError> {
        validate_finite(position_m)?;
        validate_finite(speed_mps)?;
        validate_non_negative(systematic_drift_m)?;
        let covariance = Covariance2::diagonal(position_sigma_m, speed_sigma_mps)?;
        Ok(Self {
            estimate: Estimate {
                position_m,
                speed_mps,
                covariance,
                systematic_drift_m,
            },
        })
    }

    pub fn estimate(&self) -> Estimate {
        self.estimate
    }

    /// Constant-velocity prediction with a piecewise-constant unknown acceleration.
    ///
    /// `acceleration_sigma_mps2` determines random process covariance. The separate
    /// `systematic_drift_per_m` grows a conservative allowance linearly with travelled distance,
    /// modelling lasting speed bias that ordinary white-noise covariance would underestimate.
    pub fn predict(
        &mut self,
        dt_s: f64,
        acceleration_sigma_mps2: f64,
        systematic_drift_per_m: f64,
    ) -> Result<(), FilterError> {
        if !dt_s.is_finite() || !(0.0..=5.0).contains(&dt_s) {
            return Err(FilterError::InvalidTimeStep);
        }
        validate_sigma(acceleration_sigma_mps2)?;
        if !systematic_drift_per_m.is_finite() || !(0.0..=1.0).contains(&systematic_drift_per_m) {
            return Err(FilterError::InvalidSigma);
        }

        let old = self.estimate;
        let distance = old.speed_mps * dt_s;
        let dt2 = dt_s * dt_s;
        let acceleration_variance = acceleration_sigma_mps2 * acceleration_sigma_mps2;
        let q00 = acceleration_variance * dt2 * dt2 / 4.0;
        let q01 = acceleration_variance * dt2 * dt_s / 2.0;
        let q11 = acceleration_variance * dt2;
        let p = old.covariance;
        let predicted_covariance = Covariance2 {
            position: p.position + dt_s * (2.0 * p.position_speed + dt_s * p.speed) + q00,
            position_speed: p.position_speed + dt_s * p.speed + q01,
            speed: p.speed + q11,
        }
        .floored();
        if !predicted_covariance.is_valid() {
            return Err(FilterError::InvalidCovariance);
        }
        self.estimate = Estimate {
            position_m: old.position_m + distance,
            speed_mps: old.speed_mps,
            covariance: predicted_covariance,
            systematic_drift_m: old.systematic_drift_m + distance.abs() * systematic_drift_per_m,
        };
        Ok(())
    }

    /// Update from a route-projected position measurement.
    pub fn update_position(
        &mut self,
        position_m: f64,
        sigma_m: f64,
        nis_gate: f64,
    ) -> Result<UpdateOutcome, FilterError> {
        self.update_scalar(position_m, sigma_m, nis_gate, 1.0, 0.0)
    }

    /// Update from a speed measurement such as trusted GNSS Doppler speed or scaled OBD speed.
    pub fn update_speed(
        &mut self,
        speed_mps: f64,
        sigma_mps: f64,
        nis_gate: f64,
    ) -> Result<UpdateOutcome, FilterError> {
        self.update_scalar(speed_mps, sigma_mps, nis_gate, 0.0, 1.0)
    }

    /// Install a trusted landmark or explicit route correction while retaining speed uncertainty.
    pub fn anchor_position(&mut self, position_m: f64, sigma_m: f64) -> Result<(), FilterError> {
        validate_finite(position_m)?;
        validate_sigma(sigma_m)?;
        self.estimate.position_m = position_m;
        self.estimate.covariance.position = (sigma_m * sigma_m).max(MIN_VARIANCE);
        self.estimate.covariance.position_speed = 0.0;
        self.estimate.systematic_drift_m = 0.0;
        Ok(())
    }

    /// An accepted independent position source removes distance accumulated since the previous
    /// anchor from the non-Gaussian safety allowance. The Kalman covariance is left untouched.
    pub fn reset_systematic_drift(&mut self) {
        self.estimate.systematic_drift_m = 0.0;
    }

    fn update_scalar(
        &mut self,
        measurement: f64,
        sigma: f64,
        nis_gate: f64,
        h0: f64,
        h1: f64,
    ) -> Result<UpdateOutcome, FilterError> {
        validate_finite(measurement)?;
        validate_sigma(sigma)?;
        if !nis_gate.is_finite() || nis_gate <= 0.0 {
            return Err(FilterError::InvalidGate);
        }

        let old = self.estimate;
        let p = old.covariance;
        let predicted_measurement = h0 * old.position_m + h1 * old.speed_mps;
        let innovation = measurement - predicted_measurement;
        let measurement_variance = (sigma * sigma).max(MIN_VARIANCE);
        let innovation_variance = h0 * (h0 * p.position + h1 * p.position_speed)
            + h1 * (h0 * p.position_speed + h1 * p.speed)
            + measurement_variance;
        if !innovation_variance.is_finite() || innovation_variance <= 0.0 {
            return Err(FilterError::InvalidCovariance);
        }
        let nis = innovation * innovation / innovation_variance;
        let outcome = UpdateOutcome {
            accepted: nis <= nis_gate,
            innovation,
            innovation_variance,
            normalized_innovation_squared: nis,
        };
        if !outcome.accepted {
            return Ok(outcome);
        }

        let k0 = (p.position * h0 + p.position_speed * h1) / innovation_variance;
        let k1 = (p.position_speed * h0 + p.speed * h1) / innovation_variance;

        // Joseph update: A*P*A' + K*R*K'. It preserves symmetry and positive semidefiniteness
        // much better than the shortened (I-KH)P form under floating-point rounding.
        let a00 = 1.0 - k0 * h0;
        let a01 = -k0 * h1;
        let a10 = -k1 * h0;
        let a11 = 1.0 - k1 * h1;
        let ap00 = a00 * p.position + a01 * p.position_speed;
        let ap01 = a00 * p.position_speed + a01 * p.speed;
        let ap10 = a10 * p.position + a11 * p.position_speed;
        let ap11 = a10 * p.position_speed + a11 * p.speed;
        let updated_covariance = Covariance2 {
            position: ap00 * a00 + ap01 * a01 + k0 * measurement_variance * k0,
            position_speed: 0.5
                * (ap00 * a10
                    + ap01 * a11
                    + k0 * measurement_variance * k1
                    + ap10 * a00
                    + ap11 * a01
                    + k1 * measurement_variance * k0),
            speed: ap10 * a10 + ap11 * a11 + k1 * measurement_variance * k1,
        }
        .floored();
        if !updated_covariance.is_valid() {
            return Err(FilterError::InvalidCovariance);
        }

        self.estimate = Estimate {
            position_m: old.position_m + k0 * innovation,
            speed_mps: old.speed_mps + k1 * innovation,
            covariance: updated_covariance,
            systematic_drift_m: old.systematic_drift_m,
        };
        Ok(outcome)
    }
}

fn validate_finite(value: f64) -> Result<(), FilterError> {
    if value.is_finite() {
        Ok(())
    } else {
        Err(FilterError::NonFinite)
    }
}

fn validate_non_negative(value: f64) -> Result<(), FilterError> {
    validate_finite(value)?;
    if value < 0.0 {
        Err(FilterError::InvalidSigma)
    } else {
        Ok(())
    }
}

fn validate_sigma(value: f64) -> Result<(), FilterError> {
    validate_non_negative(value)
}

#[cfg(test)]
mod tests {
    use super::*;

    const OPEN_GATE: f64 = 1.0e12;

    #[test]
    fn prediction_propagates_state_and_full_covariance() {
        let mut filter = RouteFilter::new(10.0, 4.0, 3.0, 2.0, 5.0).unwrap();
        filter.predict(0.5, 1.0, 0.08).unwrap();
        let estimate = filter.estimate();
        assert!((estimate.position_m - 12.0).abs() < 1.0e-12);
        assert!((estimate.covariance.position - 10.015625).abs() < 1.0e-12);
        assert!((estimate.covariance.position_speed - 2.0625).abs() < 1.0e-12);
        assert!((estimate.covariance.speed - 4.25).abs() < 1.0e-12);
        assert!((estimate.systematic_drift_m - 5.16).abs() < 1.0e-12);
    }

    #[test]
    fn position_update_is_symmetric_and_adjusts_correlated_speed() {
        let mut filter = RouteFilter::new(0.0, 10.0, 10.0, 2.0, 0.0).unwrap();
        filter.predict(1.0, 0.0, 0.0).unwrap();
        let before = filter.estimate();
        assert!(before.covariance.position_speed > 0.0);
        let outcome = filter.update_position(20.0, 2.0, OPEN_GATE).unwrap();
        let after = filter.estimate();
        assert!(outcome.accepted);
        assert!(after.position_m > before.position_m);
        assert!(after.speed_mps > before.speed_mps);
        assert!(after.covariance.is_valid());
    }

    #[test]
    fn speed_update_does_not_require_a_matrix_inverse() {
        let mut filter = RouteFilter::new(100.0, 8.0, 20.0, 4.0, 0.0).unwrap();
        let outcome = filter.update_speed(12.0, 1.0, OPEN_GATE).unwrap();
        assert!(outcome.accepted);
        assert!(filter.estimate().speed_mps > 11.0);
        assert!(filter.estimate().speed_sigma_mps() < 1.0);
    }

    #[test]
    fn innovation_gate_rejects_without_mutating_state() {
        let mut filter = RouteFilter::new(100.0, 8.0, 5.0, 2.0, 7.0).unwrap();
        let before = filter.estimate();
        let outcome = filter.update_position(1000.0, 5.0, 9.0).unwrap();
        assert!(!outcome.accepted);
        assert_eq!(before, filter.estimate());
    }

    #[test]
    fn anchor_resets_systematic_drift_but_not_speed_variance() {
        let mut filter = RouteFilter::new(0.0, 10.0, 5.0, 3.0, 0.0).unwrap();
        filter.predict(2.0, 1.0, 0.08).unwrap();
        let speed_variance = filter.estimate().covariance.speed;
        filter.anchor_position(21.0, 4.0).unwrap();
        let estimate = filter.estimate();
        assert_eq!(estimate.position_m, 21.0);
        assert_eq!(estimate.covariance.position, 16.0);
        assert_eq!(estimate.covariance.position_speed, 0.0);
        assert_eq!(estimate.covariance.speed, speed_variance);
        assert_eq!(estimate.systematic_drift_m, 0.0);
    }

    #[test]
    fn independent_fix_can_reset_only_the_systematic_allowance() {
        let mut filter = RouteFilter::new(0.0, 10.0, 5.0, 3.0, 0.0).unwrap();
        filter.predict(2.0, 1.0, 0.08).unwrap();
        let before = filter.estimate();
        assert!(before.systematic_drift_m > 0.0);
        filter.reset_systematic_drift();
        let after = filter.estimate();
        assert_eq!(after.systematic_drift_m, 0.0);
        assert_eq!(after.position_m, before.position_m);
        assert_eq!(after.speed_mps, before.speed_mps);
        assert_eq!(after.covariance, before.covariance);
    }

    #[test]
    fn invalid_inputs_do_not_mutate_state() {
        let mut filter = RouteFilter::new(0.0, 1.0, 1.0, 1.0, 0.0).unwrap();
        let before = filter.estimate();
        assert_eq!(
            filter.predict(f64::NAN, 1.0, 0.08),
            Err(FilterError::InvalidTimeStep)
        );
        assert_eq!(
            filter.update_position(0.0, -1.0, 9.0),
            Err(FilterError::InvalidSigma)
        );
        assert_eq!(before, filter.estimate());
    }

    #[test]
    fn long_sequence_preserves_valid_covariance() {
        let mut filter = RouteFilter::new(0.0, 15.0, 25.0, 5.0, 0.0).unwrap();
        for index in 1..=100_000 {
            filter.predict(0.5, 1.5, 0.02).unwrap();
            if index % 2 == 0 {
                let truth = 15.0 * index as f64 * 0.5;
                let noise = ((index % 11) as f64 - 5.0) * 0.4;
                filter.update_position(truth + noise, 8.0, 25.0).unwrap();
            }
            if index % 5 == 0 {
                filter.update_speed(15.0, 0.8, 25.0).unwrap();
            }
            assert!(filter.estimate().covariance.is_valid());
        }
    }
}
