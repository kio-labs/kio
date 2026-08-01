@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("maven-publish-config")
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":kio-async:async-core"))
        }
    }
}
