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
            implementation(project(":kio-http"))
            implementation(project(":kio-async:poller-poll"))
            implementation(libs.hash.sha1)
        }
    }
}
