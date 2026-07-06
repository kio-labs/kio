plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    macosArm64()
    linuxX64()

    sourceSets {
        compilerOptions {
            freeCompilerArgs.set(listOf("-Xcontext-parameters"))
        }
        commonMain.dependencies {
            api(libs.ktor.http)
            implementation(project(":kio-async:io"))
            implementation(project(":kio-compression"))
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        nativeTest.dependencies {
            implementation(project(":kio-async:poller-poll"))
        }
        linuxTest.dependencies {
            implementation(project(":kio-async:poller-linux-epoll"))
        }
        jvmTest.dependencies {
            implementation(project(":kio-async:poller-jvm-select"))
        }

        macosTest.dependencies {
            implementation(project(":kio-async:poller-kqueue"))
        }
    }
}
