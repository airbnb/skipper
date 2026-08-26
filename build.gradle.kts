import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

group = "com.airbnb.skipper"

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
