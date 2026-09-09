import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import java.util.Base64
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    // The state-machine's own main classes subclass core's @SkipperOpen Workflow/Actions, so the
    // all-open compiler plugin must run for BOTH main and test compilations. Bound to
    // @SkipperOpen below. (Core applies all-open to its test compilation only.)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
    `maven-publish`
    signing
}

group = "com.airbnb.skipper"
// Versioned with the engine: one tag releases skipper-core, skipper-testutils and this module.
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
        // Pin the Kotlin language/API level for parity with core.
        apiVersion = "1.8"
        languageVersion = "1.8"
    }
}

// Open @SkipperOpen-annotated classes (and their subclasses) for both main and test compilations so
// the Skipper workflow factory can proxy the state-machine Workflow/Actions subclasses (and mockk can
// mock them in the admin test).
allOpen {
    annotation("com.airbnb.skipper.SkipperOpen")
}

// One test is excluded from the Gradle test subset. It builds a core SmartSerde, and core's bytecode
// is compiled against the jackson version pinned in the version catalog (2.9.10) while this module's
// classpath resolves jackson to 2.13.5 — dropwizard-views 2.1.x drags in jackson-bom 2.13.5, which
// wins conflict resolution. The constructor SmartSerde calls on KotlinModule does not exist in
// 2.13.5, so the test dies with NoSuchMethodError. A blanket downgrade to 2.9.10 is not available
// (jackson-module-blackbird, which dropwizard-jackson needs, only exists from 2.12), and mixing
// jackson artifact versions is unsupported. Aligning the two pins is a core-level change, so the
// exclusion carries over from the upstream build as-is.
val testSubsetExcludes =
    listOf(
        "com/airbnb/skipper/statemachine/StateMachineCheckpointTest.kt",
    )

// Default source layout applies: main over src/main/kotlin (covers both the statemachine package and
// the statemachine/admin package in one Kotlin module, so admin sees statemachine's `internal`
// members) + src/main/resources; test over src/test/kotlin. Apply the excludes above to the test
// Kotlin compilation.
sourceSets.named("test").configure {
    val kotlinSrc = extensions.getByName("kotlin") as SourceDirectorySet
    testSubsetExcludes.forEach { kotlinSrc.exclude(it) }
}

dependencies {
    // Skipper core. This module is a plugin on top of the engine: it depends on core, and core has no
    // knowledge of it. Core exposes its `api` deps (jackson, guava, vavr, coroutines, slf4j)
    // transitively onto this module's classpath.
    api(project(":"))

    // ---- Main third-party deps (for the statemachine + admin sources) ----
    implementation(libs.jackson.annotations)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.smile)
    implementation(libs.zstd.jni)
    implementation(libs.kotlin.reflect)
    implementation(libs.slf4j.api)
    implementation(libs.javax.inject)
    // admin: the Dropwizard Freemarker admin UI. dropwizard-views 2.1.x is the javax.ws.rs line
    // (matching the admin sources' javax.ws.rs.* imports); jakarta.ws.rs-api 2.1.6 ships the
    // javax.ws.rs package. guice 6.0.0 keeps javax.inject support (7.x is jakarta-only). The admin
    // resource builds javax.ws.rs.core.Response, which needs a JAX-RS RuntimeDelegate provider — that
    // (plus the freemarker ViewRenderer discovered via ServiceLoader) comes in transitively from
    // dropwizard-views, so both are pulled with their default transitive graphs.
    implementation(libs.guice)
    implementation(libs.jakarta.ws.rs.api)
    implementation(libs.dropwizard.views)
    runtimeOnly(libs.dropwizard.views.freemarker)

    // ---- Test-only deps ----
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.assertj)
    // admin/StateMachineAdminResourceTest uses mockk and captures logs via a logback ListAppender.
    testImplementation(libs.mockk.jvm)
    testImplementation(libs.logback.classic)
    // admin test uses io.vavr collections/control types directly (vavr is otherwise only a transitive
    // api dep of core).
    testImplementation(libs.vavr)
    // Several statemachine tests use kotlinx.coroutines.runBlocking.
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // jackson reflectively introspects java.lang types (e.g. StackTraceElement) when (de)serializing
    // the Throwable-based Skipper error types; under the JDK 17 module system that access must be
    // opened to the unnamed module (same arg core's test task sets).
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}

// ---------------------------------------------------------------------------------------
// Publishing
// ---------------------------------------------------------------------------------------
// Published to Maven Central as com.airbnb.skipper:skipper-state-machine by the same
// `publishToMavenCentral` run that publishes skipper-core; the configuration mirrors the root
// project's (see the comments there). Consumers add it next to skipper-core; core never depends
// on it.
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

mavenPublishing {
    coordinates("com.airbnb.skipper", "skipper-state-machine", version.toString())
    configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaJavadoc"), sourcesJar = true))
    pom {
        name.set("Skipper State Machine")
        description.set("A state-machine DSL layered on the Skipper durable workflow engine.")
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
