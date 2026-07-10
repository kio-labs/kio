plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    linuxX64()
    sourceSets {
        commonMain.dependencies {
            api(project(":kio-async:core"))
            implementation(project(":kio-async:polling-io"))
        }
    }
}
