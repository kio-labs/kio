@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    macosArm64()
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.io)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
