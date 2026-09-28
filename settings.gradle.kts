pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RingNotes"

// Pure-Kotlin protocol core (BLE UUIDs, ADPCM decoder, event parser, WAV writer).
// It is a standalone build so it can be tested without the Android SDK.
includeBuild("ringcore")
include(":app")
