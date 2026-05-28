plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":kio-async:core"))
            implementation(libs.hash.sha1)
        }
    }
}