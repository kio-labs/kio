@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("maven-publish-config")
}

kotlin {
    macosArm64()
    linuxX64()
    sourceSets {
        nativeMain.dependencies {
            api(project(":kio-async:async-core"))
        }
    }
}
