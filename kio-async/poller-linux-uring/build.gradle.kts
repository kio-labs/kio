plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    linuxX64()
    sourceSets {
        linuxMain.dependencies {
            api(project(":kio-async:core"))
            implementation(libs.linux.uring)
        }
    }
}
