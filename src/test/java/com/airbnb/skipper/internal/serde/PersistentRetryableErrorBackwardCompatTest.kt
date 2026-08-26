package com.airbnb.skipper.internal.serde

import com.airbnb.skipper.FixedRetryStrategy
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.SkipperError
import com.airbnb.skipper.internal.PersistentRetryableError
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Backward-compatibility guard for the Java&rarr;Kotlin source rename of the persisted
 * Jackson type [PersistentRetryableError].
 *
 * [PersistentRetryableError] extends the polymorphic base [SkipperError], which carries
 * `@JsonTypeInfo(use=CLASS, property="@class")`. The wire format therefore embeds the concrete FQCN
 * as a `"@class"` discriminator. The rename MUST keep that FQCN stable
 * (`com.airbnb.skipper.internal.PersistentRetryableError`) and MUST keep `@JsonInclude(NON_NULL)` so
 * persisted workflow state stays readable. This test PINS the exact JSON wire format: if the
 * serialized shape ever drifts, it breaks before any persisted state becomes unreadable.
 *
 * The mapper under test is the SAME production [ObjectMapper] that [SimplePojoSerde] uses (obtained
 * via `SimplePojoSerde().objectMapper`); it is never reconfigured here.
 *
 * **Deterministic stack traces.** [SkipperError]'s constructor populates its `stackTrace` field from
 * the LIVE JVM stack, which would make serialized JSON non-deterministic. We therefore clear the
 * stack trace to a stable empty value on every [SkipperError] in the cause chain before pinning (see
 * [clearStackTraces]). With empty traces, `"stackTrace":[]` appears (an empty list, not null, so
 * `@JsonInclude(NON_NULL)` keeps it).
 */
class PersistentRetryableErrorBackwardCompatTest {
    private val mapper: ObjectMapper = SimplePojoSerde().objectMapper

    /**
     * [PersistentRetryableError] is a persisted polymorphic type, so we pin a FULL round-trip:
     * deserialize the pinned bytes back to an equal instance, and serialize the instance back to the
     * exact same bytes.
     *
     * The representative instance wraps a [RetryableError] cause carrying a [FixedRetryStrategy] and
     * `retryCount=1`, exercising the nested `"@class"` discriminators for all three concrete types and
     * the `NON_NULL` omission of the null `cause`/`retryDelay` fields.
     *
     * Note: [PersistentRetryableError.equals] compares only the type (via `canEqual`), not the cause
     * chain, so the deserialize&rarr;equals assertion is lenient on field contents. The byte-identical
     * RE-serialize assertion is the load-bearing check.
     */
    @Test
    @Throws(Exception::class)
    fun persistentRetryableError_backwardCompat_roundTrip() {
        val instance =
            PersistentRetryableError(
                RetryableError("boom", null, FixedRetryStrategy(Duration.ofSeconds(1), 3), 1)
            )
        clearStackTraces(instance)

        // Note: FixedRetryStrategy emits BOTH "delay" (Duration as a decimal-seconds number) and
        // "maxRetries". It is NOT a SkipperError subtype, so the base's restrictive @JsonAutoDetect
        // does
        // not apply and Jackson auto-detects its public getMaxRetries() getter. The null "clazz",
        // "cause" and "retryDelay" fields are omitted by @JsonInclude(NON_NULL).
        val pinnedJson =
            "{\"@class\":\"com.airbnb.skipper.internal.PersistentRetryableError\"," +
                "\"cause\":{\"@class\":\"com.airbnb.skipper.RetryableError\",\"message\":\"boom\"," +
                "\"retryStrategy\":{\"@class\":\"com.airbnb.skipper.FixedRetryStrategy\"," +
                "\"delay\":1.000000000,\"maxRetries\":3},\"retryCount\":1,\"stackTrace\":[]," +
                "\"type\":\"com.airbnb.skipper.RetryableError\"},\"stackTrace\":[]," +
                "\"type\":\"com.airbnb.skipper.internal.PersistentRetryableError\",\"message\":\"boom\"}"

        // (b) serialize instance -> byte-identical to the pinned bytes. This is the load-bearing
        // assertion: it guards the @class discriminators (stable FQCNs) and the full field shape.
        assertThat(mapper.writeValueAsString(instance)).isEqualTo(pinnedJson)

        // (a) deserialize pinned bytes -> equal instance (lenient type-only equality, see javadoc).
        assertThat(mapper.readValue(pinnedJson, PersistentRetryableError::class.java)).isEqualTo(instance)
    }

    companion object {
        /**
         * Recursively replaces the live-captured stack trace on every [SkipperError] in the cause
         * chain with a stable empty array. [SkipperError.setStackTrace] also resets the
         * `@JsonProperty List<StackTraceElement> stackTrace` field, so the serialized `stackTrace`
         * becomes a deterministic empty list.
         */
        private fun clearStackTraces(error: SkipperError) {
            var current: SkipperError? = error
            while (current != null) {
                current.setStackTrace(emptyArray())
                current = current.cause
            }
        }
    }
}
