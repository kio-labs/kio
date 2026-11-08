plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("maven-publish-config")
}

kotlin {
    jvm()
    macosArm64()
    linuxX64()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kio-async:async-io"))
        }
    }
}
