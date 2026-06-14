plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    macosArm64()

    sourceSets {
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
