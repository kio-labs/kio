plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("maven-publish-config")
}

kotlin {
    macosArm64()
    linuxX64()
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":kio-async:async-io"))
        }

        nativeMain.dependencies {
            implementation(libs.openssl)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
