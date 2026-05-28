plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.serialization)
}

kotlin {
    jvm()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.io)
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.datetime)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}