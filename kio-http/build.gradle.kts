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
            implementation(project(":kio-tls"))
            implementation(project(":kio-async:io"))
            implementation(project(":kio-compression"))
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
