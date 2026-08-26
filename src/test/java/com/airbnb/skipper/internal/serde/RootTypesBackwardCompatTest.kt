package com.airbnb.skipper.internal.serde

import com.airbnb.skipper.ApplicationError
import com.airbnb.skipper.CancelledWorkflow
import com.airbnb.skipper.ExponentialRetryStrategy
import com.airbnb.skipper.FixedRetryStrategy
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.OptimisticLockingError
import com.airbnb.skipper.PersistentRetryStrategy
import com.airbnb.skipper.RetryStrategy
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.SkipperError
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Backward-compatibility guard for the Java&rarr;Kotlin source rename of the eight persisted
 * Jackson types in the root `com.airbnb.skipper` package: the [SkipperError] subtypes
 * ([ApplicationError], [CancelledWorkflow], [NonRetryableError], [RetryableError],
 * [OptimisticLockingError]) and the [RetryStrategy] implementations ([FixedRetryStrategy],
 * [ExponentialRetryStrategy], [PersistentRetryStrategy]).
 *
 * Both [SkipperError] and [RetryStrategy] carry `@JsonTypeInfo(use=CLASS, property="@class")`, so
 * every wire format embeds the concrete FQCN as a `"@class"` discriminator. The rename MUST keep
 * those FQCNs stable (all remain under `com.airbnb.skipper`) so persisted workflow state and retry
 * configuration stay readable. Each test PINS the exact JSON wire format produced by the pre-port
 * Java classes: if the serialized shape ever drifts, these tests break before any persisted state
 * becomes unreadable.
 *
 * The pinned strings were captured by running the pre-port Java classes (checked out at the commit
 * before this port) through the SAME production mapper used here, and confirmed byte-for-byte
 * identical to the Kotlin port's output.
 *
 * The mapper under test is the SAME production [ObjectMapper] that [SimplePojoSerde] uses (obtained
 * via `SimplePojoSerde().objectMapper`); it is never reconfigured here.
 *
 * **Deterministic stack traces.** [SkipperError]'s constructor populates its `stackTrace` field from
 * the LIVE JVM stack, which would make serialized JSON non-deterministic. We therefore clear the
 * stack trace to a stable empty value on every [SkipperError] in the cause chain before pinning (see
 * [clearStackTraces]). With empty traces, `"stackTrace":[]` appears (an empty list, not null, so
 * `@JsonInclude(NON_NULL)` keeps it).
 *
 * **[Duration] format.** The registered JavaTimeModule serializes [Duration] as a decimal-seconds
 * NUMBER (`30.000000500`), not as a `{"seconds":..,"nanos":..}` object.
 */
class RootTypesBackwardCompatTest {
    private val mapper: ObjectMapper = SimplePojoSerde().objectMapper

    // ── FixedRetryStrategy ──

    /**
     * [FixedRetryStrategy] is a persisted polymorphic value type with a `@JsonCreator`, so we pin a
     * FULL round-trip: deserialize the pinned bytes to an equal instance, and serialize the instance
     * back to the exact same bytes. The `"@class"` discriminator and the decimal-seconds `"delay"` are
     * the load-bearing pins.
     */
    @Test
    @Throws(Exception::class)
    fun fixedRetryStrategy_backwardCompat_roundTrip() {
        val instance = FixedRetryStrategy(Duration.ofSeconds(2), 5)
        val pinnedJson =
            "{\"@class\":\"com.airbnb.skipper.FixedRetryStrategy\",\"delay\":2.000000000," +
                "\"maxRetries\":5}"

        assertThat(mapper.writeValueAsString(instance)).isEqualTo(pinnedJson)
        assertThat(mapper.readValue(pinnedJson, FixedRetryStrategy::class.java)).isEqualTo(instance)
    }

    // ── ExponentialRetryStrategy ──

    /**
     * Field order follows the `@JsonCreator` param order (`initialDelay`, `maxRetries`, `multiplier`,
     * `maxDelay`), not declaration order. `multiplier` serializes as a bare double (`2.0`).
     */
    @Test
    @Throws(Exception::class)
    fun exponentialRetryStrategy_backwardCompat_roundTrip() {
        val instance =
            ExponentialRetryStrategy(Duration.ofSeconds(1), 4, 2.0, Duration.ofSeconds(60))
        val pinnedJson =
            "{\"@class\":\"com.airbnb.skipper.ExponentialRetryStrategy\",\"initialDelay\":1.000000000," +
                "\"maxRetries\":4,\"multiplier\":2.0,\"maxDelay\":60.000000000}"

        assertThat(mapper.writeValueAsString(instance)).isEqualTo(pinnedJson)
        assertThat(mapper.readValue(pinnedJson, ExponentialRetryStrategy::class.java)).isEqualTo(instance)
    }

    // ── PersistentRetryStrategy ──

    /**
     * [PersistentRetryStrategy] nests another [RetryStrategy], so the pinned JSON carries a nested
     * `"@class"` discriminator for the wrapped [FixedRetryStrategy]. The `isPersistentRetry()`
     * override is `@JsonIgnore`'d and MUST NOT appear in the wire format.
     */
    @Test
    @Throws(Exception::class)
    fun persistentRetryStrategy_backwardCompat_roundTrip() {
        val instance =
            PersistentRetryStrategy(FixedRetryStrategy(Duration.ofSeconds(2), 5))
        val pinnedJson =
            "{\"@class\":\"com.airbnb.skipper.PersistentRetryStrategy\",\"retryStrategy\":" +
                "{\"@class\":\"com.airbnb.skipper.FixedRetryStrategy\",\"delay\":2.000000000," +
                "\"maxRetries\":5}}"

        assertThat(mapper.writeValueAsString(instance)).isEqualTo(pinnedJson)
        assertThat(mapper.readValue(pinnedJson, PersistentRetryStrategy::class.java)).isEqualTo(instance)
    }

    /**
     * Deserializing a strategy through the polymorphic [RetryStrategy] interface (how
     * [RetryableError.getRetryStrategy] is typed) MUST resolve the concrete type from the `"@class"`
     * discriminator. Guards that the discriminator drives subtype resolution, not just fixed-type
     * reads.
     */
    @Test
    @Throws(Exception::class)
    fun retryStrategy_backwardCompat_deserializesViaPolymorphicInterface() {
        val instance = FixedRetryStrategy(Duration.ofSeconds(2), 5)
        val pinnedJson =
            "{\"@class\":\"com.airbnb.skipper.FixedRetryStrategy\",\"delay\":2.000000000," +
                "\"maxRetries\":5}"

        val deserialized = mapper.readValue(pinnedJson, RetryStrategy::class.java)
        assertThat(deserialized).isInstanceOf(FixedRetryStrategy::class.java).isEqualTo(instance)
    }

    // ── CancelledWorkflow ──

    /**
     * [CancelledWorkflow] is a persisted [SkipperError] subtype. Field order follows the base's
     * declaration order (`message`, `stackTrace`, `type`) with the null `clazz`/`cause` fields omitted
     * by `@JsonInclude(NON_NULL)`. Its `equals` is type-only (Lombok `@Value`-style `canEqual`), so the
     * deserialize&rarr;equals assertion is lenient on field contents; the byte-identical serialize
     * assertion is the load-bearing check.
     */
    @Test
    @Throws(Exception::class)
    fun cancelledWorkflow_backwardCompat_roundTrip() {
        val instance = CancelledWorkflow("cancel-reason")
        clearStackTraces(instance)
        val pinnedJson =
            "{\"@class\":\"com.airbnb.skipper.CancelledWorkflow\",\"message\":\"cancel-reason\"," +
                "\"stackTrace\":[],\"type\":\"com.airbnb.skipper.CancelledWorkflow\"}"

        assertThat(mapper.writeValueAsString(instance)).isEqualTo(pinnedJson)
        assertThat(mapper.readValue(pinnedJson, CancelledWorkflow::class.java)).isEqualTo(instance)
    }

    // ── NonRetryableError ──

    /**
     * [NonRetryableError] is a persisted [SkipperError] subtype. Its 3-arg `@JsonCreator` only calls
     * `setStackTrace` when the incoming `stackTrace` is NON-empty; for the pinned empty-trace instance
     * the deserialized copy captures a FRESH live stack (via the `RuntimeException` super ctor), so a
     * full round-trip does NOT re-serialize byte-identically. This mirrors the pre-port Java behavior
     * exactly. We therefore pin the serialize direction (load-bearing) and assert the deserialized
     * instance is `equals` (type-only `canEqual` equality, so lenient on the regenerated trace).
     */
    @Test
    @Throws(Exception::class)
    fun nonRetryableError_backwardCompat_serializeAndLenientDeserialize() {
        val instance = NonRetryableError("fatal", null as SkipperError?)
        clearStackTraces(instance)
        val pinnedJson =
            "{\"@class\":\"com.airbnb.skipper.NonRetryableError\",\"message\":\"fatal\"," +
                "\"stackTrace\":[],\"type\":\"com.airbnb.skipper.NonRetryableError\"}"

        assertThat(mapper.writeValueAsString(instance)).isEqualTo(pinnedJson)
        assertThat(mapper.readValue(pinnedJson, NonRetryableError::class.java)).isEqualTo(instance)
    }

    // ── RetryableError ──

    /**
     * [RetryableError] is a persisted [SkipperError] subtype whose `@JsonCreator` params (`message`,
     * `cause`, `retryStrategy`, `retryCount`) serialize first, followed by the base's remaining
     * declaration-order fields (`stackTrace`, `type`). The nested `retryStrategy` carries its own
     * `"@class"` discriminator; the null `cause`/`retryDelay` are omitted by `@JsonInclude(NON_NULL)`.
     *
     * Note: [RetryableError.equals] compares `retryCount`, the `getRetryStrategy()` Optional, and
     * `retryDelay` (plus type via `canEqual`) — enough for this instance to round-trip to an equal
     * value.
     */
    @Test
    @Throws(Exception::class)
    fun retryableError_backwardCompat_roundTrip() {
        val instance =
            RetryableError("boom", null, FixedRetryStrategy(Duration.ofSeconds(1), 3), 1)
        clearStackTraces(instance)
        val pinnedJson =
            "{\"@class\":\"com.airbnb.skipper.RetryableError\",\"message\":\"boom\"," +
                "\"retryStrategy\":{\"@class\":\"com.airbnb.skipper.FixedRetryStrategy\"," +
                "\"delay\":1.000000000,\"maxRetries\":3},\"retryCount\":1,\"stackTrace\":[]," +
                "\"type\":\"com.airbnb.skipper.RetryableError\"}"

        assertThat(mapper.writeValueAsString(instance)).isEqualTo(pinnedJson)
        assertThat(mapper.readValue(pinnedJson, RetryableError::class.java)).isEqualTo(instance)
    }

    // ── OptimisticLockingError ──

    /**
     * [OptimisticLockingError] extends [RetryableError] but declares no own `@JsonCreator`, so a
     * `@JsonPropertyOrder("stackTrace", "type", "message", "retryStrategy", "retryCount")` annotation
     * pins the exact pre-port Java wire order. It always carries a
     * [FixedRetryStrategy]([Duration.ZERO], 10) — note [Duration.ZERO] serializes as `0.0`. It
     * deserializes back to an equal instance via the inherited [RetryableError.equals].
     */
    @Test
    @Throws(Exception::class)
    fun optimisticLockingError_backwardCompat_roundTrip() {
        val instance = OptimisticLockingError("version-conflict")
        clearStackTraces(instance)
        val pinnedJson =
            "{\"@class\":\"com.airbnb.skipper.OptimisticLockingError\",\"stackTrace\":[]," +
                "\"type\":\"com.airbnb.skipper.OptimisticLockingError\",\"message\":\"version-conflict\"," +
                "\"retryStrategy\":{\"@class\":\"com.airbnb.skipper.FixedRetryStrategy\"," +
                "\"delay\":0.0,\"maxRetries\":10},\"retryCount\":0}"

        assertThat(mapper.writeValueAsString(instance)).isEqualTo(pinnedJson)
        assertThat(mapper.readValue(pinnedJson, OptimisticLockingError::class.java)).isEqualTo(instance)
    }

    // ── ApplicationError ──

    /**
     * [ApplicationError] wraps an arbitrary [Throwable]: its `"type"` field holds the WRAPPED
     * throwable's class name (here `java.lang.IllegalStateException`, NOT `ApplicationError`), and
     * nested causes are wrapped recursively, each with its own `"@class"` discriminator.
     *
     * [ApplicationError] inherits [RuntimeException]'s identity `equals`, so a deserialized instance
     * is never `.equals` to the original. We therefore pin the serialize direction (load-bearing) and
     * prove the round-trip preserves the wire form by asserting the deserialized instance
     * RE-serializes byte-identically to the pinned bytes.
     */
    @Test
    @Throws(Exception::class)
    fun applicationError_backwardCompat_serializeAndReserialize() {
        val instance =
            ApplicationError.fromException(IllegalStateException("app-boom"))!!
        clearStackTraces(instance)
        val pinnedJson =
            "{\"@class\":\"com.airbnb.skipper.ApplicationError\"," +
                "\"type\":\"java.lang.IllegalStateException\",\"message\":\"app-boom\"," +
                "\"stackTrace\":[]}"

        assertThat(mapper.writeValueAsString(instance)).isEqualTo(pinnedJson)

        val deserialized = mapper.readValue(pinnedJson, ApplicationError::class.java)
        assertThat(mapper.writeValueAsString(deserialized)).isEqualTo(pinnedJson)
        assertThat(deserialized.type).isEqualTo("java.lang.IllegalStateException")
        assertThat(deserialized.message).isEqualTo("app-boom")
    }

    /**
     * A nested cause chain: `ApplicationError.fromException` recursively wraps the cause, so the
     * pinned JSON nests a second [ApplicationError] under `"cause"` whose `"type"` is the inner
     * throwable's class. Pins the recursive-wrap wire format.
     */
    @Test
    @Throws(Exception::class)
    fun applicationError_backwardCompat_nestedCauseChain() {
        val instance =
            ApplicationError.fromException(
                RuntimeException("outer", IllegalArgumentException("inner"))
            )!!
        clearStackTraces(instance)
        val pinnedJson =
            "{\"@class\":\"com.airbnb.skipper.ApplicationError\"," +
                "\"type\":\"java.lang.RuntimeException\",\"message\":\"outer\"," +
                "\"cause\":{\"@class\":\"com.airbnb.skipper.ApplicationError\"," +
                "\"type\":\"java.lang.IllegalArgumentException\",\"message\":\"inner\"," +
                "\"stackTrace\":[]},\"stackTrace\":[]}"

        assertThat(mapper.writeValueAsString(instance)).isEqualTo(pinnedJson)

        val deserialized = mapper.readValue(pinnedJson, ApplicationError::class.java)
        assertThat(mapper.writeValueAsString(deserialized)).isEqualTo(pinnedJson)
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
