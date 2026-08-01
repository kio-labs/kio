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
            implementation(project(":kio-postgres:postgres-protocol"))
            implementation(project(":kio-postgres:postgres-types"))
            implementation(project(":kio-async:async-io"))
            implementation(project(":kio-tls"))
            implementation(libs.hash.md5)
            implementation(libs.hash.sha2)
            implementation(libs.macs.hmac.sha2)
        }
    }
}