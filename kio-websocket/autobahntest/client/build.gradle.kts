@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    macosArm64 {
        binaries {
            executable {
                entryPoint("me.example.asyncechoclient.main")
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation(project(":kio-websocket"))
        }
    }
}
