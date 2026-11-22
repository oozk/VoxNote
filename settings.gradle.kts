pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // lets Gradle provision the Java 17 runtime used by unit tests when it is missing
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "OfflineTranscriber"
include(":app")
include(":engine-transcribe")
