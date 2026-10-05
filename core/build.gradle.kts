import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // HTTP client for everything that talks to a server (sync, downloads, routing, search).
    // `api` because callers pass OkHttp types (clients) into core classes.
    api(libs.okhttp)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    exclude("**/CellServerApiIntegrationTest.class")
}

val serverApiTest = tasks.register<Test>("serverApiTest") {
    group = "verification"
    description = "Runs the app's Kotlin cell-sync client against a temporary Rust server"
    dependsOn(rootProject.tasks.named("buildCellServerForKotlinTest"))
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/CellServerApiIntegrationTest.class")
    systemProperty("cellServerBinary", rootProject.file("server/target/debug/imu-nav-cell-server").absolutePath)
    useJUnit()
}

tasks.named("check") {
    dependsOn(serverApiTest)
}
