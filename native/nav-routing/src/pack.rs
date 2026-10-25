//! Pack ownership shares one validated geometry mapping between all declared profiles.
use crate::{Result, shared::Shared, storage::Network};
use std::{collections::HashMap, path::Path, sync::Arc};
/// JNI retains this immutable owner until the last in-flight request finishes.
pub struct Pack {
    profiles: HashMap<String, Network>,
}
impl Pack {
    /// Open exactly the profiles declared in bounded metadata, rejecting older formats.
    /// # Errors
    /// Rejects malformed metadata, incompatible packs and invalid routing files.
    pub fn open(directory: &Path) -> Result<Self> {
        let file = std::fs::File::open(directory.join("pack.json"))?;
        if file.metadata()?.len() > 65_536 {
            return Err("routing metadata too large".into());
        }
        let metadata: serde_json::Value = serde_json::from_reader(file)?;
        if metadata["routingFormat"] != crate::FORMAT {
            return Err("new Rust routing pack required".into());
        }
        let profiles = metadata["profiles"].as_array().ok_or("missing profiles")?;
        if profiles.is_empty() || profiles.len() > 2 {
            return Err("invalid profile count".into());
        }
        let shared = Arc::new(Shared::open(&directory.join(crate::shared::FILE))?);
        let mut networks = HashMap::new();
        for value in profiles {
            let name = value.as_str().ok_or("invalid profile")?;
            if !matches!(name, "car" | "foot") || networks.contains_key(name) {
                return Err("invalid profile".into());
            }
            networks.insert(
                name.to_owned(),
                Network::with_shared(
                    &directory.join(format!("{name}.routing")),
                    Arc::clone(&shared),
                )?,
            );
        }
        Ok(Self { profiles: networks })
    }
    /// # Errors
    /// Returns an error if the requested profile was not declared in the pack.
    pub fn profile(&self, name: &str) -> Result<&Network> {
        self.profiles
            .get(name)
            .ok_or_else(|| "profile unavailable".into())
    }
}
