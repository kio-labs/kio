plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    linuxX64()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kio-postgres:protocol"))
            implementation(project(":kio-async:io"))
            api(project(":kio-postgres:types"))
            implementation(libs.hash.md5)
        }
    }
}