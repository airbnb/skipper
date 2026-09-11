import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
  java
  id("org.springframework.boot") version "4.1.1"
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
  // Spring Boot's BOM manages every Spring, Flyway, MySQL driver and Testcontainers version below. It also
  // overrides the Flyway 5.0.7 that skipper-core declares with Flyway 12; see README for what that implies.
  implementation(platform(SpringBootPlugin.BOM_COORDINATES))

  implementation("org.springframework.boot:spring-boot-starter-webmvc")
  implementation("org.springframework.boot:spring-boot-starter-jdbc")
  // /actuator/health, which compose.yaml and CI use as the readiness probe.
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  // Runs Skipper's bundled MySQL migrations (classpath:db/migration) at startup; see application.yaml.
  implementation("org.springframework.boot:spring-boot-starter-flyway")
  runtimeOnly("org.flywaydb:flyway-mysql")
  runtimeOnly("com.mysql:mysql-connector-j")

  implementation("com.airbnb.skipper:skipper-core:$skipperVersion")

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  // Boots a throwaway MySQL in Docker for the integration test and points the DataSource at it.
  testImplementation("org.springframework.boot:spring-boot-testcontainers")
  testImplementation("org.testcontainers:testcontainers-mysql")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

// One predictable artifact for the Dockerfile to copy: build/libs/app.jar, and no -plain.jar next to it.
tasks.jar {
  enabled = false
}
tasks.bootJar {
  archiveFileName.set("app.jar")
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    events("passed", "failed", "skipped")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}
