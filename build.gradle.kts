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

val serverRustFmtCheck = tasks.register<Exec>("serverRustFmtCheck") {
    group = "verification"
    description = "Checks formatting of the Rust cell-sharing server"
    commandLine("cargo", "fmt", "--manifest-path", "server/Cargo.toml", "--all", "--check")
    inputs.files(fileTree("server") { exclude("target/**") })
}

val serverRustTest = tasks.register<Exec>("serverRustTest") {
    group = "verification"
    description = "Runs the persistent cell-sharing server tests"
    commandLine("cargo", "test", "--manifest-path", "server/Cargo.toml")
    inputs.files(fileTree("server") { exclude("target/**") })
}

val buildCellServerForKotlinTest = tasks.register<Exec>("buildCellServerForKotlinTest") {
    group = "verification"
    description = "Builds the local cell server used by Kotlin API integration tests"
    commandLine("cargo", "build", "--manifest-path", "server/Cargo.toml", "--bin", "imu-nav-cell-server")
    environment("CARGO_TARGET_DIR", file("server/target").absolutePath)
    inputs.files(fileTree("server") { exclude("target/**") })
    outputs.file(file("server/target/debug/imu-nav-cell-server"))
}

val serverRustClippy = tasks.register<Exec>("serverRustClippy") {
    group = "verification"
    description = "Runs pedantic static analysis for the Rust cell-sharing server"
    commandLine("cargo", "clippy", "--manifest-path", "server/Cargo.toml", "--all-targets", "--", "-W", "clippy::pedantic", "-D", "warnings")
    inputs.files(fileTree("server") { exclude("target/**") })
}

tasks.named("check") {
    dependsOn(serverRustFmtCheck, serverRustTest, serverRustClippy)
}

// Coverage is opt-in: ordinary tests and performance measurements run without a Java agent.
val kotlinCoverageEnabled = providers.gradleProperty("kotlinCoverage").isPresent
val kotlinCoverageOutput = providers.gradleProperty("kotlinCoverageOutput").orElse(layout.buildDirectory.dir("kotlin-coverage").map { it.asFile.absolutePath })
val kotlinCoverageTests = listOf(":core:test", ":routing:test", ":replay:test", ":app:testFdroidDebugUnitTest")
val kotlinCoverageReports = tasks.register("kotlinCoverageReport") {
    group = "verification"
    description = "Collects host Kotlin coverage; use tools/kotlin_coverage.py to merge and verify it"
    doFirst { check(kotlinCoverageEnabled) { "Coverage requires -PkotlinCoverage" } }
}

subprojects {
    apply(plugin = "jacoco")
    extensions.configure<JacocoPluginExtension> { toolVersion = rootProject.libs.versions.jacoco.get() }
    tasks.withType<Test>().configureEach {
        extensions.configure<JacocoTaskExtension> {
            isEnabled = kotlinCoverageEnabled
            destinationFile = file("${kotlinCoverageOutput.get()}/execution/${project.name}-$name.exec")
            includes = listOf("org.imunav.*")
        }
        if (kotlinCoverageEnabled) {
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
        }
    }
    if (name != "app") {
        val moduleName = name
        val report = tasks.register<JacocoReport>("kotlinCoverageReport") {
            dependsOn(kotlinCoverageTests)
            val main = project.extensions.getByType<SourceSetContainer>().named("main")
            classDirectories.from(main.map { it.output.classesDirs.asFileTree.matching { include("org/imunav/**") } })
            sourceDirectories.from(file("src/main/kotlin"))
            if (moduleName == "replay") sourceDirectories.from(rootProject.file("app/src/main/kotlin"))
            val executions = if (moduleName == "replay") listOf("replay-test") else listOf("core-test", "routing-test", "replay-test", "app-testFdroidDebugUnitTest")
            executionData.from(executions.filter { it != "app-testFdroidDebugUnitTest" }.map { file("${kotlinCoverageOutput.get()}/execution/$it.exec") })
            if (moduleName != "replay") {
                executionData.from(project(":app").tasks.named<Test>("testFdroidDebugUnitTest").map { it.extensions.getByType<JacocoTaskExtension>().destinationFile })
            }
            reports {
                xml.required = true
                xml.outputLocation = file("${kotlinCoverageOutput.get()}/$moduleName/report.xml")
                html.required = true
                html.outputLocation = file("${kotlinCoverageOutput.get()}/$moduleName/html")
            }
            doFirst {
                check(classDirectories.files.isNotEmpty()) { "Missing $moduleName production classes" }
                executionData.files.forEach { check(it.isFile && it.length() > 0) { "Missing execution data: $it" } }
                file("${kotlinCoverageOutput.get()}/$moduleName").mkdirs()
                file("${kotlinCoverageOutput.get()}/$moduleName/classes.txt").writeText(classDirectories.asFileTree.files.sorted().joinToString("\n") { it.absolutePath } + "\n")
            }
        }
        kotlinCoverageReports.configure { dependsOn(report) }
    }
}

kotlinCoverageReports.configure { dependsOn(":app:kotlinCoverageReport") }
