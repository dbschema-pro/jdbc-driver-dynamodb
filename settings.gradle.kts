pluginManagement {
    repositories {
        maven {
            name = "wisecoders-nexus"
            url = uri(providers.gradleProperty("wisecodersNexus.repo-url.pull").get())

            credentials {
                username = providers.gradleProperty("wisecodersNexus.username").get()
                password = providers.gradleProperty("wisecodersNexus.password").get()
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        maven {
            name = "wisecoders-nexus"
            url = uri(providers.gradleProperty("wisecodersNexus.repo-url.pull").get())

            credentials {
                username = providers.gradleProperty("wisecodersNexus.username").get()
                password = providers.gradleProperty("wisecodersNexus.password").get()
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "jdbc-driver-dynamodb"

