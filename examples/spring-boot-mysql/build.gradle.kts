import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
  java
  id("org.springframework.boot") version "4.1.1"
}

repositories {
  mavenCentral()
}

// One place to bump when a new Skipper release lands. Every example pins the same version.
val skipperVersion = "0.6.3"

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

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    events("passed", "failed", "skipped")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}
