@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    linuxX64()
    macosArm64()
    sourceSets {
        nativeMain.dependencies {
            api(project(":kio-async:core"))
        }
    }
}
