# Rust routing pack format v2

`pack.json` declares `routingFormat: rust-ch-2`. The app rejects v1 and GraphHopper packs;
rebuild them. The metadata's declared profiles each have a `<profile>.routing` file, and
both profiles reference one `shared.routing`. Tantivy remains `tantivy-1` and is unchanged.
All integers are little-endian. Bounds, checked section arithmetic, IDs, CSR ranges,
hierarchy invariants and UTF-8 names are validated before queries can access records.

## Shared file

The 64-byte header contains magic `IMUGEO02`, then seven u64 values: geometry count, name
count, point count, UTF-8 byte count, elevation-present flag (0/1), and two reserved zeros.
Geometry descriptors and name descriptors follow (16 bytes each): u64 start index/byte offset,
u32 count/byte length, and four reserved zero bytes. Then come coordinates and name bytes.
A coordinate has signed i32 latitude and longitude in E7 degrees (8 bytes). If elevation is
present, each coordinate also has its original f64 height (16-byte records). Without
elevation no height column or per-point NaN is stored.

Geometry sequences are canonicalized against their reverse **after** E7 rounding. Every point
is retained, including adjacent points that round to the same coordinate. Equal canonical
sequences share a descriptor across profiles and restriction-history states. Hash matches are
confirmed against the full staged sequence. Names are deduplicated independently. Profiles
retain distinct topology IDs, access permissions, weights and speed limits.

## Profile file

The 72-byte header is magic `IMURT002` followed by eight u64 values: state count, graph vertex
count, node-based flag (walking=1, car=0), up/down arc counts, width flags, spatial-cell count,
and spatial-posting count. Width bits 0/1 select 64-bit up/down CSR offsets; bits 2/3 select
64-bit up/down arc weights. A cleared bit selects 32-bit storage. Writers check actual maxima;
weights are never rescaled and arithmetic remains u64 in the query engine.

Each state is 40 bytes: u32 from, to, geometry ID, name ID, f32 speed, u16 maxspeed, u16 flags,
u64 weight and f64 distance. Existing flags retain their meanings; bit 5 indicates reverse
traversal of shared geometry. Geometry count is read from its descriptor. Unused reverse-road
IDs, precomputed milliseconds and alignment padding have been removed.

After states: up/down CSR offsets (vertex count + 1 each), up/down arcs (u32 head, u32 skipped
vertex or `u32::MAX`, and weight at the selected width), sorted u32 cell keys, u64 cell posting
offsets (cell count + 1), then u32 road IDs. The spatial grid and its candidate policy are
unchanged; only repeated cell keys have been removed. There are no trailing sections.

## Ownership, creation and compatibility

The Rust pack owner retains one shared mapping and both profile readers. JNI operations select
a declared profile using the pack handle; closing the handle cannot invalidate active requests.
ARMv7 continues to use bounded mapping windows, with no whole-file decompression into RAM.

The host builder stages its original full-precision routing output, contracts both profiles,
and compacts that staged data before publishing metadata and creating the ZIP. Geometry
payloads stay in a temporary disk file during deduplication; in-memory tables hold descriptors
and hashes. This isolates serialization changes from import policy and CH preparation. Internal
`IMUST001` staging files are not importable packs and are replaced before publication.

E7 rounding gives under 1 cm positional error. Routing weights, original distance values,
posted speeds and elevation precision are retained. Public-coordinate snapping at exact ties
may be sensitive to rounding; conformance tests compare geometry with a tolerance and compare
fixed-vertex routing costs and sequences exactly. No road simplification is performed.
