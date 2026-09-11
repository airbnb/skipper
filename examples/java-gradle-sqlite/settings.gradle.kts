// Standalone build: this example is not part of the Skipper build. It consumes the published
// skipper-core and skipper-testutils artifacts from Maven Central, the same way a service would.
// Lets Gradle download the JDK the build asks for (toolchain 17 in build.gradle.kts) when none is installed.
plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "skipper-example-java-gradle-sqlite"
