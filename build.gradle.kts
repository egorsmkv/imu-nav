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
