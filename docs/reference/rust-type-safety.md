# Rust type safety

[Documentation index](../../README.md) · [Native input contracts](../core/input-contracts.md)

IMU Nav is a research prototype, not a safety system. Checked types prevent particular programming
mistakes; they do not establish that a position is accurate or that a navigation correction is safe.

## Quantities and state

`native/nav-types` contains `imu-nav-types`, shared by the native, desktop and server workspaces.
It has no mandatory third-party dependencies; its optional `serde` feature serializes checked
values as the existing scalar numbers and strings, validating them on deserialization.

| Type | Intrinsic domain |
| --- | --- |
| `RoutePosition`, `Velocity` | Finite signed route metres and metres/second respectively |
| `Distance`, `Speed`, `SpeedKmh` | Finite non-negative quantities in their named units |
| `PositionSigma`, `SpeedSigma` | Finite non-negative standard deviations; zero remains valid |
| `Latitude`, `Longitude` | Inclusive −90…90 and −180…180 degrees |
| `ElapsedMs` | Non-negative signed-64-bit monotonic milliseconds |
| `MonotonicMs` | Signed-64-bit timestamps for the standalone speed regression |
| `UnixMs` | Signed-64-bit wall-clock milliseconds; protocol UTC bounds are checked separately |
| `DurationMs`, `Seconds` | Non-negative durations, distinct from timestamps |

Conversions between kilometres/hour and metres/second, and milliseconds and seconds, are explicit.
Checked duration arithmetic rejects overflow. There are no generic arithmetic or implicit numeric
conversion operators that would allow distances, velocities or clock domains to be interchanged.

`RouteFilter::new` accepts checked quantities; `from_raw` is the explicit scalar ingress for JNI
and malformed-input tests. Live filter state is an immutable `FilterEstimate`; `estimate()` exports
the scalar DTO consumed by the existing numerical kernels and JNI arrays. `Covariance2::new`
checks finite positive-semidefinite covariance, and public getters replace public mutation.
`TrustConfigValues` is editable input; conversion into immutable `TrustConfig` validates finiteness,
signs and relationships between its limits. Navigation's missing GPS/OBD watermarks are
`Option<ElapsedMs>` rather than negative timestamps. GPS DTOs retain malformed inputs so the trust
classifier can report BAD. Arithmetic order and conservative correction gates remain unchanged.

Projection results expose `position()` and `offset()` as different checked quantities. Local and
global searches now both reject out-of-world coordinates. Scalar scratch records remain inside the
geometry kernels, including their temporary infinity sentinels. Network anchors retain checked
position, uncertainty and duration. Speed regression offers typed `add` and explicit untrusted
`add_raw`; its signed-time contract and ignored-sample behavior are preserved.

## Routing, search and native ownership

Routing APIs distinguish `StateId`, `VertexId`, `NodeId`, `GeometryId`, `NameId` and `ArcIndex`.
They prevent cross-domain arguments; owning-network bounds checks are still necessary. A
`Direction` selects a CSR graph. Absent shortcut vertices use `Option<VertexId>`, translating the
existing `u32::MAX` only in storage code. `Profile` represents the existing lowercase `car`/`foot`
metadata vocabulary. Routing format `rust-ch-2`, binary record sizes and dictionary encodings are
unchanged; packs need no rebuild for these changes.

Search row kinds use `SearchKind`, and nonzero `SearchId` values replace document/reference
primitives. Imported rows, stored rows and query reference points are checked against geographic
bounds. Addresses require a nonzero street reference. Existing Tantivy field encodings, including
zero as the storage-only absent-reference marker, are unchanged.

JNI handles remain opaque positive Kotlin `Long` values. Their upper bits carry a distinct object
kind and their remaining 55 bits carry a counter that never wraps or reuses a value. Typed registry
keys separate filters, routes, trust classifiers, network trackers, estimators, routing/search packs,
requests and graphics iterators. Exhaustion fails rather than resurrecting a stale handle. Active
routing/search requests retain their `Arc` owners after close; cancellation remains request-scoped.
Routing and search cancellation use distinct error types instead of comparing error messages.
JNI signatures, array orders, status codes, exception classes and sanitized error text remain stable.

Graphics JNI copies points, verbs and conic weights while the source Skia path is live during
creation, then retains owned buffers in a typed registry. No foreign slices escape that call.
The platform-layout read remains unsafe and requires an unchanged live source path during copying.
Host callers can still borrow slices. A conic snapshot fixture adds three allocations totalling
31 buffer bytes compared with borrowed creation; both traversal paths allocate zero bytes.
This measures buffers, not registry growth or total Android creation latency. C++ bit-pattern
fixtures and Android native method registration remain unchanged.

## Server and desktop boundaries

Server account and cell identities validate during JSON, CSV and SQL conversion: account IDs are
positive, MCC is 1–999, MNC is 0–999, and area/CID are non-negative, including zero. Public device IDs
retain the 8–64 ASCII alphanumeric/hyphen contract; internal account-prefixed keys remain separate.
Invalid stored identities and consensus physical fields produce conversion errors without repairing
or deleting database rows. SQL schemas and serialized scalar representations are unchanged.

Tower coordinate/range/sample validation is separate from publication policy. Active policies are
immutable `ValidatedPolicy` values, and replacement policies validate before transaction activation.
CSV syntax errors remain skipped; numeric rows with invalid identities still count as rejected
uploads and toward upload row limits. This preserves upload rejection accounting.

Desktop guidance uses the existing uppercase vocabularies as enums for travel mode, source, GPS
state and verdict. Sequences and revisions are nonzero IDs. Frame decoding **and encoding** validate
contents; unsupported versions remain a distinct viewer status. Elapsed time and age must fit their
non-negative signed-64-bit domains, and UTC retains its existing bounds. Route ordering, byte/point
limits and shared phone fixtures remain enforced. Maneuver kind/modifier/name text stays open within
its existing size limits. Navigation protocol version 1 is unchanged.

## Verification

Run formatting, locked workspace tests and pedantic Clippy for `native`, `server` and `desktop`;
enable `imu-nav-routing/builder` for host routing tests. Android builds must keep builder features
disabled. `gradlew check` additionally checks Kotlin, replay and Android Lint. Simulator provenance
and Gradle task inputs include shared type sources. CI selection also invalidates every consuming
workspace when shared types or their workspace metadata change.

Compile-fail doctests cover quantity, clock, routing-ID and native-handle confusion. Regression tests
cover constructors, finite bounds, overflow, stale/wrong-kind handles, cancellation ownership,
wire fixtures, SQL conversion, upload counts and owned graphics lifetimes. The dedicated graphics
allocation test compares borrowed/owned construction and traversal without timing-dependent limits.
Run Android Graphics Path instrumentation on API 26–33 as well as the R8 benchmark build.

Kani needs the pinned Linux verifier described in [native verification](../native_verification.md).
Host tests do not substitute for that proof run, PostgreSQL integration needs its configured server,
and device validation must be reported separately from host results.
