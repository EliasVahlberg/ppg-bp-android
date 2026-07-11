// settings.gradle.kts — top-level Gradle settings for the Android recorder.
//
// We use the modern "pluginManagement" + "dependencyResolutionManagement"
// blocks, which avoids the legacy buildscript classpath and keeps the
// per-module build.gradle.kts files clean.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Polar BLE SDK is published via JitPack
        maven(url = "https://jitpack.io") {
            content {
                includeGroup("com.github.polarofficial")
            }
        }
    }
}

rootProject.name = "polar-ppg-bp-recorder"
include(":app")
