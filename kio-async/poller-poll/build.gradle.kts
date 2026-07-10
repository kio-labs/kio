@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    macosArm64()
    linuxX64()
    sourceSets {
        nativeMain.dependencies {
            api(project(":kio-async:core"))
            implementation(project(":kio-async:polling-io"))
        }
    }
}
