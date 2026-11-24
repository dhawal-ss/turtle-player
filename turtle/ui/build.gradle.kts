// :turtle-ui — Shell, Turtle Player's design system and screens (Compose Multiplatform).
// Depends on :turtle-bridge for our engines and on upstream :shared for models and playback.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

compose.resources {
    packageOfResClass = "app.turtleplayer.ui.generated.resources"
}

kotlin {
    android {
        namespace = "app.turtleplayer.ui"
        compileSdk {
            version = release(37) { minorApiLevel = 1 }
        }
        minSdk = 24
        androidResources.enable = true
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }

    jvm("desktop") {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }

    jvmToolchain(25)

    sourceSets {
        // Pure state logic behind Shell's layouts (desktop panel, Explore anchoring, mix snapshots).
        // Runs locally (./gradlew :turtle-ui:desktopTest); CI runs only the engines.
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
            // Headless screenshot harness (src/desktopTest/.../shot): Compose's UI test runner on Skia,
            // no window, no network.
            implementation("org.jetbrains.compose.ui:ui-test:${libs.versions.compose.get()}")
            // Dispatchers.Main, as in the desktop app (Shell launches some work on it).
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.kotlinxCoroutines.get()}")
        }
        commonMain.dependencies {
            implementation(project(":shared"))
            api(project(":turtle-bridge"))
            implementation(libs.runtime)
            implementation(libs.foundation)
            implementation(libs.material3)
            implementation(libs.ui)
            implementation(libs.components.resources)
            implementation(libs.coil)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.koin.core)
        }
    }
}
