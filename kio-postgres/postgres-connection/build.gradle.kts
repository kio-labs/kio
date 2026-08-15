plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("maven-publish-config")
}

kotlin {
    jvm()
    linuxX64()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":kio-postgres:postgres-types"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":kio-postgres:postgres-protocol"))
            implementation(project(":kio-async:async-io"))
            implementation(project(":kio-tls"))
            implementation(libs.hash.md5)
            implementation(libs.hash.sha2)
            implementation(libs.macs.hmac.sha2)
        }
    }
}