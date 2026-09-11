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

// Dropwizard 3.x is the last line on the javax.ws.rs namespace, which is what Skipper's AdminResource is written
// against. Dropwizard 4 and 5 moved to jakarta.ws.rs and would not see the resource's annotations.
val dropwizardVersion = "3.0.17"

dependencies {
  implementation("com.airbnb.skipper:skipper-core:$skipperVersion")

  implementation("io.dropwizard:dropwizard-core:$dropwizardVersion")
  // DataSourceFactory: a pooled, managed javax.sql.DataSource from the YAML config.
  implementation("io.dropwizard:dropwizard-db:$dropwizardVersion")
  runtimeOnly("com.mysql:mysql-connector-j:9.7.0")

  // Guice 6 accepts both javax.inject and jakarta.inject annotations, so the @Inject fields on the Actions
  // classes (javax, Skipper's convention) are filled without any adaptation.
  implementation("com.google.inject:guice:6.0.0")

  // skipper-core pulls in Flyway 5.0.7, whose API predates Flyway.configure(). 5.2.4 is the last release that
  // has both the fluent API used in OrdersApplication and the constructor Skipper's SQLite store still calls,
  // and it supports MySQL 8. Anything newer breaks the SQLite store (and with it the WorkflowTest harness).
  implementation("org.flywaydb:flyway-core:5.2.4")

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
  mainClass.set("com.example.orders.OrdersApplication")
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    events("passed", "failed", "skipped")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}
