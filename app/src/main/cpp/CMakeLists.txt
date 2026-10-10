cmake_minimum_required(VERSION 3.22.1)
project(imu_nav_android_dependencies)

# Keep the 1.0.1 implementation and exported JNI ABI; change only the linker layout.
add_subdirectory(graphics-path)
target_compile_features(androidx.graphics.path PRIVATE cxx_std_17)
target_compile_options(androidx.graphics.path PRIVATE
    -fno-exceptions -fno-unwind-tables -fno-asynchronous-unwind-tables -fno-rtti
    -ffast-math -ffp-contract=fast -fvisibility-inlines-hidden -fvisibility=hidden
    -fomit-frame-pointer -ffunction-sections -fdata-sections)
target_link_options(androidx.graphics.path PRIVATE
    -nostdlib++ -Wl,--hash-style=both -Wl,--gc-sections -Wl,-Bsymbolic-functions
    -Wl,-z,common-page-size=16384)
