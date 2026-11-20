kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":turtle-events"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
