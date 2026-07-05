@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    macosArm64 {
        binaries {
            executable {
                entryPoint("me.example.asyncechoserver.main")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kio-async:io"))
        }
        nativeMain.dependencies {
            implementation(project(":kio-async:poller-poll"))
        }
        jvmMain.dependencies {
            implementation(project(":kio-async:poller-jvm-select"))
        }
    }
}
