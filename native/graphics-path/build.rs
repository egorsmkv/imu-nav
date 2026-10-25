//! Keep Android JNI LOAD and RELRO boundaries compatible with real 16 KB pages.

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        // common-page-size also places the end of GNU_RELRO on a 16 KB boundary, which the
        // prebuilt AndroidX library does not do.
        println!("cargo:rustc-link-arg-cdylib=-Wl,-z,max-page-size=16384");
        println!("cargo:rustc-link-arg-cdylib=-Wl,-z,common-page-size=16384");
    }
}
