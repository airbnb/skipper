import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import java.util.Base64
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// skipper-testutils: the test harness for services that use Skipper. `WorkflowTest` (extend it,
// write a test, Skipper is running on an in-memory store), `TestRuntime`, the wait helpers, and
// the SQLite/MySQL JUnit extensions. Published to Maven Central as
// com.airbnb.skipper:skipper-testutils, versioned with skipper-core.
//
// Dependency policy: the artifact adds nothing to a consumer's classpath beyond skipper-core.
// JUnit is compileOnly because every consumer's test classpath has it already. The pieces that
// need more than that declare it compileOnly too, so they cost nothing unless used:
//   TestRuntime               Mockito (its FeatureGate/Knobs are mocks tests stub with `whenever`)
//   MySqlTestSetupExtension   MariaDB4j + the MariaDB/MySQL drivers
// WorkflowTest, WorkflowTestHelper, MutableClock, @Bind and SqliteTestSetupExtension need none.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
    `maven-publish`
    signing
}

group = "com.airbnb.skipper"
version = rootProject.version

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

// Same rule as the engine: Workflow/Actions subclasses (including the ones in this module's own
// tests) are opened for Skipper's proxies.
allOpen {
    annotation("com.airbnb.skipper.SkipperOpen")
}

dependencies {
    api(project(":"))
    compileOnly(libs.junit.jupiter.api)
    compileOnly(libs.mockito.core)
    compileOnly(libs.mariadb4j)
    compileOnly(libs.mariadb.java.client)
    compileOnly(libs.mysql.connector.java)

    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.logback.classic)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Publishing mirrors the root project's (see the comments there); only the coordinates and the
// description differ. Both artifacts are uploaded by the same `publishToMavenCentral` run.
mavenPublishing {
    coordinates("com.airbnb.skipper", "skipper-testutils", version.toString())
    configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaJavadoc"), sourcesJar = true))
    pom {
        name.set("Skipper test utilities")
        description.set("Test harness for services built on Skipper: WorkflowTest and friends.")
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
