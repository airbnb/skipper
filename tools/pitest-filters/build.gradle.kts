// PIT mutation interceptors for this build's mutation CI job: CHANGED_LINES keeps only the mutants
// on the lines a pull request changes, KOTLIN_NULL_CHECKS drops the ones that only remove a null
// check the Kotlin compiler inserted. The root build.gradle.kts puts this jar on every project's
// PIT tool classpath. Not published: nothing outside this build runs it.
plugins {
    `java-library`
    // The sources are Java, but Kover only measures projects with a Kotlin plugin applied: without
    // it the classes drop out of the merged report and the coverage job sees nothing changed.
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    alias(libs.plugins.pitest)
}

group = "com.airbnb.skipper"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // PIT supplies itself at run time; the interceptors only compile against its plugin API.
    compileOnly(libs.pitest.entry)

    testImplementation(libs.pitest.entry)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.assertj)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
