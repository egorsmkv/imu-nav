# Native Rust crates

The `native/` Cargo workspace contains the navigation core, its Android JNI bridge, a host
simulator, address search (`nav-search`), offline routing (`nav-routing`) and the Rust port of
AndroidX Graphics Path's JNI library ([`graphics-path`](graphics-path/README.md)). Start with the [Rust core guide](../docs/core/readme.md) for a plain English crate
overview, topic guides, build and replay commands, profiling, verification and a newcomer glossary.

`nav-routing` contains the Rust road runtime and standalone OSM pack builder. Its builder-only
features are excluded from Android; see [routing](../docs/reference/routing_and_search.md).

The routing builder emits a combined `rust-ch-2` pack. To publish smaller mode-specific
downloads without repeating OSM import or CH preparation, run:

```bash
native/target/release/subset-routing graph-ukraine graph-ukraine-car car
native/target/release/subset-routing graph-ukraine graph-ukraine-walk walk
```

Each command writes a new directory and ZIP, trimming shared geometry and names while copying
Tantivy unchanged. The app supports one installed routing pack; use the combined pack for both modes.
