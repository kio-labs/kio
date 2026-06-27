plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.serialization)
}

kotlin {
    jvm()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kio-postgres:protocol"))
            implementation(project(":kio-network"))
            api(project(":kio-postgres:types"))
            implementation(libs.hash.md5)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        nativeTest.dependencies {
            implementation(project(":kio-async:poller-poll"))
        }

        jvmTest.dependencies {
            implementation(project(":kio-async:poller-jvm-select"))
        }

        macosTest.dependencies {
            implementation(project(":kio-async:poller-kqueue"))
        }
    }
}