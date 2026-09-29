import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.blinddriver.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "org.blinddriver.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.6.0"
    }

    // Release signing: create keystore.properties (see README) — it and the keystore are gitignored.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (keystoreProps != null) {
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
        release {
            // R8: strip unused library code and resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Phones are ARM; x86 builds only serve emulators (debug builds keep them).
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // A Play-only bundled routing pack is already a zip; store it as-is so first-start unpacking is fast.
        noCompress += "zip"
    }

    packaging {
        resources {
            // GraphHopper's dependencies ship overlapping licence/manifest files.
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST", "META-INF/*.md")
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

dependencies {
    implementation(project(":core"))
    implementation(project(":routing"))

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

    implementation(libs.maplibre.android)
}

// GraphHopper brings its OSM import stack, which only the desktop pack builder (`:routing:run`)
// needs; the phone loads ready-made graphs. Keeping it out of the APK removes the only copyleft
// component (osmosis-osm-binary, LGPL 3.0) and its Protocol Buffers dependency.
configurations.configureEach {
    if (name.endsWith("RuntimeClasspath")) {
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
            runtime.resolvedConfiguration.resolvedArtifacts
                .map { artifact -> "${artifact.moduleVersion.id.group}:${artifact.name}" }
                .filter { coordinate -> forbiddenFdroidDependencyGroups.any { group -> coordinate.startsWith("$group:") } }
                .sorted()
        check(forbidden.isEmpty()) { "F-Droid runtime contains forbidden dependencies: ${forbidden.joinToString()}" }
    }
}

tasks.matching { it.name == "assembleFdroidRelease" }.configureEach {
    dependsOn(verifyFdroidDependencies)
}
