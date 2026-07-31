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
        // Increment versionCode on EVERY release: Android refuses to install an
        // APK whose versionCode is not greater than the installed one, so a
        // forgotten bump silently breaks updates for every user. versionName is
        // cosmetic and only needs to be meaningful to a human.
        versionCode = 6
        versionName = "0.5.0"

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

    // Release signing (#12). The keystore lives OUTSIDE the repo and its
    // passwords come from gitignored `local.properties` (or -P properties, or
    // the environment), following the same pattern as `defaultDeviceId` above.
    // These repos are public: nothing secret may be committed.
    //
    // The signing identity is effectively permanent. Android identifies an app
    // by (applicationId, signing certificate), so signing a later release with a
    // different key forces users to uninstall -- which wipes filesDir and
    // SharedPreferences, i.e. unsynced recordings and the server config. Back the
    // keystore and both passwords up before shipping anything signed with it.
    //
    // If the credentials are absent (a fresh clone, or CI), the release build
    // stays unsigned rather than failing: an unsigned APK is obvious and
    // harmless, whereas a build error here would block `assembleRelease` for
    // anyone just wanting to check that R8 succeeds.
    signingConfigs {
        create("release") {
            val props = Properties().apply {
                val f = rootProject.file("local.properties")
                if (f.exists()) FileInputStream(f).use { load(it) }
            }
            fun setting(name: String, env: String): String? =
                (project.findProperty(name) as String?)
                    ?: props.getProperty(name)
                    ?: System.getenv(env)

            val storePath = setting("releaseStoreFile", "PPG_BP_STORE_FILE")
            val storePass = setting("releaseStorePassword", "PPG_BP_STORE_PASSWORD")
            val keyAliasValue = setting("releaseKeyAlias", "PPG_BP_KEY_ALIAS")
            val keyPass = setting("releaseKeyPassword", "PPG_BP_KEY_PASSWORD")

            if (storePath != null && storePass != null &&
                keyAliasValue != null && keyPass != null &&
                file(storePath).exists()
            ) {
                storeFile = file(storePath)
                storePassword = storePass
                keyAlias = keyAliasValue
                keyPassword = keyPass

                // v2 is the baseline. v3 additionally records a signing-
                // certificate lineage, which is the only mechanism that lets a
                // future key change be accepted as an update instead of forcing
                // an uninstall (and with it the loss of unsynced recordings).
                // Every supported device is Android 13+, so both are safe.
                // v1 (JAR signing) is only needed below API 24 and is skipped.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            } else {
                logger.warn(
                    "ppg-bp: release signing credentials not found; " +
                        "release build will be UNSIGNED and cannot be installed as an update.",
                )
            }
        }
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
            // Only attach the config if it actually resolved to a keystore;
            // otherwise Gradle fails the build on a null storeFile.
            signingConfigs.getByName("release").storeFile?.let {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        // A release build you can drive over ADB. Same R8 configuration, same
        // keep rules and the same signing key as `release`, but the debug
        // command receiver is compiled in.
        //
        // This exists because the two properties we need cannot coexist in one
        // shippable artifact. Driving the app over ADB requires an exported
        // receiver, and adb shell runs as a different app identity (uid 2000),
        // so it cannot reach a non-exported one. But an exported receiver with
        // no permission gate is callable by ANY app on the phone, not only by
        // adb -- so shipping it would let any installed app repoint the upload
        // server and token, or start and stop recordings, on a device holding
        // health data.
        //
        // The alternative would be to keep the receiver in `release` behind
        // android:permission="android.permission.WRITE_SECURE_SETTINGS", which
        // adb shell holds and ordinary apps cannot obtain. That is defensible,
        // but it buys nothing operationally: the phones we ship to are not
        // reachable over ADB anyway, so the shipped artifact carries no harness
        // at all and this variant is used for verification instead.
        //
        // What it does and does not prove: R8 shrinking, obfuscation and the
        // keep rules against the real Polar and Omron code paths -- which is
        // where release-only breakage actually lives. It is not the identical
        // artifact, because retaining the receiver keeps a little more of the
        // graph reachable.
        create("releaseTest") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            // Resource shrinking off: it removes resources this variant's extra
            // code may reference, and resources are not what we are testing.
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["test"].kotlin.srcDirs("src/test/kotlin")

    // releaseTest reuses the debug harness verbatim rather than copying it, so the
    // two cannot drift apart and grow different command sets.
    sourceSets["releaseTest"].kotlin.srcDirs("src/debug/kotlin")
    sourceSets["releaseTest"].manifest.srcFile("src/debug/AndroidManifest.xml")

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
    implementation("androidx.navigation:navigation-compose:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // JVM unit tests (ROP writer golden-bytes contract test — no Android deps).
    testImplementation("junit:junit:4.13.2")
    // Real org.json for unit tests: the android.jar stub throws "not mocked", which
    // silently turned a parsed server version into null (#16).
    testImplementation("org.json:json:20240303")
}
