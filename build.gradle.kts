import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `java-library`
    // Both are Gradle built-ins, so adding them resolves nothing over the network.
    `maven-publish`
    signing
    alias(libs.plugins.kotlin.jvm)
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
    // Published alongside the binary so consumers get IDE navigation into the engine.
    withSourcesJar()
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
// One publication, published to Artifactory. The destination is supplied entirely from the
// environment: this is a public repository, so the URLs and credentials are configuration,
// not source. Only the hostnames are withheld - nothing about the mechanism is.
//
// The version selects which destination is used - anything ending in -SNAPSHOT goes to the
// snapshot URL, everything else to the release URL. CI passes the version with
// -PVERSION_NAME; scripts/next-version.sh computes it from the git history.
//
// Every value below can be set either as a Gradle property (CI: prefix the name with
// ORG_GRADLE_PROJECT_; locally: put it in ~/.gradle/gradle.properties) or as a plain
// environment variable of the same name:
//
//   SNAPSHOT_REPOSITORY_URL   destination for -SNAPSHOT versions
//   RELEASE_REPOSITORY_URL    destination for release versions
//   artifactoryUsername       repository username
//   artifactoryPassword       repository password or API token
//
// Nothing here is required to build. When the relevant URL is unset the remote repository
// is not registered at all, so `build`, `assemble` and `publishToMavenLocal` work with no
// configuration whatsoever - which is what keeps pull-request builds green for anyone,
// including contributors who could never hold these values.

// Reads a value from a Gradle property, falling back to an environment variable of the
// same name. Returns null when neither is set.
fun configValue(name: String): String? =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull

// Byte-identical jars across rebuilds of the same commit, so a published artifact can be
// reproduced from its tag and compared against what the repository holds.
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            // `skipper-core` rather than `skipper`: skipper-state-machine is a sibling
            // module, and coordinates cannot be renamed once consumers depend on them.
            artifactId = "skipper-core"

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
        }
    }

    repositories {
        val destination =
            if (version.toString().endsWith("-SNAPSHOT")) {
                configValue("SNAPSHOT_REPOSITORY_URL")
            } else {
                configValue("RELEASE_REPOSITORY_URL")
            }

        // Registered only when the destination is known. Without it there is no
        // `publishAllPublicationsToArtifactoryRepository` task, which is the correct outcome:
        // a build that has not been told where to publish cannot publish.
        if (destination != null) {
            maven {
                // The repository name drives the credential property names: a repository
                // called `artifactory` reads artifactoryUsername / artifactoryPassword.
                name = "artifactory"
                url = uri(destination)
                credentials(PasswordCredentials::class)
            }
        }
    }
}

// Artifactory rejects a re-upload of an existing release version, and the sentinel version
// must never reach a shared repository at all. Fail early and legibly rather than letting
// either turn into an HTTP error mid-upload.
tasks.withType<PublishToMavenRepository>().configureEach {
    doFirst {
        check(version.toString() != LOCAL_VERSION) {
            "Refusing to publish $LOCAL_VERSION. Pass -PVERSION_NAME=<version>, e.g. " +
                "./gradlew publishAllPublicationsToArtifactoryRepository -PVERSION_NAME=0.1.0-SNAPSHOT"
        }
    }
}

// Signing is optional on purpose. Artifactory accepts unsigned artifacts, so snapshots
// publish with no key material in scope; when SIGNING_KEY is present (Maven Central
// requires signatures) every publication is signed.
signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY")
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD")
    if (signingKey.isPresent && signingPassword.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.get())
        sign(publishing.publications)
    }
}
