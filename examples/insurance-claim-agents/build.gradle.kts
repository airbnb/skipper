plugins {
  java
  application
}

// Pinned to a Skipper release by default. Pass -PskipperVersion=<v> (or set ORG_GRADLE_PROJECT_skipperVersion)
// to build against a version you published to ~/.m2 with `./gradlew publishToMavenLocal -PVERSION_NAME=<v>` from a
// Skipper checkout; Skipper's own CI does exactly that so the examples exercise the code under review.
val skipperVersion = (findProperty("skipperVersion") as String?) ?: "0.16.0"

repositories {
  if (findProperty("skipperVersion") != null) {
    mavenLocal()
  }
  mavenCentral()
}

val jerseyVersion = "2.47"

dependencies {
  implementation("com.airbnb.skipper:skipper-core:$skipperVersion")
  // The model behind ClaudeModel. Only called when ANTHROPIC_API_KEY is set; the default agents are scripted.
  // It also brings a current Jackson, which is what lets this example use Java records for everything Skipper
  // persists (records need Jackson 2.12+; Skipper runs on whichever Jackson 2.x the application ships).
  implementation("com.anthropic:anthropic-java:3.3.0")
  runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

  // Hosts the claims page and Skipper's admin UI (WebServer.java). The admin UI is a JAX-RS resource on the
  // javax.ws.rs namespace, which is Jersey 2; Jersey 3 and later are jakarta.ws.rs and would not see its
  // annotations. The JDK HTTP container needs no separate web server.
  implementation("org.glassfish.jersey.containers:jersey-container-jdk-http:$jerseyVersion")
  implementation("org.glassfish.jersey.inject:jersey-hk2:$jerseyVersion")
  implementation("org.glassfish.jersey.media:jersey-media-json-jackson:$jerseyVersion")

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
  mainClass.set("com.example.claims.Main")
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    events("passed", "failed", "skipped")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}
