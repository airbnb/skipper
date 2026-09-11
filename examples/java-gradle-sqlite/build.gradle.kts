plugins {
  java
  application
}

repositories {
  mavenCentral()
}

// One place to bump when a new Skipper release lands. Every example pins the same version.
val skipperVersion = "0.6.3"

dependencies {
  implementation("com.airbnb.skipper:skipper-core:$skipperVersion")
  // Skipper logs through slf4j-api and ships no binding; pick any. simplelogger.properties
  // under src/main/resources quiets it down to WARN for Skipper's own packages.
  runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

  // WorkflowTest, the JUnit 5 base class that runs each test on its own in-memory runtime.
  // It declares JUnit as compileOnly, so the project brings its own.
  testImplementation("com.airbnb.skipper:skipper-testutils:$skipperVersion")
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
