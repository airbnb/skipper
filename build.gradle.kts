import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import java.util.Base64
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    // Dokka produces the javadoc jar Maven Central requires; the vanniktech plugin owns the
    // publication and the Central Portal upload. It applies maven-publish and signing itself,
    // but they are listed here too: Gradle only generates the type-safe `publishing { }` and
    // `signing { }` accessors used below for plugins declared in this block. Re-applying an
    // already-applied plugin is a no-op.
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
    `maven-publish`
    signing
    alias(libs.plugins.spotless)
}

group = "com.airbnb.skipper"

// Sentinel version for a build that was not given one. Deliberately not a valid release
// version so it cannot be mistaken for one, and guarded against in the publish tasks below.
val LOCAL_VERSION = "0.0.0-LOCAL"

// The version is supplied by CI as -PVERSION_NAME (see .circleci/config.yml); a plain
// local build stays on the sentinel below, which the publish guard refuses to upload.
version = providers.gradleProperty("VERSION_NAME").getOrElse(LOCAL_VERSION)

// Build with a JDK 17 toolchain but emit JVM 8 bytecode, maximizing OSS runtime
// compatibility.
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
        // Pin the Kotlin language/API level for reproducible OSS builds.
        apiVersion = "1.8"
        languageVersion = "1.8"
    }
}

// The OSS main source subset lives under src/main/java (mixed Java + Kotlin; the
// Kotlin plugin adds src/main/java to the Kotlin compilation for joint
// compilation) and src/main/resources, which carries both the MySQL db/migration
// and the SQLite db/sqlite Flyway script directories.
//
// The OSS test subset = everything under src/test + testutils except files that depend on
// Airbnb-only test infrastructure. src/test/thrift-beans holds the hand-authored,
// libthrift-0.9.3-compatible Thrift beans (the Gradle toolchain has no thrift compiler);
// it is a Gradle-only source dir.
val testSubsetExcludes =
    listOf(
        // Cross-backend prefix coverage is exercised by its dedicated Bazel target.
        "com/airbnb/skipper/internal/storage/prefix/TablePrefixIntegrationTest.kt"
    )

sourceSets {
    main {
        java.setSrcDirs(listOf("src/main/java"))
        resources.setSrcDirs(listOf("src/main/resources"))
    }
    test {
        // src/test/java is mixed Java+Kotlin; testutils/src/main/java is the trimmed shared
        // test harness; src/test/thrift-beans/java holds the hand-authored Thrift beans.
        java.setSrcDirs(
            listOf("src/test/java", "testutils/src/main/java", "src/test/thrift-beans/java")
        )
        resources.setSrcDirs(listOf("src/test/resources"))
        testSubsetExcludes.forEach { java.exclude(it) }
    }
}

// The Kotlin plugin's default test Kotlin srcDirs are src/test/{kotlin,java}; add the testutils
// Kotlin sources and apply the same OSS-subset excludes to the Kotlin compilation.
sourceSets.named("test").configure {
    val kotlinSrc = extensions.getByName("kotlin") as SourceDirectorySet
    kotlinSrc.srcDir("testutils/src/main/java")
    testSubsetExcludes.forEach { kotlinSrc.exclude(it) }
}

// Third-party dependencies for the main source subset. Versions come from the
// version catalog (gradle/libs.versions.toml).
dependencies {
    api(libs.vavr)
    api(libs.vavr.match)
    api(libs.guava)
    api(libs.jackson.annotations)
    api(libs.jackson.core)
    api(libs.jackson.databind)
    api(libs.jackson.datatype.jdk8)
    api(libs.jackson.datatype.jsr310)
    api(libs.jackson.module.kotlin)
    api(libs.jackson.module.parameter.names)
    api(libs.kotlinx.coroutines.core)
    api(libs.slf4j.api)
    api(libs.flyway.core)
    api(libs.sqlite.jdbc)
    api(libs.libthrift)
    api(libs.javassist)
    api(libs.opentracing.api)
    api(libs.opentracing.util)
    api(libs.javax.inject)
    api(libs.jakarta.ws.rs.api)

    // ---- Test-only dependencies (OSS test subset) ----
    // The `hello` workflow fixtures are Lombok @Value/@Builder POJOs.
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    // The `util` suite (ExtraRequestDataTest, plus a few classes) is JUnit 4; the vintage engine
    // runs those on the JUnit Platform alongside the Jupiter tests.
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.assertj)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.opentracing.mock)
    // testutils/tests use Guice (com.google.inject.*); main only needs javax.inject. Guice 6.x
    // still supports javax.inject bindings (@Named(UTC_CLOCK), etc.).
    testImplementation(libs.guice)
    testImplementation(libs.mariadb4j)
    testImplementation(libs.mariadb.java.client)
    testImplementation(libs.mysql.connector.java)
    // libthrift (the Thrift beans + ThriftSerde) is already an `api` dependency of main, so it is
    // on the test classpath transitively.
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // jackson 2.9.10 introspects java.lang.StackTraceElement when (de)serializing the Throwable-based
    // Skipper error types; under the JDK 17 module system that reflective access requires java.lang
    // to be opened to the unnamed module.
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}

// Apply the Kotlin AllOpen compiler plugin (bound to @SkipperOpen) to the TEST compilation only.
// @SkipperOpen classes and their subclasses become `open` so
// the workflow factory can proxy test Workflow/Actions subclasses, Mockito can mock them, and
// SkipperOpenTest's reflective assertions hold. It is intentionally NOT applied to main: main
// compiles without it (it uses explicit `open`), and enabling it there would clash with @JvmField
// members that @SkipperOpen classes carry (e.g. Actions.pendingCheckpointName) — a conflict the
// pinned kotlinc surfaces as "JvmField can only be applied to final property".
dependencies {
    "kotlinCompilerPluginClasspathTest"(
        "org.jetbrains.kotlin:kotlin-allopen-compiler-plugin-embeddable:${libs.versions.kotlin.get()}"
    )
}

tasks.named<KotlinCompile>("compileTestKotlin") {
    kotlinOptions.freeCompilerArgs +=
        listOf("-P", "plugin:org.jetbrains.kotlin.allopen:annotation=com.airbnb.skipper.SkipperOpen")
}

// ---------------------------------------------------------------------------------------
// Publishing
// ---------------------------------------------------------------------------------------
// One publication, one destination: Maven Central, published from CI on a v* tag with a
// semantic version. Central versions are permanent, public and undeletable, so only tagged
// releases are published at all. Internal consumers need nothing extra - Artifactory mirrors
// Central, so a release appears there on its own and is never uploaded directly.
//
// com.vanniktech.maven.publish owns the publication - coordinates, sources jar, the Dokka
// javadoc jar Central requires, the POM - and the Central Portal upload, which is a signed
// bundle plus a validation poll rather than a plain Maven PUT. That is why maven-publish on
// its own cannot reach Central.
//
// Credentials come from the CI context, as Gradle properties (ORG_GRADLE_PROJECT_ prefix)
// or environment variables. This is a public repository: none of it is committed here.
//
//   mavenCentralUsername / mavenCentralPassword   Central Portal user token
//   SIGNING_KEY / SIGNING_PASSWORD                 armoured PGP private key + passphrase
//                                                  (environment only - see `signing`)
//
// Nothing here is required to build. With none of it set, `build`, `assemble` and
// `publishToMavenLocal` work with no configuration whatsoever - which is what keeps
// pull-request builds green for anyone, including contributors who could never hold these.

// Byte-identical jars across rebuilds of the same commit, so a published artifact can be
// reproduced from its tag and compared against what the repository holds.
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

mavenPublishing {
    // `skipper-core` rather than `skipper`: skipper-state-machine is a sibling module, and
    // coordinates cannot be renamed once consumers depend on them.
    coordinates("com.airbnb.skipper", "skipper-core", version.toString())

    // Central requires a javadoc jar. This module is Kotlin-first (128 .kt to 7 .java), so the
    // stock `javadoc` task would document seven files and risk symbol-resolution failures
    // against the jointly compiled Kotlin. Dokka's javadoc-format jar is the one that is both
    // accepted by Central and actually useful.
    configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaJavadoc"), sourcesJar = true))

    pom {
        name.set("Skipper")
        description.set(
            "Durable workflow engine for the JVM, embedded in the service that uses it."
        )
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

    // Uploads a signed bundle to the Central Portal and polls for validation. The deployment
    // is then left for a human to release in the Portal UI rather than released automatically,
    // so the first few releases can be inspected before they become permanent. Flip to
    // automaticRelease = true once the pipeline has proven itself.
    publishToMavenCentral()
}

// Central rejects a re-upload of an existing version, and the sentinel version must never
// reach it at all. Fail early and legibly rather than letting either turn into an HTTP error
// mid-upload. Matches every Central-bound task the plugin registers, including the
// repository-style ones that stage into build/; publishToMavenLocal is deliberately not covered.
tasks.matching { it.name.contains("MavenCentral") }
    .configureEach {
        doFirst {
            check(version.toString() != LOCAL_VERSION) {
                "Refusing to publish $LOCAL_VERSION. Pass -PVERSION_NAME=<version>, e.g. " +
                    "./gradlew publishToMavenCentral -PVERSION_NAME=0.2.0"
            }
        }
    }

// Signing is conditional on purpose: publishToMavenLocal needs no key material in scope, so
// anyone can validate the publication. Maven Central rejects unsigned artifacts, so the CI
// release job supplies SIGNING_KEY and SIGNING_PASSWORD and every publication is signed.
// These are read as environment variables rather than the plugin's own signingInMemoryKey
// properties so the names match what the CI context already holds.
//
// SIGNING_KEY may be either the ASCII-armoured key itself or base64 of it. CI variable fields
// routinely flatten a multi-line value onto one line, and a flattened armoured key fails
// with nothing more helpful than "Could not read PGP secret key"; base64 survives any field.
// The MIME decoder also tolerates a base64 blob that was itself line-wrapped.
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

// ---------------------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------------------
// `./gradlew spotlessApply` reformats every Kotlin and Java source; `./gradlew spotlessCheck`
// fails if any would change, and `check` (so `build`) depends on it. The format-check CI job
// runs spotlessCheck on every push.
//
// Configured once here for the whole build rather than per project: skipper-state-machine's
// sources are covered by the targets below, so there is a single spotlessApply and one place
// that says what "formatted" means. Formatter versions come from the version catalog.
//
//   Kotlin  ktlint, configured by the .editorconfig at the repository root
//   Java    google-java-format, Google style (it has no configuration)
//
// Both are the formatters Airbnb's internal linter uses, at the same versions, so code moved
// between here and the internal monorepo keeps its formatting either way.
spotless {
    kotlin {
        target("src/**/*.kt", "testutils/src/**/*.kt", "skipper-state-machine/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    java {
        target("src/**/*.java", "testutils/src/**/*.java", "skipper-state-machine/src/**/*.java")
        googleJavaFormat(libs.versions.googleJavaFormat.get())
    }
}
