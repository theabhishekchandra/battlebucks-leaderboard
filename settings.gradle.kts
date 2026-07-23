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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BattleBucks"

// :engine and :leaderboard are pure Kotlin/JVM modules, deliberately NOT Android library
// modules. That makes it structurally impossible for framework types to leak into the domain,
// and it keeps their unit tests on a plain JVM (milliseconds, no Robolectric, no emulator).
include(":app")
include(":engine")
include(":leaderboard")
