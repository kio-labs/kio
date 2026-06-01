plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    macosArm64()
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kio-async:core"))
            implementation(libs.kotlinx.io)
            implementation(libs.ktor.http)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
