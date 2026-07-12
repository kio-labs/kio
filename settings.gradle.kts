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
include(":kio-async:polling-io")
include(":kio-async:poller-poll")
include(":kio-async:poller-kqueue")
include(":kio-async:poller-jvm-select")
include(":kio-async:poller-linux-epoll")
include(":kio-async:poller-linux-uring")
include(":kio-async:io")
include(":kio-postgres:conn")
include(":kio-postgres:protocol")
include(":kio-postgres:types")
include(":kio-postgres:example:postgres-client")
include(":kio-compression")
include(":kio-http")
include(":kio-http:autobahntest:server")
include(":kio-http:autobahntest:client")
include(":kio-http:example:server")
include(":kio-tls")
include(":kio-integration-test")
