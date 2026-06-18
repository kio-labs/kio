plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    macosArm64()

    sourceSets {
        compilerOptions {
            freeCompilerArgs.set(listOf("-Xcontext-parameters"))
        }
        commonMain.dependencies {
            api(libs.ktor.http)
            implementation(project(":kio-network"))
            implementation(project(":kio-compression"))
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
