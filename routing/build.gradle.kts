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
}

// `./gradlew :routing:run --args="--osm ukraine.osm.pbf --out graph-ukraine"` builds an offline routing pack.
application {
    mainClass.set("org.blinddriver.routing.BuildGraphKt")
    applicationDefaultJvmArgs = listOf("-Xmx10g")
}

dependencies {
    api(project(":core"))
    api(libs.graphhopper.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    maxHeapSize = "2g"
}
