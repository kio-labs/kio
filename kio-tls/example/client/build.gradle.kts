plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    macosArm64  {
        binaries {
            executable {
                entryPoint("me.example.kio.tls.client.main")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kio-async:poller-poll"))
            implementation(project(":kio-tls"))
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
