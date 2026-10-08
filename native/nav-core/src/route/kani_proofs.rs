//! Precomputed immutable geometry for estimator proofs; route construction is outside their scope.
use super::{GeoPoint, RouteGeometry, SegmentBlock};

/// A straight northbound segment; the length is the normal constructor's haversine result.
pub(crate) fn straight_route() -> RouteGeometry {
    RouteGeometry {
        points: vec![
            GeoPoint {
                latitude_deg: 50.0,
                longitude_deg: 30.0,
            },
            GeoPoint {
                latitude_deg: 50.1,
                longitude_deg: 30.0,
            },
        ],
        cumulative_m: vec![0.0, 11_119.492_664_456_03],
        turns: Vec::new(),
        blocks: vec![SegmentBlock {
            first: 0,
            last: 0,
            min_latitude_deg: 50.0,
            max_latitude_deg: 50.1,
            min_longitude_deg: 30.0,
            max_longitude_deg: 30.0,
        }],
    }
}

/// Turn-policy proofs supply a bounded landmark table; geographic landmark construction is separate.
pub(crate) fn with_landmarks(turns: Vec<super::RouteTurn>) -> RouteGeometry {
    let mut route = straight_route();
    route.turns = turns;
    route
}
