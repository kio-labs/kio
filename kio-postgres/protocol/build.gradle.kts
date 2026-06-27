plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":kio-async:core"))
        }
    }
}