pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Let Gradle auto-provision the JDK 17 toolchain on any machine, so `gradlew` works
    // without a manually configured JAVA_HOME. Downloads to the Gradle cache on first use.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Krystals"
include(
    ":app",
    ":crystal-analysis",
    ":crystal-core",
    ":crystal-data",
    ":crystal-io",
    ":renderer",
    ":renderer-core",
    ":renderer-filament",
    ":renderer-legacy",
)
