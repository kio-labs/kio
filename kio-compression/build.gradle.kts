plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.io)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
