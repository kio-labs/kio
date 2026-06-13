plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kio-async:core"))
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
