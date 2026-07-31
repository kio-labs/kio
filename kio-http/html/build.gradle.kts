plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    macosArm64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":kio-http"))
            api(libs.kotlinx.html)
        }
    }
}
