// Standalone build: consumes skipper-core, skipper-state-machine and skipper-testutils from Maven Central.
// Lets Gradle download the JDK the build asks for (toolchain 17 in build.gradle.kts) when none is installed.
plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "skipper-example-kotlin-gradle-sqlite"
