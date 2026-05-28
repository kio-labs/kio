@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.serialization)
}

kotlin {
    macosArm64 {
        binaries {
            executable {
                entryPoint("me.example.postegreclient.main")
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation(project(":kio-postgres:conn"))
            implementation(project(":kio-postgres:protocol"))
            implementation(project(":kio-postgres:types"))
        }
    }
}
