//! Explicit car/walking policy. Direction-specific OSM access overrides general access.
// Coordinates, grid cells, speeds and file indices have explicit bounds in the importer/loader.
// Integer-to-float conversions here stay below the exact-integer range of f64.
#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss
)]
use super::osm::Tags;
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    Car,
    Foot,
}
impl Mode {
    #[must_use]
    pub fn name(self) -> &'static str {
        if self == Self::Car { "car" } else { "foot" }
    }
}
fn tag<'a>(tags: &'a Tags, key: &str) -> &'a str {
    tags.get(key).map_or("", String::as_str)
}
fn denied(value: &str) -> bool {
    matches!(value, "no" | "private" | "agricultural" | "forestry")
}
fn allowed(value: &str) -> bool {
    matches!(
        value,
        "yes" | "permissive" | "designated" | "destination" | "customers" | "delivery"
    )
}
#[must_use]
pub fn road(tags: &Tags) -> bool {
    tags.contains_key("highway") || tag(tags, "route") == "ferry"
}
/// Returns average travel speed, explicit posted limit, and preference multiplier.
#[must_use]
pub fn access(tags: &Tags, mode: Mode, reverse: bool) -> Option<(f32, u16, f64)> {
    let highway = tag(tags, "highway");
    let ferry = tag(tags, "route") == "ferry";
    if matches!(highway, "construction" | "proposed" | "raceway")
        || tag(tags, "area") == "yes"
        || tag(tags, "status") == "impassable"
    {
        return None;
    }
    // Time-dependent closures are conservatively closed in a static offline pack.
    if tags.iter().any(|(key, value)| {
        key.ends_with(":conditional")
            && (key.starts_with("access")
                || key.starts_with("vehicle") && mode == Mode::Car
                || key.starts_with("motor_vehicle") && mode == Mode::Car
                || key.starts_with("motorcar") && mode == Mode::Car
                || key.starts_with("foot") && mode == Mode::Foot)
            && value
                .split(';')
                .any(|clause| clause.split('@').next().is_some_and(|v| denied(v.trim())))
    }) {
        return None;
    }
    let direction = if reverse { "backward" } else { "forward" };
    let keys: &[&str] = if mode == Mode::Car {
        &["motorcar", "motor_vehicle", "vehicle", "access"]
    } else {
        &["foot", "access"]
    };
    let explicit = access_value(tags, keys, direction);
    // General access=yes does not authorize cars on footways or pedestrians on motorroads.
    let mode_access = access_value(tags, &keys[..keys.len() - 1], direction);
    if mode == Mode::Foot && tag(tags, "motorroad") == "yes" && !allowed(mode_access) {
        return None;
    }
    if denied(explicit) {
        return None;
    }
    let base = default_speed(highway, ferry, mode_access, mode)?;
    if mode == Mode::Foot
        && matches!(
            tag(tags, "sac_scale"),
            "demanding_mountain_hiking"
                | "alpine_hiking"
                | "demanding_alpine_hiking"
                | "difficult_alpine_hiking"
        )
    {
        return None;
    }
    if !direction_allowed(tags, mode, reverse, direction, highway) {
        return None;
    }
    let limit = tags
        .get(&format!("maxspeed:{direction}"))
        .or_else(|| tags.get("maxspeed"))
        .and_then(|value| speed(value));
    let mut speed = base;
    if mode == Mode::Car {
        if let Some(limit) = limit {
            speed = f64::min(speed, limit * 0.9);
        }
        if matches!(
            tag(tags, "surface"),
            "gravel" | "unpaved" | "ground" | "dirt" | "sand" | "mud" | "cobblestone"
        ) {
            speed = speed.min(30.);
        }
        if highway == "track" {
            speed = match tag(tags, "tracktype") {
                "grade1" => 20.,
                "grade2" => 15.,
                "grade3" => 10.,
                "grade4" | "grade5" => 5.,
                _ => speed,
            }
        }
    }
    let priority = if mode == Mode::Foot {
        match highway {
            "footway" | "path" | "pedestrian" => 1.2,
            "primary" | "secondary" => 0.7,
            _ => 1.,
        }
    } else {
        1.
    };
    Some((
        (speed / 3.6) as f32,
        limit.map_or(0, |v| v.round().clamp(1., 300.) as u16),
        priority,
    ))
}
/// Resolve the most specific vehicle tag before falling back to its general access tag.
fn access_value<'a>(tags: &'a Tags, keys: &[&str], direction: &str) -> &'a str {
    keys.iter()
        .find_map(|key| {
            tags.get(&format!("{key}:{direction}"))
                .or_else(|| tags.get(*key))
        })
        .map_or("", String::as_str)
}
fn speed(value: &str) -> Option<f64> {
    let first = value.split(';').next()?.trim();
    if first == "walk" {
        return Some(5.);
    }
    let number = first.split_whitespace().next()?.parse::<f64>().ok()?;
    let result = if first.contains("mph") {
        number * 1.609_344
    } else {
        number
    };
    (result.is_finite() && result > 0. && result <= 300.).then_some(result)
}
#[must_use]
pub fn barrier(tags: &Tags, mode: Mode) -> bool {
    let restriction = if mode == Mode::Car {
        ["motorcar", "motor_vehicle", "vehicle", "access"]
    } else {
        ["foot", "foot", "foot", "access"]
    }
    .into_iter()
    .find_map(|key| tags.get(key));
    if let Some(value) = restriction {
        if allowed(value) {
            return false;
        }
        if denied(value) {
            return true;
        }
    }
    if mode == Mode::Car {
        matches!(
            tag(tags, "barrier"),
            "bollard"
                | "block"
                | "bus_trap"
                | "sump_buster"
                | "cycle_barrier"
                | "stile"
                | "turnstile"
                | "kissing_gate"
                | "fence"
                | "wall"
        )
    } else {
        matches!(tag(tags, "barrier"), "fence" | "wall")
    }
}

fn default_speed(highway: &str, ferry: bool, explicit: &str, mode: Mode) -> Option<f64> {
    Some(if mode == Mode::Car {
        match highway {
            "motorway" => 100.,
            "motorway_link" | "secondary" => 60.,
            "trunk" => 70.,
            "trunk_link" | "primary_link" | "secondary_link" | "tertiary" => 50.,
            "primary" => 65.,
            "tertiary_link" => 40.,
            "unclassified" | "residential" => 30.,
            "living_street" => 5.,
            "service" | "road" => 20.,
            "track" => 15.,
            _ if ferry => 10.,
            _ if allowed(explicit) => 10.,
            _ => return None,
        }
    } else {
        match highway {
            "motorway" | "motorway_link" | "trunk" | "trunk_link" if !allowed(explicit) => {
                return None;
            }
            "steps" => 2.,
            "footway" | "path" | "pedestrian" | "living_street" | "residential" | "service"
            | "track" | "unclassified" | "tertiary" | "secondary" | "primary" | "cycleway"
            | "bridleway" | "road" | "trunk" | "trunk_link" | "motorway" | "motorway_link" => 5.,
            _ if ferry => 10.,
            _ => return None,
        }
    })
}

fn direction_allowed(
    tags: &Tags,
    mode: Mode,
    reverse: bool,
    direction: &str,
    highway: &str,
) -> bool {
    let specific = if mode == Mode::Car {
        [
            format!("motorcar:{direction}"),
            format!("motor_vehicle:{direction}"),
            format!("vehicle:{direction}"),
        ]
    } else {
        [
            format!("foot:{direction}"),
            format!("foot:{direction}"),
            format!("foot:{direction}"),
        ]
    };
    let directional = specific.iter().find_map(|key| tags.get(key));
    if directional.is_some_and(|value| denied(value)) {
        return false;
    }
    let oneway = if mode == Mode::Car {
        tags.get("oneway:motorcar")
            .or_else(|| tags.get("oneway:motor_vehicle"))
            .or_else(|| tags.get("oneway"))
            .map_or(
                if tag(tags, "junction") == "roundabout" || highway == "motorway" {
                    "yes"
                } else {
                    ""
                },
                String::as_str,
            )
    } else {
        tag(tags, "oneway:foot")
    };
    if mode == Mode::Car && matches!(oneway, "reversible" | "alternating") {
        return false;
    }
    if !directional.is_some_and(|value| allowed(value))
        && ((matches!(oneway, "yes" | "1" | "true") && reverse) || (oneway == "-1" && !reverse))
    {
        return false;
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn general_access_does_not_override_mode_defaults() {
        let mut tags = Tags::from([
            ("highway".into(), "footway".into()),
            ("access".into(), "yes".into()),
        ]);
        assert!(access(&tags, Mode::Car, false).is_none());
        tags.insert("motor_vehicle".into(), "yes".into());
        assert!(access(&tags, Mode::Car, false).is_some());
        tags.insert("highway".into(), "motorway".into());
        assert!(access(&tags, Mode::Foot, false).is_none());
        tags.insert("foot".into(), "yes".into());
        assert!(access(&tags, Mode::Foot, false).is_some());
    }
    #[test]
    fn directional_access_and_motorroads() {
        let mut tags = Tags::from([
            ("highway".into(), "residential".into()),
            ("motor_vehicle".into(), "no".into()),
            ("motor_vehicle:forward".into(), "yes".into()),
        ]);
        assert!(access(&tags, Mode::Car, false).is_some());
        assert!(access(&tags, Mode::Car, true).is_none());
        tags.insert("motorroad".into(), "yes".into());
        assert!(access(&tags, Mode::Foot, false).is_none());
        tags.insert("foot".into(), "yes".into());
        assert!(access(&tags, Mode::Foot, false).is_some());
    }
}
