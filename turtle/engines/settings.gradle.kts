// Turtle Player engine build: pure-Kotlin search, recommendation and listening-event engines.
// Standalone so the engines build and test without the Android SDK or upstream's app modules:
//   ./gradlew -p turtle/engines check
// The app build consumes it as an included build (see turtle/HOOKS.md).
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
    versionCatalogs {
        // Share upstream's catalog so Kotlin and kotlinx versions never drift from the app.
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "turtle-engines"

include(":turtle-core", ":turtle-events", ":turtle-search", ":turtle-taste")
project(":turtle-core").projectDir = file("core")
project(":turtle-events").projectDir = file("events")
project(":turtle-search").projectDir = file("search")
project(":turtle-taste").projectDir = file("taste")
