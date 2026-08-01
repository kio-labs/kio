import java.net.URI

plugins {
    `kotlin-dsl`
}

repositories {
    maven {
        url = URI("https://plugins.gradle.org/m2/")
    }
    google()
    mavenCentral()
}

dependencies {
    implementation(libs.maven.publish.gradle.plugin)
}
