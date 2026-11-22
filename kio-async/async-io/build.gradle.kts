import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("maven-publish-config")
}

kotlin {
    jvm()
    macosArm64 {
        compilations.getByName("main") {
            cinterops.create("spawn")
        }
    }
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":kio-async:async-core"))
            api(libs.kotlinx.io)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
