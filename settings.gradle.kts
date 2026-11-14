pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { setUrl("https://jitpack.io") }
        maven("https://jogamp.org/deployment/maven")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("local-maven") }
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        maven("https://jogamp.org/deployment/maven")
        maven(url = "https://raw.githubusercontent.com/bravepipeproject/maven-repo/master/repository")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

val coreDir = File(rootDir, "core")
val serviceDir = File(rootDir, "core/service")
val mediaDir = File(rootDir, "core/media")

rootProject.name = "SimpMusic"
include(
    ":androidApp",
    ":composeApp",
    ":desktopApp",
    ":common",
    ":data",
    ":domain",
    ":ktorExt",
    ":kotlinYtmusicScraper",
    ":lyricsService",
    ":refineControl",
    ":media-jvm",
    ":media3",
    ":crashlytics",
    ":crashlytics-empty",
    ":cast",
    ":cast-empty",
)

project(":common").projectDir = File(coreDir, "common")
project(":data").projectDir = File(coreDir, "data")
project(":domain").projectDir = File(coreDir, "domain")

project(":ktorExt").projectDir = File(serviceDir, "ktorExt")
project(":lyricsService").projectDir = File(serviceDir, "lyricsService")
project(":kotlinYtmusicScraper").projectDir = File(serviceDir, "kotlinYtmusicScraper")
project(":refineControl").projectDir = File(serviceDir, "refineControl")

project(":media-jvm").projectDir = File(mediaDir, "media-jvm")
project(":media3").projectDir = File(mediaDir, "media3")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")