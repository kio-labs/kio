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
include(":t")
include(":kio-async:core")
include(":kio-async:polling-io")
include(":kio-async:poller-poll")
include(":kio-async:poller-kqueue")
include(":kio-async:poller-jvm-select")
include(":kio-async:poller-linux-epoll")
include(":kio-async:poller-linux-uring")
include(":kio-async:poller-test")
include(":kio-async:io")
include(":kio-async:io:example:async-echo-server")
include(":kio-async:io:example:tcp-client")
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
include(":kio-tls:example:client")
include(":kio-tls:example:server")
