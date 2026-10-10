# AndroidX Graphics Path 1.0.1 native source

These files are unchanged from AndroidX release commit
`8a05a22af450d589ef911d772a001a49dcb05b71`:

https://android.googlesource.com/platform/frameworks/support/+/8a05a22af450d589ef911d772a001a49dcb05b71/graphics/graphics-path/src/main/cpp/

`SHA256SUMS` records every original file. Copyright notices are retained; these
sources are Apache-2.0, including the Android/Filament vector helpers (see LICENSE).

The Maven 1.0.1 AAR's Java/Kotlin classes and consumer rules are retained. Only its
prebuilt JNI libraries are removed by an artifact transform. The parent CMake file
rebuilds the same JNI ABI with pinned NDK 28.2 and the upstream compiler options,
adding `-z common-page-size=16384` to align the end of GNU_RELRO as well as LOAD
segments. RELRO protection remains enabled. No algorithm or symbol export changes
are made. Verify all packaged libraries using `tools/verify_android_pages.py`;
the emulator suite also loads this library explicitly on 4 KB and 16 KB systems.

When upgrading the Maven dependency, update the source, manifest, licence and
native runtime test together; the artifact transform rejects unexpected versions.
