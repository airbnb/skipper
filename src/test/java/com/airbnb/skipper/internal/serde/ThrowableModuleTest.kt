package com.airbnb.skipper.internal.serde

import com.airbnb.skipper.ApplicationError
import com.airbnb.skipper.RetryableError
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins how errors and their [StackTraceElement]s are persisted.
 *
 * [ThrowableModule] exists so that persisting an error works on JDK 16+ without
 * `--add-opens java.base/java.lang=ALL-UNNAMED`: this suite runs without that flag (see the root
 * `build.gradle.kts`), so a regression to reflective access fails here with
 * `InaccessibleObjectException` under the JDK 17 toolchain. The wire format is pinned to the bytes a JDK 8
 * deployment has always written, so persisted rows stay readable in both directions.
 */
class ThrowableModuleTest {
    private val serde = SimplePojoSerde()
    private val mapper: ObjectMapper = serde.objectMapper

    private val frame = StackTraceElement("com.example.Foo", "bar", "Foo.java", 42)

    @Test
    fun `writes the exact six keys the JDK 8 bean serializer produced`() {
        assertThat(mapper.writeValueAsString(frame))
            .isEqualTo(
                "{\"declaringClass\":\"com.example.Foo\",\"methodName\":\"bar\",\"fileName\":\"Foo.java\"," +
                    "\"lineNumber\":42,\"className\":\"com.example.Foo\",\"nativeMethod\":false}"
            )
    }

    @Test
    fun `null file name and native frames survive a round trip`() {
        val native = StackTraceElement("java.lang.Object", "wait", null, -2)
        assertThat(native.isNativeMethod).isTrue()
        val back = mapper.readValue(mapper.writeValueAsString(native), StackTraceElement::class.java)
        assertThat(back).isEqualTo(native)
        assertThat(back.isNativeMethod).isTrue()
    }

    @Test
    fun `reads rows written by JDK 8 deployments`() {
        val jdk8 =
            "{\"declaringClass\":\"com.example.Foo\",\"methodName\":\"bar\",\"fileName\":\"Foo.java\"," +
                "\"lineNumber\":42,\"className\":\"com.example.Foo\",\"nativeMethod\":false}"
        assertThat(mapper.readValue(jdk8, StackTraceElement::class.java)).isEqualTo(frame)
    }

    @Test
    fun `reads rows written by JDK 17 deployments that ran with add-opens`() {
        // Captured from SimplePojoSerde on Corretto 17 with --add-opens: the bean serializer also
        // emitted the module fields and the internal `format` byte.
        val jdk17 =
            "{\"classLoaderName\":\"app\",\"moduleName\":null,\"moduleVersion\":null," +
                "\"declaringClass\":\"com.example.Foo\",\"methodName\":\"bar\",\"fileName\":\"Foo.java\"," +
                "\"lineNumber\":42,\"format\":1,\"nativeMethod\":false,\"className\":\"com.example.Foo\"}"
        assertThat(mapper.readValue(jdk17, StackTraceElement::class.java)).isEqualTo(frame)
    }

    @Test
    fun `reads the className-only form Jackson's own deserializer accepts`() {
        val minimal = "{\"className\":\"com.example.Foo\",\"methodName\":\"bar\",\"lineNumber\":42}"
        assertThat(mapper.readValue(minimal, StackTraceElement::class.java))
            .isEqualTo(StackTraceElement("com.example.Foo", "bar", null, 42))
    }

    @Test
    fun `a retryable error with a real stack trace persists through the full serde`() {
        val error = RetryableError("carrier unavailable", IllegalStateException("503"))
        error.setStackTrace(arrayOf(frame))

        // Both directions must work on this JVM without java.lang being opened: the cause carries the
        // live stack trace captured when IllegalStateException was created.
        val serialized = serde.serialize(error)
        val back = serde.deserialize(serialized) as RetryableError

        assertThat(back.message).isEqualTo("carrier unavailable")
        assertThat(back.cause).isInstanceOf(ApplicationError::class.java)
        assertThat(back.cause!!.message).isEqualTo("503")

        // The payload is [className, json]; the persisted frames use the pinned JDK 8 shape.
        @Suppress("UNCHECKED_CAST")
        val payload = mapper.readTree((mapper.readValue(serialized, List::class.java) as List<String>)[1])
        assertThat(payload["stackTrace"][0]["declaringClass"].asText()).isEqualTo("com.example.Foo")
        assertThat(payload["cause"]["stackTrace"].size()).isGreaterThan(0)
        assertThat(serialized).doesNotContain("moduleName").doesNotContain("detailMessage")
    }

    @Test
    fun `a raw throwable in an admin view serializes through its getters only`() {
        // Write-only admin projections (ActionCheckpoint.result) carry arbitrary throwables.
        val json = mapper.writeValueAsString(RuntimeException("boom"))

        assertThat(json).contains("\"message\":\"boom\"").contains("\"stackTrace\":[")
        assertThat(json).doesNotContain("detailMessage").doesNotContain("suppressedExceptions")
    }
}
