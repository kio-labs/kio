plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kio-async:core"))
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
