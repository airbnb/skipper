package com.airbnb.skipper.internal.serde

import com.airbnb.skipper.internal.CheckpointTag
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.internal.api.WaitSignal
import com.fasterxml.jackson.databind.ObjectMapper
import io.vavr.control.Either
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Backward-compatibility guard for the Java&rarr;Kotlin source rename of the skipper API value
 * types [WaitSignal] and [ActionCheckpoint].
 *
 * Each test PINS the exact JSON wire format produced by the pre-port Java classes (captured via the
 * production mapper) and proves the Kotlin port serializes byte-identically. These strings are
 * intentionally hardcoded: if the serialized format ever drifts, these tests break before any
 * persisted workflow state becomes unreadable.
 *
 * The mapper under test is the SAME production [ObjectMapper] that [SimplePojoSerde] uses (obtained
 * via `SimplePojoSerde().objectMapper`); it is never reconfigured here.
 */
class ApiTypesBackwardCompatTest {
    private val mapper: ObjectMapper = SimplePojoSerde().objectMapper

    // ── WaitSignal ──

    /**
     * WaitSignal is a persisted value type (Kotlin data class with a `@JsonCreator`), so we pin a
     * FULL round-trip: deserialize the pinned bytes back to the representative instance, and serialize
     * the instance back to the exact same bytes.
     *
     * Note: JavaTimeModule serializes [Duration] as a decimal-seconds NUMBER (`30.000000500`), not as
     * a `{"seconds":..,"nanos":..}` object.
     */
    @Test
    @Throws(Exception::class)
    fun waitSignal_backwardCompat_roundTrip() {
        val instance = WaitSignal(Duration.ofSeconds(30).plusNanos(500))
        val pinnedJson = "{\"waitDuration\":30.000000500}"

        // (a) deserialize pinned bytes -> equals the representative instance (data class equality).
        assertThat(mapper.readValue(pinnedJson, WaitSignal::class.java)).isEqualTo(instance)

        // (b) serialize instance -> byte-identical to the pinned bytes.
        assertThat(mapper.writeValueAsString(instance)).isEqualTo(pinnedJson)
    }

    // ── ActionCheckpoint ──
    //
    // ActionCheckpoint is a WRITE-ONLY admin view type. Its `result` field is an
    // io.vavr.control.Either<Throwable, Object> that is @JsonIgnore'd, it has NO @JsonCreator, and
    // io.vavr.Either has no Jackson creator — so it is NOT deserializable (and never was in the Java
    // baseline; mapper.readValue(json, ActionCheckpoint.class) would throw
    // InvalidDefinitionException).
    // Production code only ever SERIALIZES it. We therefore assert ONLY the serialize direction;
    // asserting a full round-trip would test behavior the pre-port baseline never had.
    //
    // On serialize, the raw Either is hidden and projected by two computed getters:
    //   - @JsonProperty("result")       -> getResultOrError()  (the right value, or the left
    // throwable)
    //   - @JsonProperty("isSuccessful") -> isSuccessful()       (Either.isRight)
    // Plus the @JvmName("isTransient") boolean emits both an "isTransient" key (field) and a
    // "transient" key (getter projection), matching the original Java output.

    /**
     * Pins the success projection: a right-valued Either serializes `"result":"hello-result"` and
     * `"isSuccessful":true`. Full string is pinned because the success payload is small and stable.
     */
    @Test
    @Throws(Exception::class)
    fun actionCheckpoint_backwardCompat_serializesSuccessResult() {
        val tag =
            CheckpointTag.builder()
                .workflowId("wf-123")
                .actionClass(String::class.java)
                .actionMethod("doThing")
                .iteration(2)
                .build()
        val cp =
            ActionCheckpoint.builder()
                .checkpointTag(tag)
                .executionStartTime(Instant.ofEpochMilli(1000))
                .executionEndTime(Instant.ofEpochMilli(2000))
                .result(Either.right("hello-result"))
                .input("the-input")
                .isTransient(true)
                .resultIsAsync(false)
                .build()

        val pinnedJson =
            "{\"checkpointTag\":{\"workflowId\":\"wf-123\",\"actionClass\":\"java.lang.String\"," +
                "\"actionMethod\":\"doThing\",\"iteration\":2,\"checkpointName\":null}," +
                "\"executionStartTime\":1.000000000,\"executionEndTime\":2.000000000," +
                "\"result\":\"hello-result\",\"input\":\"the-input\",\"isTransient\":true," +
                "\"resultIsAsync\":false,\"transient\":true,\"isSuccessful\":true}"

        assertThat(mapper.writeValueAsString(cp)).isEqualTo(pinnedJson)
    }

    /**
     * Exercises the Either.left branch of `getResultOrError()`/`isSuccessful()`: an error result must
     * serialize `"isSuccessful":false` and a non-null serialized throwable under `"result"`.
     *
     * We deliberately do NOT pin a full string here — the serialized exception shape is large and
     * laden with JVM/stacktrace detail that would make the assertion brittle. Instead we deserialize
     * the produced JSON into a generic Map and assert only the load-bearing projection switched
     * correctly.
     */
    @Test
    @Throws(Exception::class)
    fun actionCheckpoint_serializesErrorProjection() {
        val tag =
            CheckpointTag.builder()
                .workflowId("wf-123")
                .actionClass(String::class.java)
                .actionMethod("doThing")
                .iteration(2)
                .build()
        val cp =
            ActionCheckpoint.builder()
                .checkpointTag(tag)
                .executionStartTime(Instant.ofEpochMilli(1000))
                .executionEndTime(Instant.ofEpochMilli(2000))
                .result(Either.left(RuntimeException("boom")))
                .input("the-input")
                .isTransient(true)
                .resultIsAsync(false)
                .build()

        val json = mapper.writeValueAsString(cp)
        @Suppress("UNCHECKED_CAST")
        val map = mapper.readValue(json, Map::class.java) as Map<String, Any>

        // The projection flips to the left branch: not successful, and the serialized throwable (an
        // object, not a scalar) is emitted under "result".
        assertThat(map.get("isSuccessful")).isEqualTo(false)
        assertThat(map.get("result")).isInstanceOf(Map::class.java)
    }
}
