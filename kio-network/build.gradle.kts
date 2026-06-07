plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":kio-async:core"))
            api(libs.kotlinx.io)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
