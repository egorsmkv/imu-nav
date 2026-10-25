//! Keep Android search LOAD and RELRO boundaries compatible with real 16 KB pages.

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        // Rust toolchains can override the NDK defaults. Set both explicitly on the
        // final shared library; common-page-size also controls the RELRO end.
        println!("cargo:rustc-link-arg-cdylib=-Wl,-z,max-page-size=16384");
        println!("cargo:rustc-link-arg-cdylib=-Wl,-z,common-page-size=16384");
    }
}
