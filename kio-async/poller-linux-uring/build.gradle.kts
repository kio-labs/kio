plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("maven-publish-config")
}

kotlin {
    linuxX64()
    sourceSets {
        linuxMain.dependencies {
            api(project(":kio-async:async-core"))
            implementation(libs.linux.uring)
        }
    }
}
