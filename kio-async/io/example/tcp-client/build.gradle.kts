@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    listOf(
        macosArm64(),
        linuxX64()
    ).forEach {
        it.binaries {
            executable {
                entryPoint("me.example.tcpclient.main")
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
