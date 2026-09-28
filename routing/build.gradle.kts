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

// SQLite JDBC (desktop native libs) is only for building the search index — keep it out of the APK.
val builder by configurations.creating

dependencies {
    api(project(":core"))
    compileOnly(libs.sqlite.jdbc)
    builder(libs.sqlite.jdbc)
    testImplementation(libs.sqlite.jdbc)
    api(libs.graphhopper.core)
    api(libs.graphhopper.map.matching) {
        exclude(group = "ch.qos.logback") // desktop logging backend; not wanted on Android
    }
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    maxHeapSize = "2g"
}

tasks.named<JavaExec>("run") { classpath += builder }
tasks.named<CreateStartScripts>("startScripts") { classpath = classpath!! + builder }
distributions { main { contents { from(builder) { into("lib") } } } }
