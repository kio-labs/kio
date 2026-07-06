plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    listOf(
        linuxX64(),
        macosArm64()
    ).forEach {
        it.binaries {
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
