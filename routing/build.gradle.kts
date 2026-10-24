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
    mainClass.set("org.imunav.routing.BuildGraphKt")
    applicationDefaultJvmArgs = listOf("-Xmx10g")
}

// SQLite JDBC (desktop native libs) is only for building the search index — keep it out of the APK.
val builder by configurations.creating

dependencies {
    api(project(":core"))
    compileOnly(libs.sqlite.jdbc)
    builder(libs.sqlite.jdbc)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.jackson.core)
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

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    classpath += builder
}
tasks.named<CreateStartScripts>("startScripts") { classpath = classpath!! + builder }
distributions { main { contents { from(builder) { into("lib") } } } }

// Desktop pack generation uses the writer-enabled host library; Android builds omit that entry point.
val searchNativeTarget = providers.environmentVariable("CARGO_TARGET_DIR").map { rootProject.file(it).absolutePath }.orElse(rootProject.file("native/target").absolutePath)
val searchNativeDirectory = searchNativeTarget.map { "$it/release" }
val buildSearchNative = tasks.register<Exec>("buildSearchNative") {
    commandLine("cargo", "build", "--manifest-path", rootProject.file("native/Cargo.toml"), "-p", "imu-nav-search", "--release", "--features", "builder")
    environment("CARGO_TARGET_DIR", searchNativeTarget.get())
    inputs.files(rootProject.fileTree("native/nav-search"), rootProject.file("native/Cargo.lock"), rootProject.file("native/Cargo.toml"))
    outputs.file(searchNativeDirectory.map { "$it/${System.mapLibraryName("imu_nav_search")}" })
}
tasks.test {
    dependsOn(buildSearchNative)
    // JNI behavior can change without a Kotlin/JVM class changing; invalidate cached test results too.
    inputs.file(searchNativeDirectory.map { "$it/${System.mapLibraryName("imu_nav_search")}" })
    inputs.property("tantivyDataDir", providers.environmentVariable("TANTIVY_DATA_DIR").orElse(""))
    systemProperty("java.library.path", searchNativeDirectory.get())
}
tasks.named<JavaExec>("run") {
    dependsOn(buildSearchNative)
    systemProperty("java.library.path", searchNativeDirectory.get())
}
// Native distributions are host-specific, like the pack builder's JDBC binary.
distributions {
    main {
        contents {
            from(searchNativeDirectory) {
                include(System.mapLibraryName("imu_nav_search"))
                into("lib")
            }
        }
    }
}
tasks.named("installDist") { dependsOn(buildSearchNative) }
tasks.named("distZip") { dependsOn(buildSearchNative) }
tasks.named("distTar") { dependsOn(buildSearchNative) }
tasks.named<CreateStartScripts>("startScripts") {
    dependsOn(buildSearchNative)
    doLast {
        val libraryEnvironment = """export LD_LIBRARY_PATH="${'$'}APP_HOME/lib:${'$'}{LD_LIBRARY_PATH:-}"
export DYLD_LIBRARY_PATH="${'$'}APP_HOME/lib:${'$'}{DYLD_LIBRARY_PATH:-}"
CLASSPATH="""
        unixScript.writeText(unixScript.readText().replace("CLASSPATH=", libraryEnvironment))
        windowsScript.writeText(windowsScript.readText().replace("set CLASSPATH=", "set PATH=%APP_HOME%\\lib;%PATH%\r\nset CLASSPATH="))
    }
}
