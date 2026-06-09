@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    macosArm64 {
        binaries {
            executable {
                entryPoint("me.example.pipe.main")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kio-async:poller-poll"))
        }
    }
}
