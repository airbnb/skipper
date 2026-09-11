import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import java.util.Base64
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// skipper-metrics-datadog: a `Metrics` implementation for one observability backend. Lives under plugins/ and is
// its own artifact so skipper-core stays free of backend clients: adopters add this module next to
// skipper-core and the client library they already run. Core never depends on it.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
    `maven-publish`
    signing
}

group = "com.airbnb.skipper"
// Versioned with the engine: one tag releases every artifact in this build.
version = rootProject.version

// Build with a JDK 17 toolchain but emit JVM 8 bytecode, matching core's build.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "1.8"
        apiVersion = "1.8"
        languageVersion = "1.8"
    }
}

dependencies {
    // Skipper core supplies the `Metrics` interface this module implements (and slf4j, transitively).
    api(project(":"))
    // The DogStatsD client is `api`: adopters construct and own the `StatsDClient` they hand to
    // `DatadogMetrics`, so its types are part of this module's public surface.
    api(libs.dogstatsd.client)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.assertj)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------------------
// Publishing
// ---------------------------------------------------------------------------------------
// Published to Maven Central as com.airbnb.skipper:skipper-metrics-datadog by the same `publishToMavenCentral`
// run that publishes skipper-core; the configuration mirrors the root project's (see the comments
// there).
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

mavenPublishing {
    coordinates("com.airbnb.skipper", "skipper-metrics-datadog", version.toString())
    configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaJavadoc"), sourcesJar = true))
    pom {
        name.set("Skipper Metrics for Datadog")
        description.set("Skipper Metrics implementation that reports through a DogStatsD client to Datadog.")
        url.set("https://github.com/airbnb/skipper")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("airbnb")
                name.set("Airbnb, Inc.")
                url.set("https://github.com/airbnb")
            }
        }
        scm {
            url.set("https://github.com/airbnb/skipper")
            connection.set("scm:git:https://github.com/airbnb/skipper.git")
            developerConnection.set("scm:git:ssh://git@github.com/airbnb/skipper.git")
        }
    }
    publishToMavenCentral()
}

tasks.matching { it.name.contains("MavenCentral") }
    .configureEach {
        doFirst {
            check(version.toString() != "0.0.0-LOCAL") {
                "Refusing to publish 0.0.0-LOCAL. Pass -PVERSION_NAME=<version>."
            }
        }
    }

signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY")
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD")
    if (signingKey.isPresent && signingPassword.isPresent) {
        val armoured =
            signingKey.get().trim().let { value ->
                if (value.startsWith("-----BEGIN PGP")) value
                else String(Base64.getMimeDecoder().decode(value), Charsets.UTF_8)
            }
        useInMemoryPgpKeys(armoured, signingPassword.get())
        sign(publishing.publications)
    }
}
