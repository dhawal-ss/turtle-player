// :turtle-bridge — the only Turtle module that touches upstream. Implements our engine seams on
// top of Metrolist's APIs and provides the Koin module the app loads after upstream's.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

compose.resources {
    packageOfResClass = "app.turtleplayer.bridge.generated.resources"
}

kotlin {
    android {
        namespace = "app.turtleplayer.bridge"
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
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okhttp)
        }
        commonMain.dependencies {
            implementation(libs.components.resources)
            implementation(libs.runtime)
            implementation(project(":shared"))
            api("app.turtleplayer:turtle-events")
            api("app.turtleplayer:turtle-search")
            api("app.turtleplayer:turtle-taste")
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.koin.core)
            // androidx.sqlite connection API for our own database (no Room code generation needed).
            implementation(libs.room.runtime)
        }
    }
}
