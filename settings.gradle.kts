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
include(":kio-async:poller-poll")
include(":kio-async:poller-kqueue")
include(":kio-async:poller-test")
include(":kio-async:example:pipe")
include(":kio-websocket")
include(":kio-websocket:autobahntest:server")
include(":kio-websocket:autobahntest:client")
include(":kio-postgres:conn")
include(":kio-postgres:protocol")
include(":kio-postgres:types")
include(":kio-postgres:example:postgres-client")
include(":kio-compression")
include(":kio-http")
include(":kio-http:example:server")
include(":kio-network")
include(":kio-network:example:async-echo-server")
include(":kio-network:example:tcp-client")
include(":kio-tls")
include(":kio-tls:example:client")
include(":kio-tls:example:server")
