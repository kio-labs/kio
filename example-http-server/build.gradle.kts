plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    listOf(
        linuxX64(),
        macosArm64()
    ).forEach {
        it.binaries {
            executable {
                entryPoint("main")
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kio-http"))
            implementation(project(":kio-tls"))
        }
        jvmMain.dependencies {
            implementation(project(":kio-async:poller-jvm-select"))
        }
        nativeMain.dependencies {
            implementation(project(":kio-async:poller-poll"))
        }
    }
}
