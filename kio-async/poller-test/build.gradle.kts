@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    macosArm64()
    sourceSets {
        commonTest.dependencies {
            implementation(project(":kio-async:core"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(project(":kio-async:poller-jvm-select"))
        }
        nativeTest.dependencies {
            implementation(project(":kio-async:poller-poll"))
            implementation(project(":kio-async:poller-kqueue"))
        }
    }
}
