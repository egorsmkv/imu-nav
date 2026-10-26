//! Distinct index domains; IDs are checked against the owning network before use.
//!
//! ```compile_fail
//! use imu_nav_routing::ids::{StateId, VertexId};
//! let vertex: VertexId = StateId::new(0);
//! ```
macro_rules! id {
    ($name:ident, $raw:ty, $doc:literal) => {
        #[doc = $doc]
        #[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, serde::Serialize)]
        #[serde(transparent)]
        pub struct $name($raw);
        impl $name {
            /// Decode an index; validity still depends on the owning network's bounds.
            #[must_use]
            pub const fn new(value: $raw) -> Self {
                Self(value)
            }
            /// Encode at a storage or explicitly checked graph-domain conversion boundary.
            #[must_use]
            pub const fn get(self) -> $raw {
                self.0
            }
        }
    };
}
id!(StateId, u32, "Directed road state identity.");
id!(VertexId, u32, "Contraction hierarchy vertex identity.");
id!(NodeId, u32, "Physical road junction identity.");
id!(GeometryId, u32, "Shared geometry dictionary identity.");
id!(NameId, u32, "Shared name dictionary identity.");
id!(
    ArcIndex,
    usize,
    "Arc position within one direction's CSR records."
);

/// Search direction also selects one of the two upward CSR graphs.
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum Direction {
    Forward,
    Backward,
}

impl Direction {
    pub const ALL: [Self; 2] = [Self::Forward, Self::Backward];
    #[must_use]
    pub const fn index(self) -> usize {
        match self {
            Self::Forward => 0,
            Self::Backward => 1,
        }
    }
    #[must_use]
    pub const fn opposite(self) -> Self {
        match self {
            Self::Forward => Self::Backward,
            Self::Backward => Self::Forward,
        }
    }
}
