import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val linkProperties =
    Properties().apply {
        rootProject.file("links.properties").inputStream().use { load(it) }
    }

/** Reads an optional public HTTPS link and rejects values Android cannot safely open. */
fun configuredLink(name: String): String = linkProperties.getProperty(name, "").trim().also { value ->
    require(value.isEmpty() || value.startsWith("https://")) { "$name in links.properties must be empty or use HTTPS" }
}

/** Quotes a configurable value so Gradle can emit it as a BuildConfig String field. */
fun buildConfigString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val sourceRevision = providers.environmentVariable("GITHUB_SHA").orElse(providers.gradleProperty("sourceRevision")).orElse(
    providers.exec {
        commandLine("git", "rev-parse", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } },
)

android {
    namespace = "org.imunav.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.imunav.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.10.0"
        buildConfigField("String", "SOURCE_REVISION", buildConfigString(sourceRevision.get()))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MONOBANK_DONATION_URL", buildConfigString(configuredLink("monobankDonationUrl")))
        buildConfigField("String", "PRIVATBANK_DONATION_URL", buildConfigString(configuredLink("privatbankDonationUrl")))
        // Main-thread diagnostics (StrictMode + MainThreadWatchdog); on in debug and benchmark builds only.
        buildConfigField("boolean", "DIAGNOSTICS", "false")
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_static" }
        }
    }

    // Release signing: create keystore.properties (see README) — it and the keystore are gitignored.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (keystoreProps != null && !providers.gradleProperty("unsignedPlay").isPresent) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            // Only Play releases use the developer key. F-Droid builds and signs its own APK.
            signingConfig = signingConfigs.findByName("release")
        }
        create("fdroid") {
            dimension = "distribution"
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = providers.gradleProperty("kotlinCoverage").isPresent
            // Installs next to the release app (own data), so both can be compared on one device.
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "DIAGNOSTICS", "true")
        }
        release {
            // R8: strip unused library code and resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Phones are ARM; x86 builds only serve emulators (debug builds keep them).
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        // `assemblePlayBenchmark`: the release build (R8, same speed as users get) with main-thread
        // diagnostics on, signed with the debug key and installed next to the real app. Use it to
        // measure responsiveness; debug builds are much slower and cannot load the offline routing pack
        // (see AGENTS.md, "Known pitfalls").
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".bench"
            // Local installs reuse this machine's debug key. CI distributes an unsigned build
            // to the dedicated signing step, which restores one durable private benchmark key.
            signingConfig = if (providers.gradleProperty("unsignedBenchmark").isPresent) null else signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            buildConfigField("boolean", "DIAGNOSTICS", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // GraphHopper is a Java 17 library; desugaring rewrites its newer JDK calls for old Android versions.
        isCoreLibraryDesugaringEnabled = true
    }

    testCoverage { jacocoVersion = libs.versions.jacoco.get() }

    // Robolectric host tests read the merged resources (strings, themes) of the app; on JDK 17+ it
    // also needs reflective access to java.io file descriptors to emulate Android's shared memory.
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            // The same host JNI build the replay tests use, so tests can create the app graph with its native estimators.
            it.dependsOn(":replay:buildHostNative")
            it.systemProperty("java.library.path", rootProject.file("native/target/debug").absolutePath)
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        // PMTiles has compressed tiles and must be seekable inside the APK; routing zips are already compressed.
        noCompress += "zip"
        noCompress += "pmtiles"
    }

    packaging {
        resources {
            // GraphHopper's dependencies ship overlapping license/manifest files.
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST", "META-INF/*.md")
        }
    }

    sourceSets.getByName("main").jniLibs.directories.add(layout.buildDirectory.dir("generated/rustJniLibs").get().asFile.absolutePath)
    // The opt-in comparison harness targets the same R8 backend used in production.
    providers.gradleProperty("searchPrototypeDir").orNull?.let { experiment ->
        testBuildType = "benchmark"
        buildTypes.getByName("benchmark").proguardFiles("search-experiment-rules.pro")
        providers.gradleProperty("searchPrototypeAbi").orNull?.let { abi ->
            require(abi in setOf("arm64-v8a", "x86_64"))
            buildTypes.getByName("benchmark").ndk.abiFilters.apply {
                clear()
                add(abi)
            }
        }
        sourceSets.getByName("androidTest").apply {
            // Existing instrumentation uses debug-only activities; this isolated suite targets R8 instead.
            java.directories.clear()
            kotlin.directories.clear()
            kotlin.directories.add(rootProject.file("routing/src/test/kotlin/org/imunav/search").absolutePath)
            kotlin.directories.add("src/searchExperiment/kotlin")
            assets.directories.add("$experiment/assets")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The in-app language switcher needs every language in every install (App Bundles would split them).
    bundle {
        language {
            enableSplit = false
        }
    }

    // Android Lint: `./gradlew :app:lintFdroidRelease` (debug lint is also part of `check`). Deliberate exceptions live in lint.xml.
    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
        lintConfig = file("lint.xml")
        htmlReport = true
        textReport = true
    }
}

val androidSdkDirectory = androidComponents.sdkComponents.sdkDirectory

val buildRustAndroid = tasks.register<Exec>("buildRustAndroid") {
    group = "build"
    description = "Builds the Rust navigation core for the Android ABIs"
    workingDir(rootProject.projectDir)
    val outputDirectory = layout.buildDirectory.dir("generated/rustJniLibs").get().asFile
    if (System.getProperty("os.name").startsWith("Windows")) {
        commandLine(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
            rootProject.file("scripts/build-rust-android.ps1"), "-OutputRoot", outputDirectory,
        )
    } else {
        commandLine("bash", rootProject.file("scripts/build-rust-android.sh"), outputDirectory)
    }
    doFirst {
        // Android Studio often selects its SDK through local.properties rather than environment variables.
        val sdkDirectory = androidSdkDirectory.get().asFile.absolutePath
        environment("ANDROID_HOME", sdkDirectory)
        environment("ANDROID_SDK_ROOT", sdkDirectory)
        if (System.getenv("ANDROID_NDK_HOME") == null && System.getenv("ANDROID_NDK_ROOT") == null) {
            environment("ANDROID_NDK_HOME", "$sdkDirectory/ndk/${android.ndkVersion}")
        }
    }
    inputs.files(rootProject.fileTree("native") { exclude("target/**") })
    inputs.file(rootProject.file("scripts/build-rust-android.sh"))
    inputs.file(rootProject.file("scripts/build-rust-android.ps1"))
    outputs.dir(layout.buildDirectory.dir("generated/rustJniLibs"))
}

// AGP reads generated JNI directories in JniLibFolders before packaging them in NativeLibs.
tasks.matching {
    it.name.startsWith("merge") && (it.name.endsWith("JniLibFolders") || it.name.endsWith("NativeLibs"))
}.configureEach {
    dependsOn(buildRustAndroid)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":routing"))

    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.projected)
    if (!providers.gradleProperty("searchPrototypeDir").isPresent) {
        androidTestImplementation(libs.androidx.car.testing) {
            // Device tests use Android itself; Robolectric's service-loaded activity/thread adapters crash here.
            exclude(group = "org.robolectric")
        }
    }
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons.extended)
    // The vendored native implementation must match the Maven JNI callers exactly.
    implementation("androidx.graphics:graphics-path") { version { strictly("1.0.1") } }

    implementation(libs.maplibre.android)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.jackson.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

/**
 * Removes GraphHopper classes that `:routing` replaces with Android-compatible copies
 * (`routing/src/main/java/com/graphhopper/storage/`, see the README there) from the GraphHopper jar,
 * so the app contains only the patched versions. Other jars pass through unchanged.
 */
abstract class StripPatchedGraphHopperClasses : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        if (!input.name.startsWith("graphhopper-core-")) {
            outputs.file(input)
            return
        }
        val patched = listOf("com/graphhopper/storage/MMapDataAccess", "com/graphhopper/storage/RAMDataAccess")
        val output = outputs.file("patched-${input.name}")
        ZipFile(input).use { zip ->
            ZipOutputStream(output.outputStream().buffered()).use { out ->
                for (entry in zip.entries()) {
                    // The class itself and its inner classes (Name$Inner.class).
                    if (patched.any { entry.name == "$it.class" || entry.name.startsWith("$it$") }) continue
                    out.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }
}

val graphHopperPatched: Attribute<Boolean> = Attribute.of("org.imunav.graphhopper-patched", Boolean::class.javaObjectType)
val graphicsPathRebuilt: Attribute<Boolean> = Attribute.of("org.imunav.graphics-path-rebuilt", Boolean::class.javaObjectType)

/** Retains Graphics Path's bytecode and metadata while replacing its incompatible prebuilt JNI. */
abstract class StripPrebuiltGraphicsPath : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        if (!input.name.startsWith("graphics-path-")) {
            outputs.file(input)
            return
        }
        check(input.name == "graphics-path-1.0.1.aar") { "Update the vendored Graphics Path JNI source before changing its version" }
        val output = outputs.file("rebuilt-${input.name}")
        ZipFile(input).use { zip ->
            ZipOutputStream(output.outputStream().buffered()).use { out ->
                for (entry in zip.entries()) {
                    if (entry.name.startsWith("jni/") && entry.name.endsWith("/libandroidx.graphics.path.so")) continue
                    out.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }
}

dependencies {
    attributesSchema { attribute(graphHopperPatched) }
    attributesSchema { attribute(graphicsPathRebuilt) }
    artifactTypes.getByName("jar") { attributes.attribute(graphHopperPatched, false) }
    artifactTypes.maybeCreate("aar").attributes.attribute(graphicsPathRebuilt, false)
    registerTransform(StripPatchedGraphHopperClasses::class) {
        from.attribute(graphHopperPatched, false).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
        to.attribute(graphHopperPatched, true).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
    }
    registerTransform(StripPrebuiltGraphicsPath::class) {
        from.attribute(graphicsPathRebuilt, false).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar")
        to.attribute(graphicsPathRebuilt, true).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar")
    }
}

// GraphHopper brings its OSM import stack, which only the desktop pack builder (`:routing:run`)
// needs; the phone loads ready-made graphs. Keeping it out of the APK removes the only copyleft
// component (osmosis-osm-binary, LGPL 3.0) and its Protocol Buffers dependency.
configurations.configureEach {
    if (name.endsWith("RuntimeClasspath")) {
        // Local JVM tests need the app's original compiled classes, not APK artifact transforms.
        if (!name.contains("UnitTest")) {
            attributes.attribute(graphHopperPatched, true)
            attributes.attribute(graphicsPathRebuilt, true)
        }
        exclude(group = "org.openstreetmap.osmosis", module = "osmosis-osm-binary")
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
}

val forbiddenFdroidDependencyGroups =
    listOf(
        "com.android.billingclient",
        "com.appsflyer",
        "com.crashlytics.android",
        "com.google.android.gms",
        "com.google.android.play",
        "com.google.firebase",
        "com.google.mlkit",
        "com.mixpanel.android",
        "com.segment.analytics",
        "com.adjust.sdk",
        "io.fabric.sdk.android",
        "io.sentry",
    )

val verifyFdroidDependencies = tasks.register("verifyFdroidDependencies") {
    group = "verification"
    description = "Fails if the F-Droid runtime graph contains a known proprietary or tracking SDK."
    doLast {
        val runtime = configurations.getByName("fdroidReleaseRuntimeClasspath")
        val forbidden =
            // Inspect dependency coordinates without selecting transformed artifact variants.
            runtime.incoming.resolutionResult.allComponents
                .mapNotNull { component -> component.moduleVersion?.let { "${it.group}:${it.name}" } }
                .filter { coordinate -> forbiddenFdroidDependencyGroups.any { group -> coordinate.startsWith("$group:") } }
                .sorted()
        check(forbidden.isEmpty()) { "F-Droid runtime contains forbidden dependencies: ${forbidden.joinToString()}" }
    }
}

tasks.matching { it.name == "assembleFdroidRelease" }.configureEach {
    dependsOn(verifyFdroidDependencies)
}

/** Reads original variant bytecode through AGP, before dexing, shrinking or APK transforms. */
abstract class AppKotlinCoverageReport : JacocoReport() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classFolders: ListProperty<Directory>
}

androidComponents.onVariants(androidComponents.selector().withName("fdroidDebug")) { variant ->
    val coverageOutput = providers.gradleProperty("kotlinCoverageOutput").orElse(rootProject.layout.buildDirectory.dir("kotlin-coverage").map { it.asFile.absolutePath })
    val coverage = tasks.register<AppKotlinCoverageReport>("kotlinCoverageReport") {
        dependsOn("testFdroidDebugUnitTest")
        fun productionClasses(classes: FileTree): FileTree = classes.matching {
            include("org/imunav/**/*.class")
            exclude("**/BuildConfig.class", "**/R.class", "**/R$*.class")
            // Emulator-only Debug fixtures share this variant, but are absent from production APKs.
            // Keep main-source inventory validation intact; never count fixture lines as app coverage.
            exclude("org/imunav/app/location/MockLocationTest*.class")
        }
        classDirectories.from(classFolders.map { folders -> folders.map { productionClasses(fileTree(it.asFile)) } })
        classDirectories.from(classJars.map { jars -> jars.map { productionClasses(zipTree(it.asFile)) } })
        sourceDirectories.from(file("src/main/kotlin"))
        // AGP owns the unit-test agent destination; read it instead of guessing its output layout.
        executionData.from(tasks.named<Test>("testFdroidDebugUnitTest").map { it.extensions.getByType<JacocoTaskExtension>().destinationFile })
        reports {
            xml.required = true
            xml.outputLocation = file("${coverageOutput.get()}/app/report.xml")
            html.required = true
            html.outputLocation = file("${coverageOutput.get()}/app/html")
        }
        doFirst {
            check(classDirectories.files.isNotEmpty()) { "Missing app production classes" }
            executionData.files.forEach { check(it.isFile && it.length() > 0) { "Missing execution data: $it" } }
            val executionCopy = file("${coverageOutput.get()}/execution/app-testFdroidDebugUnitTest.exec")
            executionCopy.parentFile.mkdirs()
            executionData.singleFile.copyTo(executionCopy, overwrite = true)
            file("${coverageOutput.get()}/app").mkdirs()
            file("${coverageOutput.get()}/app/classes.txt").writeText(classDirectories.asFileTree.files.sorted().joinToString("\n") { it.absolutePath } + "\n")
        }
    }
    variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(coverage).toGet(ScopedArtifact.CLASSES, AppKotlinCoverageReport::classJars, AppKotlinCoverageReport::classFolders)
}
