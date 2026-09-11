plugins {
  kotlin("jvm") version "2.4.20"
  // Skipper subclasses your Workflow and Actions classes at runtime, so they must be open. The base classes
  // carry @SkipperOpen; AllOpen propagates it to your subclasses so nothing needs annotating by hand.
  kotlin("plugin.allopen") version "2.4.20"
  application
}

allOpen {
  annotation("com.airbnb.skipper.SkipperOpen")
}

repositories {
  mavenCentral()
}

// One place to bump when a new Skipper release lands. Every example pins the same version.
val skipperVersion = "0.6.3"

dependencies {
  implementation("com.airbnb.skipper:skipper-core:$skipperVersion")
  // The state-machine DSL is a separate artifact layered on the engine.
  implementation("com.airbnb.skipper:skipper-state-machine:$skipperVersion")
  // Skipper serializes Kotlin data classes through jackson-module-kotlin, which reads Kotlin metadata via
  // kotlin-reflect. Keep it on the same version as the compiler rather than the old one Skipper's own
  // dependencies would otherwise pull in.
  implementation(kotlin("reflect"))
  runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

  testImplementation("com.airbnb.skipper:skipper-testutils:$skipperVersion")
  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
  jvmToolchain(17)
}

application {
  mainClass.set("com.example.orders.MainKt")
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    events("passed", "failed", "skipped")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}
