plugins {
  java
  application
}

// Pinned to a Skipper release by default. Pass -PskipperVersion=<v> (or set ORG_GRADLE_PROJECT_skipperVersion)
// to build against a version you published to ~/.m2 with `./gradlew publishToMavenLocal -PVERSION_NAME=<v>` from a
// Skipper checkout; Skipper's own CI does exactly that so the examples exercise the code under review.
val skipperVersion = (findProperty("skipperVersion") as String?) ?: "0.6.3"

repositories {
  if (findProperty("skipperVersion") != null) {
    mavenLocal()
  }
  mavenCentral()
}


dependencies {
  implementation("com.airbnb.skipper:skipper-core:$skipperVersion")
  // Skipper logs through slf4j-api and ships no binding; pick any. simplelogger.properties
  // under src/main/resources quiets it down to WARN for Skipper's own packages.
  runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

  // WorkflowTest, the JUnit 5 base class that runs each test on its own in-memory runtime.
  // It declares JUnit as compileOnly, so the project brings its own.
  testImplementation("com.airbnb.skipper:skipper-testutils:$skipperVersion")
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
  // Versioned explicitly: with mavenLocal() in play Gradle may skip the module metadata that would otherwise
  // align this with junit-jupiter.
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

application {
  mainClass.set("com.example.orders.Main")
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    events("passed", "failed", "skipped")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}
