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

// ./gradlew :replay:run --args="trip-20260928-101500.rec.gz --hide-gps-after 30,60,120 --out report"
application {
    mainClass.set("org.imunav.replay.MainKt")
}

dependencies {
    implementation(project(":core"))
}
