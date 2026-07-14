@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.serialization)
}

kotlin {
    jvm()
    macosArm64()
    linuxX64()
    sourceSets {
        commonTest.dependencies {
            implementation(project(":kio-async:io"))
            implementation(project(":kio-tls"))
            implementation(project(":kio-postgres:conn"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(project(":kio-async:poller-jvm-select"))
        }
        nativeTest.dependencies {
            implementation(project(":kio-async:poller-poll"))
        }
        linuxMain.dependencies {
            implementation(project(":kio-async:poller-linux-epoll"))
            implementation(project(":kio-async:poller-linux-uring"))
        }
        macosTest.dependencies {
            implementation(project(":kio-async:poller-kqueue"))
        }
    }
}
