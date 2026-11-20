import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    group = "app.turtleplayer"
    version = "0.1.0"

    pluginManager.apply("org.jetbrains.kotlin.multiplatform")

    extensions.configure<KotlinMultiplatformExtension> {
        // One JVM target serves both desktop and Android (Android consumes JVM variants of KMP libraries).
        // iOS targets are added once we build on macOS; keep everything in commonMain.
        jvm {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
        compilerOptions {
            allWarningsAsErrors.set(false)
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    tasks.withType<Test>().configureEach {
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
