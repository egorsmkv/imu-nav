# Native Rust crates

The `native/` Cargo workspace contains the navigation core, its Android JNI bridge and a host
simulator. Start with the [Rust core guide](../docs/core/readme.md) for a plain English crate
overview, topic guides, build and replay commands, profiling, verification and a newcomer glossary.

`nav-routing` contains the Rust road runtime and standalone OSM pack builder. Its builder-only
features are excluded from Android; see [routing](../docs/reference/routing_and_search.md).
