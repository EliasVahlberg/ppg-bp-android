plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.util.Properties
import java.io.FileInputStream

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

android {
    namespace = "com.polarppgbp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.polarppgbp"
        // Polar BLE SDK 7.0+ requires minSdk 33 (Android 13). Our test
        // device (OnePlus 9 Pro) runs Android 14 (API 34) so this is fine.
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Optional fallback Polar device ID used only when the app has never
        // been told which device to connect to (no prior SET_SERVER/pairing).
        // Left empty by default so the repo never hardcodes a real device's
        // serial. Override locally via `local.properties`
        // (`defaultDeviceId=YOURSERIAL`), which is gitignored, or with
        // `-PdefaultDeviceId=YOURSERIAL` on the command line.
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) FileInputStream(f).use { load(it) }
        }
        val defaultDeviceId = (project.findProperty("defaultDeviceId") as String?)
            ?: localProps.getProperty("defaultDeviceId")
            ?: ""
        buildConfigField("String", "DEFAULT_DEVICE_ID", "\"$defaultDeviceId\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            // Useful logs while we're still iterating.
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["test"].kotlin.srcDirs("src/test/kotlin")

    packaging {
        resources {
            // Polar SDK pulls in protobuf which sometimes ships duplicate
            // META-INF entries; merge them rather than failing the build.
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        }
    }
}

// Kotlin 2.3 DSL: kotlinOptions {} is gone, use kotlin { compilerOptions { … } }.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Tolerate Polar SDK ABI metadata that ships ahead of compiler version.
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    // Kotlin stdlib + Android-specific coroutine dispatchers.
    // The Polar SDK already bundles kotlinx-coroutines-core; we just need
    // -android for Dispatchers.Main on the IO thread, and Compose anyway
    // depends on coroutines.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Polar BLE SDK 7.x — Coroutines-based API. Pinned to 7.1.0 (April 2026).
    implementation("com.github.polarofficial:polar-ble-sdk:7.1.0")

    // Polar SDK transitive deps that we need explicitly (per their README).
    implementation("io.reactivex.rxjava3:rxjava:3.1.6")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.2")
    implementation("commons-io:commons-io:2.16.1")
    implementation("com.google.protobuf:protobuf-javalite:3.21.12")

    // Jetpack
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Background upload of session bundles to ppg-pi-server.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // JVM unit tests (ROP writer golden-bytes contract test — no Android deps).
    testImplementation("junit:junit:4.13.2")
}
