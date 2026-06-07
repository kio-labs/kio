@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    macosArm64 {
        binaries {
            executable {
                entryPoint("me.example.asyncechoserver.main")
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation(project(":kio-websocket"))
            implementation(project(":kio-async:poller-poll"))
        }
    }
}
