plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    macosArm64 {
        compilations.getByName("main") {
            cinterops {
                val openssl by creating {
                    defFile(project.file("src/nativeInterop/cinterop/openssl.def"))
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kio-async:io"))
            implementation(project(":kio-async:core"))
            implementation(libs.kotlinx.io)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
