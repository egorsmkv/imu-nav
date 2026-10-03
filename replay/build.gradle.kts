import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    // Compile the same Android-independent JNI wrappers used by the app, preserving JNI names.
    sourceSets.main {
        kotlin.srcDir(rootProject.file("app/src/main/kotlin"))
        kotlin.include(
            "org/imunav/replay/**",
            "org/imunav/app/nativecore/NativeEstimatorBridge.kt",
            "org/imunav/app/nativecore/NativeNavigationEstimator.kt",
            "org/imunav/app/nativecore/NativeRouteGeometry.kt",
            "org/imunav/app/nativecore/NativeRouteFilter.kt",
        )
    }
}

val nativeHeapProfile = providers.gradleProperty("nativeHeapProfile").isPresent

val buildHostNative = tasks.register<Exec>("buildHostNative") {
    group = "build"
    description = "Builds the navigation JNI library for host replay without an Android SDK"
    commandLine("cargo", "build", "--manifest-path", rootProject.file("native/Cargo.toml"), "--package", "imu-nav-jni")
    if (nativeHeapProfile) args("--features", "heap-profile")
    environment("CARGO_TARGET_DIR", rootProject.file("native/target"))
}

tasks.named<JavaExec>("run") {
    if (providers.gradleProperty("nativeReplay").isPresent || nativeHeapProfile) dependsOn(buildHostNative)
    systemProperty("java.library.path", rootProject.file("native/target/debug").absolutePath)
}

tasks.test {
    dependsOn(buildHostNative)
    systemProperty("imunav.heapProfile", nativeHeapProfile)
    systemProperty("java.library.path", rootProject.file("native/target/debug").absolutePath)
    // Rust changes must invalidate the JNI integration tests even when Kotlin is unchanged.
    inputs.files(fileTree(rootProject.file("native/target/debug")) { include("libimu_nav_jni.*", "imu_nav_jni.dll") })
    useJUnit()
}

// ./gradlew :replay:run --args="trip-20260928-101500.rec.gz --hide-gps-after 30,60,120 --out report"
application {
    mainClass.set("org.imunav.replay.MainKt")
}

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}
