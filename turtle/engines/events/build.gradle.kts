plugins { alias(libs.plugins.kotlin.serialization) }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":turtle-core"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
