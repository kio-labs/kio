plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    signAllPublications()
    publishToMavenCentral()

    pom {
        name.set("kio")
        description.set("Coroutine-friendly async I/O extensions for Kotlin/Native and kotlinx-io.")
        url.set("https://github.com/kio-labs/kio/blob/main/README.md")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("andannn")
                name.set("Andannn")
            }
        }

        scm {
            url.set("https://github.com/kio-labs/kio.git")
            developerConnection.set("scm:git:ssh://git@github.com/kio-labs/kio.git")
            connection.set("scm:git:git://github.com/kio-labs/kio.git")
        }
    }
}