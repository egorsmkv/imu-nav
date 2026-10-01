plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless)
}

// ---------------------------------------------------------------- code checks
// ./gradlew check        tests + detekt + ktlint (Spotless) + Android Lint
// ./gradlew spotlessApply  reformat everything to the project style (.editorconfig)

val ktlintVersion = libs.versions.ktlint.get()

spotless {
    kotlin {
        target("*/src/**/*.kt")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint(ktlintVersion)
    }
}

subprojects {
    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.file("config/detekt.yml"))
        parallel.set(true)
    }
}

tasks.named("check") { dependsOn("spotlessCheck") }

val rustFmtCheck = tasks.register<Exec>("rustFmtCheck") {
    group = "verification"
    description = "Checks formatting of the Rust navigation core"
    commandLine("cargo", "fmt", "--manifest-path", "native/Cargo.toml", "--all", "--check")
    inputs.files(fileTree("native") { exclude("target/**") })
}

val rustTest = tasks.register<Exec>("rustTest") {
    group = "verification"
    description = "Runs the Rust navigation core tests"
    commandLine("cargo", "test", "--manifest-path", "native/Cargo.toml")
    inputs.files(fileTree("native") { exclude("target/**") })
}

val rustClippy = tasks.register<Exec>("rustClippy") {
    group = "verification"
    description = "Runs pedantic Rust static analysis"
    commandLine("cargo", "clippy", "--manifest-path", "native/Cargo.toml", "--all-targets", "--", "-W", "clippy::pedantic", "-D", "warnings")
    inputs.files(fileTree("native") { exclude("target/**") })
}

tasks.named("check") {
    dependsOn(rustFmtCheck, rustTest, rustClippy)
}
