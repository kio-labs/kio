plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("maven-publish-config")
}

kotlin {
    linuxX64()
    sourceSets {
        commonMain.dependencies {
            api(project(":kio-async:async-core"))
            implementation(project(":kio-async:polling-io"))
        }
    }
}
