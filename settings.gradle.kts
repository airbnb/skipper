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

// skipper-state-machine is a plugin layer on top of the engine: a separate Gradle project (its own
// artifact) that depends on the root `skipper` project. Core does not depend on it, so the engine
// builds and publishes exactly as it did before this module existed.
include("skipper-state-machine")

// skipper-testutils is the test harness adopters extend (WorkflowTest, TestRuntime, the setup
// extensions): its own artifact, depending on the root `skipper` project, published alongside it.
// The directory keeps its historical name so sources line up with the internal build.
include("skipper-testutils")
project(":skipper-testutils").projectDir = file("testutils")
