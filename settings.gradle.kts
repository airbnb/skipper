// Standalone Gradle build for the Skipper workflow engine (the OSS build option).
// Lives in-tree under common/skipper/.

pluginManagement {
    repositories {
        // Optional, env-gated Artifactory mirror first (to dodge Maven Central 429s
        // in CI), then the public plugin/artifact repositories as the fallback.
        System.getenv("SKIPPER_ARTIFACTORY_MIRROR")?.let { maven { url = uri(it) } }
        gradlePluginPortal()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        System.getenv("SKIPPER_ARTIFACTORY_MIRROR")?.let { maven { url = uri(it) } }
        mavenCentral()
    }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
}

rootProject.name = "skipper"
