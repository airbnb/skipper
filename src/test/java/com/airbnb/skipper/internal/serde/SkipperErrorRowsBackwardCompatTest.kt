package com.airbnb.skipper.internal.serde

import com.airbnb.skipper.ApplicationError
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.SkipperError
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Golden rows captured from the **released `skipper-core:0.5.0` jar**, before [ThrowableModule]
 * existed, guarding both directions of compatibility for persisted errors:
 *
 * - **Old rows stay readable.** [ROW_WRITTEN_BY_0_5_0_ON_JDK_8] is what every JDK 8 deployment has
 *   written for years; [ROW_WRITTEN_BY_0_5_0_ON_JDK_17] is what a JDK 17 deployment running with
 *   `--add-opens java.base/java.lang=ALL-UNNAMED` wrote (Jackson's bean serializer also emitted the
 *   module fields and the internal `format` byte). Both must deserialize into the same error.
 * - **New rows stay readable by old code.** The current serde must write bytes identical to the JDK 8
 *   row, so a rolled-back 0.5.0 (or a JDK 8 replica in a mixed fleet) reads what this code wrote.
 *
 * The rows were produced by `SimplePojoSerde().serialize(...)` on Corretto 8 and Corretto 17 from the
 * error built by [representativeError], with every stack trace set explicitly so the bytes are
 * deterministic. Regenerate them only from a released jar, never from the code under test.
 */
class SkipperErrorRowsBackwardCompatTest {
    private val serde = SimplePojoSerde()

    @Test
    fun `reads the row a JDK 8 deployment wrote`() {
        assertRepresentativeError(serde.deserialize(ROW_WRITTEN_BY_0_5_0_ON_JDK_8))
    }

    @Test
    fun `reads the row a JDK 17 deployment wrote under add-opens`() {
        assertRepresentativeError(serde.deserialize(ROW_WRITTEN_BY_0_5_0_ON_JDK_17))
    }

    @Test
    fun `writes bytes identical to the JDK 8 row`() {
        assertThat(serde.serialize(representativeError())).isEqualTo(ROW_WRITTEN_BY_0_5_0_ON_JDK_8)
    }

    private fun assertRepresentativeError(deserialized: Any?) {
        val exhausted = deserialized as NonRetryableError
        assertThat(exhausted.message).isEqualTo("action ship has exhausted all retry attempts")
        // NonRetryableError's creator restores the persisted trace; the others keep a live one.
        assertThat(exhausted.stackTrace)
            .containsExactly(StackTraceElement("com.example.OrderWorkflow", "fulfill", "OrderWorkflow.java", 51))

        val retryable = exhausted.cause as RetryableError
        assertThat(retryable.message).isEqualTo("carrier unavailable")

        val application = retryable.cause as ApplicationError
        assertThat(application.message).isEqualTo("503")
        assertThat(application.type).isEqualTo("java.lang.IllegalStateException")
    }

    companion object {
        /** The error whose serialized form the golden rows hold. */
        fun representativeError(): NonRetryableError {
            val cause = IllegalStateException("503")
            cause.stackTrace =
                arrayOf(
                    StackTraceElement("com.example.Carrier", "createShipment", "Carrier.java", 88),
                    StackTraceElement("java.lang.Object", "wait", null, -2), // a native frame
                )
            val retryable = RetryableError("carrier unavailable", cause)
            retryable.stackTrace =
                arrayOf(StackTraceElement("com.example.ShippingActions", "ship", "ShippingActions.java", 23))
            val exhausted = NonRetryableError("action ship has exhausted all retry attempts", retryable as SkipperError)
            exhausted.stackTrace =
                arrayOf(StackTraceElement("com.example.OrderWorkflow", "fulfill", "OrderWorkflow.java", 51))
            return exhausted
        }

        // Captured from skipper-core-0.5.0.jar on Corretto 1.8.0_362.
        const val ROW_WRITTEN_BY_0_5_0_ON_JDK_8 =
            """["com.airbnb.skipper.NonRetryableError","{\"@class\":\"com.airbnb.skipper.NonRetryableError\",\"message\":\"action ship has exhausted all retry attempts\",\"cause\":{\"@class\":\"com.airbnb.skipper.RetryableError\",\"message\":\"carrier unavailable\",\"cause\":{\"@class\":\"com.airbnb.skipper.ApplicationError\",\"type\":\"java.lang.IllegalStateException\",\"message\":\"503\",\"stackTrace\":[{\"declaringClass\":\"com.example.Carrier\",\"methodName\":\"createShipment\",\"fileName\":\"Carrier.java\",\"lineNumber\":88,\"className\":\"com.example.Carrier\",\"nativeMethod\":false},{\"declaringClass\":\"java.lang.Object\",\"methodName\":\"wait\",\"fileName\":null,\"lineNumber\":-2,\"className\":\"java.lang.Object\",\"nativeMethod\":true}]},\"retryCount\":0,\"stackTrace\":[{\"declaringClass\":\"com.example.ShippingActions\",\"methodName\":\"ship\",\"fileName\":\"ShippingActions.java\",\"lineNumber\":23,\"className\":\"com.example.ShippingActions\",\"nativeMethod\":false}],\"type\":\"com.airbnb.skipper.RetryableError\"},\"stackTrace\":[{\"declaringClass\":\"com.example.OrderWorkflow\",\"methodName\":\"fulfill\",\"fileName\":\"OrderWorkflow.java\",\"lineNumber\":51,\"className\":\"com.example.OrderWorkflow\",\"nativeMethod\":false}],\"type\":\"com.airbnb.skipper.NonRetryableError\"}"]"""

        // Captured from skipper-core-0.5.0.jar on Corretto 17.0.20 with --add-opens java.base/java.lang.
        const val ROW_WRITTEN_BY_0_5_0_ON_JDK_17 =
            """["com.airbnb.skipper.NonRetryableError","{\"@class\":\"com.airbnb.skipper.NonRetryableError\",\"message\":\"action ship has exhausted all retry attempts\",\"cause\":{\"@class\":\"com.airbnb.skipper.RetryableError\",\"message\":\"carrier unavailable\",\"cause\":{\"@class\":\"com.airbnb.skipper.ApplicationError\",\"type\":\"java.lang.IllegalStateException\",\"message\":\"503\",\"stackTrace\":[{\"classLoaderName\":null,\"moduleName\":null,\"moduleVersion\":null,\"declaringClass\":\"com.example.Carrier\",\"methodName\":\"createShipment\",\"fileName\":\"Carrier.java\",\"lineNumber\":88,\"format\":0,\"nativeMethod\":false,\"className\":\"com.example.Carrier\"},{\"classLoaderName\":null,\"moduleName\":null,\"moduleVersion\":null,\"declaringClass\":\"java.lang.Object\",\"methodName\":\"wait\",\"fileName\":null,\"lineNumber\":-2,\"format\":0,\"nativeMethod\":true,\"className\":\"java.lang.Object\"}]},\"retryCount\":0,\"stackTrace\":[{\"classLoaderName\":null,\"moduleName\":null,\"moduleVersion\":null,\"declaringClass\":\"com.example.ShippingActions\",\"methodName\":\"ship\",\"fileName\":\"ShippingActions.java\",\"lineNumber\":23,\"format\":0,\"nativeMethod\":false,\"className\":\"com.example.ShippingActions\"}],\"type\":\"com.airbnb.skipper.RetryableError\"},\"stackTrace\":[{\"classLoaderName\":null,\"moduleName\":null,\"moduleVersion\":null,\"declaringClass\":\"com.example.OrderWorkflow\",\"methodName\":\"fulfill\",\"fileName\":\"OrderWorkflow.java\",\"lineNumber\":51,\"format\":0,\"nativeMethod\":false,\"className\":\"com.example.OrderWorkflow\"}],\"type\":\"com.airbnb.skipper.NonRetryableError\"}"]"""
    }
}
