# Patched GraphHopper classes

GraphHopper 11 targets Java 17 and uses a few JDK methods that Android only gained much later.
On older phones the app crashed as soon as it loaded a routing pack. The two storage classes
here are copies of GraphHopper 11.0's (Apache License 2.0, © GraphHopper GmbH), with only
those calls replaced:

| Class            | Upstream call                                        | Available on Android | Replacement                |
| ---------------- | ---------------------------------------------------- | -------------------- | -------------------------- |
| `MMapDataAccess` | `ByteBuffer.get/put(int, byte[], int, int)`          | 15 (API 35)          | single-byte loop           |
| `RAMDataAccess`  | `MethodHandles.byteArrayViewVarHandle` / `VarHandle` | 13 (API 33)          | manual little-endian bytes |

The app build removes the originals from the GraphHopper jar (`StripPatchedGraphHopperClasses` in
`app/build.gradle.kts`). Other newer JDK calls in GraphHopper (e.g. `Stream.toList()`) are
handled by core library desugaring. `tools/check_api_level.py` checks a built APK for any
remaining call newer than `minSdk`.

**When upgrading GraphHopper:** copy the new versions of these files from its sources jar, re-apply
the marked changes, and run `tools/check_api_level.py` on a release APK.
