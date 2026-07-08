plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    linuxX64 {
        binaries {
            executable {
                entryPoint("main")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kio-async:poller-linux-uring"))
        }
    }
}
