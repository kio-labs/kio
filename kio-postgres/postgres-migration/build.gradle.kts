plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.serialization)
    id("maven-publish-config")
}

kotlin {
    jvm()
    macosArm64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kio-postgres:postgres-connection"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}