plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    macosArm64 {
        binaries {
            executable {
                entryPoint("me.example.httpserver.main")
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kio-async:poller-poll"))
            implementation(project(":kio-http"))
        }
    }
}
