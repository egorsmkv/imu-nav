# Desktop Rust copy and allocation audit

Scope: `desktop/app` and `desktop/protocol`, reviewed on 2026-10-25 with Rust/Clippy 1.99.0
on Windows x86-64. The protocol crate is also used by the server. This source review focuses
on network ingestion, immutable snapshots, drawing, tile loading and serialization; it is not
an exhaustive allocator trace or a performance comparison. IMU Nav is a research prototype,
not a safety system.

The repository-root `rustfmt.toml` already applies to both desktop crates. No additional
formatter configuration, dependency upgrade or wire-format change is needed. The desktop CI
Clippy command now also enables `redundant_clone`, `needless_collect` and
`unnecessary_to_owned`, with warnings treated as errors.

## Removed unnecessary buffers and copies

| Location | Change | Remaining work |
| --- | --- | --- |
| `protocol/src/lib.rs`, `parse_rmc` | Twelve required fields are borrowed into a fixed stack array, replacing `Vec<&str>`. The optional thirteenth mode field is checked through the split iterator. | Numeric parsing and checksum validation remain. Required fields, optional mode `N`, extra trailing fields and the 82-byte limit keep their previous acceptance rules. This removes the explicit field-table heap allocation; it is not a global guarantee for all protocol operations. |
| `app/src/online.rs`, `cache_policy` | Repeated scans clone a borrowed split iterator rather than collecting cache directives into a vector. | Owned ETag/Last-Modified metadata strings remain. Iterator clones copy cursor state, not the header payload. |
| `app/src/maps.rs`, `read_tile` | `Row::get_ref` borrows the SQLite BLOB, and image decoding finishes inside the row callback. The temporary owned compressed-tile `Vec<u8>` is removed. | SQLite, decompression, decoded pixels, egui conversion and GPU upload can still allocate or copy. The BLOB reference cannot escape the callback. Decoding holds the statement on the existing single map worker, with no new UI-thread work. |
| `app/src/maps.rs`, `decode_raster` | `into_rgba8` consumes the decoded image, reusing an RGBA8 buffer when that is already its representation. | Other image formats still require conversion. `ColorImage::from_rgba_unmultiplied` creates egui-owned pixels. PNG/JPEG, size and decoder allocation limits remain enforced. |
| `app/src/smoke.rs`, `from_arguments` | Read the two expected arguments from an iterator instead of collecting all process arguments into a vector. | OS argument strings and the owned screenshot path remain. This is startup-only work. |

Tests cover RMC field counts and optional mode handling, shared phone fixtures, cache directive
case/whitespace/precedence, SQLite BLOB type errors, corrupt/oversized tiles and decoded PNG
pixels. Existing tests also cover private endpoints, reconnect/cancellation, atomic route
replacement, stale-state behavior, read-only maps and bounded files/frames.

## Remaining allocation sites and ownership constraints

| Location | Finding | Priority / constraint |
| --- | --- | --- |
| `app/src/connection.rs`, `Receiver::position` | Every distinct position collects the complete current trail into a new `Vec`, then wraps it in an `Arc`. At the 10,000-point cap this copies about 160,000 coordinate bytes per update. | Highest-priority profiling target. Consider coalescing publications or persistent/chunked storage; retained snapshots must remain immutable. `Arc::make_mut` can still copy when the UI holds a snapshot. |
| `app/src/connection.rs`, `Snapshot` / `publish` | Snapshot clones share route, guidance and trail via `Arc`; they do not deep-copy those buffers. The optional `Fix` copies scalar fields. | Preserve short mutex ownership and worker cancellation. Replacing `Arc` with a borrowed UI reference would require a different concurrency/lifetime design. |
| `protocol/src/lib.rs`, `Lines::push` | Fragments are copied into one bounded partial buffer. Completed line ownership is transferred with `mem::take`, then a fresh partial buffer grows for the next line; the returned outer vector also allocates. | A callback/borrowed fast path for complete lines could remove buffers, but must preserve split delimiters, frame limits and error behavior. The present API returns independently owned lines. |
| `protocol/src/lib.rs`, `Route::frames` | The full route is temporarily serialized to check its size; the start summary and each chunk's geometry are cloned into owned `Frame` values before serialization. Encoded frame vectors are retained for the caller. | Borrowed serialization views and a counting writer are possible follow-ups. Keep exact wire compatibility, newline-inclusive frame limits and the full route limit. Chunking alone is not zero-copy. |
| `protocol/src/lib.rs`, `RouteAssembly::accept` | Each chunk and completed route are serialized to temporary vectors for byte-limit checks. Geometry is transferred into the pending route, whose vector can grow. JSON parsing owns strings and chunk vectors. | Profile peak memory and churn separately. Enforcing limits before exposing a partial route is essential; avoid changing rejection semantics merely to remove buffers. |
| `app/src/viewer.rs`, `draw_navigation` | Route/trail coordinates are borrowed and projected one at a time, with no full projected-path vector. A marker polygon owns a small vector, and egui's painter/tessellator stores shapes. UI text uses formatted strings. | Rendering is not zero-allocation. The route/trail scan is linear per repaint; measure CPU and draw-command storage before optimizing the four-point marker. |
| `app/src/maps.rs` | Tile requests/results use bounded queues, and the texture cache is capped at 128 entries. `TextureHandle::clone` shares a texture handle, rather than copying raster pixels. Metadata/path strings and the small bounds vector allocate during setup. | Decoding and texture upload are distinct memory boundaries. Do not confuse a borrowed compressed BLOB or shared GPU handle with allocation-free image rendering. |
| `app/src/online.rs`, `fetch` | Chained template replacements and tile/cache-key formatting allocate strings. Disk/network bodies are bounded owned buffers; decoded pixels and HTTP metadata also allocate. | Work runs on the map worker after explicit Online selection. Any URL builder rewrite must retain repeated placeholder behavior and HTTPS/privacy rules. No real tile-network requests were needed for the source review. |
| `app/src/smoke.rs`, screenshot saving | The screenshot event shares its image through an `Arc`; converting pixels to PNG input creates an owned byte vector on a worker. | This is opt-in synthetic verification, not a navigation hot path. Keep live user data and saved connection preferences out of smoke captures. |

## Reproduce verification

Run from the repository root:

```powershell
cargo fmt --manifest-path desktop/Cargo.toml --all --check
cargo clippy --manifest-path desktop/Cargo.toml --workspace --all-targets --all-features --locked -- -W clippy::pedantic -W clippy::redundant_clone -W clippy::needless_collect -W clippy::unnecessary_to_owned -D warnings
cargo test --manifest-path desktop/Cargo.toml --workspace --all-targets --all-features --locked
cargo build --manifest-path desktop/Cargo.toml -p imu-nav-desktop --release --locked
desktop/target/release/imu-nav-desktop.exe --smoke-test captures/desktop-audit.png
```

The desktop/protocol test run passes 17 tests (12 app, 5 protocol), including the new boundary
cases. Clippy and rustfmt pass. The release executable builds successfully; its synthetic
smoke run exits with code 0 and produces a screenshot that was visually checked for navigation
text, route, marker and destination. It uses Offline mode with no selected raster map, makes
no map-network requests and reads/saves no connection preferences.

`gradlew.bat check` also passes, including the server consumers of the protocol crate,
Android Lint, detekt and Kotlin/JNI tests. PostgreSQL scenarios still require a disposable
`TEST_POSTGRES_URL` and were skipped when it was absent. The shared native/server [allocation audit](rust-allocation-audit.md)
explains the distinction between borrowing, ownership transfer and zero-allocation.
No measured speedup, global zero-allocation guarantee or real phone/VPN validation is claimed.
