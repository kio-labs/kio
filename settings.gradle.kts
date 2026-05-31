pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kio"
include(":kio-async:core")
include(":kio-async:example:async-echo-server")
include(":kio-async:example:std-in-out")
include(":kio-async:example:pipe")
include(":kio-websocket")
include(":kio-websocket:example:server")
include(":kio-postgres:conn")
include(":kio-postgres:protocol")
include(":kio-postgres:types")
include(":kio-postgres:example:postgres-client")
include(":kio-compression")
