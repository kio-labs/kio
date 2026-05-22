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

rootProject.name = "kio-async"
include(":kio-async-core")
includeBuild("third_party/kotlinx-io") {
    dependencySubstitution {
        substitute(module("org.jetbrains.kotlinx:kotlinx-io-core"))
            .using(project(":kotlinx-io-core"))
    }
}
include(":example:async-echo-server")
include(":example:std-in-out")
