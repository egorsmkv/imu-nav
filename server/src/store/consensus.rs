//! Pure consensus calculation from the contributions read by the SQLite store.

use super::{
    CellKey, CellTower, Consensus, Contribution, MANUAL_DEVICE, Policy, SEED_DEVICE, SEED_VOTE,
    SEED_WEIGHT, distance_m,
};

/// Aggregate compatible contributions after rejecting location outliers.
#[cfg_attr(feature = "profiling", hotpath::measure)]
pub(super) fn calculate(
    key: &CellKey,
    contributions: &[Contribution],
    policy: &Policy,
) -> Consensus {
    if let Some(manual) = contributions
        .iter()
        .find(|item| item.device == MANUAL_DEVICE)
    {
        return Consensus {
            tower: CellTower {
                key: key.clone(),
                lat: manual.lat,
                lon: manual.lon,
                range_m: manual.range_m,
                samples: manual.samples,
            },
            devices: contributions
                .iter()
                .filter(|item| item.device != SEED_DEVICE && item.device != MANUAL_DEVICE)
                .count(),
            seeded: true,
            updated_s: contributions
                .iter()
                .map(|item| item.updated_s)
                .max()
                .unwrap_or_default(),
        };
    }
    let median_lat = weighted_median(
        contributions
            .iter()
            .map(|item| (item.lat, vote(item)))
            .collect(),
    );
    let median_lon = weighted_median(
        contributions
            .iter()
            .map(|item| (item.lon, vote(item)))
            .collect(),
    );
    let inliers = select_inliers(contributions, median_lat, median_lon, policy);
    let weight_sum: f64 = inliers.iter().map(|item| weight(item)).sum();
    let lat = inliers
        .iter()
        .map(|item| item.lat * weight(item))
        .sum::<f64>()
        / weight_sum;
    let lon = inliers
        .iter()
        .map(|item| item.lon * weight(item))
        .sum::<f64>()
        / weight_sum;
    let spread = inliers
        .iter()
        .map(|item| distance_m(lat, lon, item.lat, item.lon))
        .fold(0.0, f64::max);
    let mut ranges = inliers.iter().map(|item| item.range_m).collect::<Vec<_>>();
    ranges.sort_by(f64::total_cmp);

    Consensus {
        tower: CellTower {
            key: key.clone(),
            lat,
            lon,
            range_m: ranges[ranges.len() / 2].max(spread),
            samples: inliers
                .iter()
                .fold(0_i64, |total, item| total.saturating_add(item.samples))
                .min(1_000_000),
        },
        devices: inliers
            .iter()
            .filter(|item| item.device != SEED_DEVICE)
            .count(),
        seeded: inliers.iter().any(|item| item.device == SEED_DEVICE),
        updated_s: inliers
            .iter()
            .map(|item| item.updated_s)
            .max()
            .unwrap_or_default(),
    }
}

fn select_inliers<'a>(
    items: &'a [Contribution],
    median_lat: f64,
    median_lon: f64,
    policy: &Policy,
) -> Vec<&'a Contribution> {
    if items.len() >= 3 {
        let distances = items
            .iter()
            .map(|item| distance_m(median_lat, median_lon, item.lat, item.lon))
            .collect::<Vec<_>>();
        let mut sorted = distances.clone();
        sorted.sort_by(f64::total_cmp);
        let limit = (3.0 * sorted[sorted.len() / 2]).max(policy.outlier_min_m);
        let selected = items
            .iter()
            .zip(distances)
            .filter_map(|(item, distance)| (distance <= limit).then_some(item))
            .collect::<Vec<_>>();
        if !selected.is_empty() {
            return selected;
        }
    } else if items.len() == 2
        && distance_m(items[0].lat, items[0].lon, items[1].lat, items[1].lon)
            > policy.outlier_min_m * 2.0
    {
        return vec![if weight(&items[0]) >= weight(&items[1]) {
            &items[0]
        } else {
            &items[1]
        }];
    }
    items.iter().collect()
}

fn weight(item: &Contribution) -> f64 {
    if item.device == SEED_DEVICE {
        SEED_WEIGHT
    } else {
        f64::from(u32::try_from(item.samples).unwrap_or(u32::MAX))
    }
}

fn vote(item: &Contribution) -> f64 {
    if item.device == SEED_DEVICE {
        SEED_VOTE
    } else {
        1.0
    }
}

fn weighted_median(mut values: Vec<(f64, f64)>) -> f64 {
    values.sort_by(|left, right| left.0.total_cmp(&right.0));
    let half = values.iter().map(|item| item.1).sum::<f64>() / 2.0;
    let mut accumulated = 0.0;
    for (value, value_weight) in &values {
        accumulated += value_weight;
        if accumulated >= half {
            return *value;
        }
    }
    values.last().map_or(0.0, |item| item.0)
}
