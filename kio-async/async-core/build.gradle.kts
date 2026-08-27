@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("maven-publish-config")
}

kotlin {
    jvm()
    macosArm64()
    linuxX64()

    applyHierarchyTemplate {
        common {
            group("jvm") {
                withJvm()
            }

            group("native") {
                withNative()

                group("linux") {
                    withLinux()
                }

                group("nonLinux") {
                    withApple()
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.io)
            api(libs.kotlinx.coroutines.core)
        }

        linuxMain.dependencies {
            api(libs.linux.platform)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
