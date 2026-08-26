package com.airbnb.skipper.statemachine

import com.airbnb.skipper.statemachine.SkipperStateMachine.PreciseTimeRange
import com.airbnb.skipper.statemachine.SkipperStateMachine.PreciseTimestamp
import com.airbnb.skipper.statemachine.SkipperStateMachine.TransitionOutcome
import com.airbnb.skipper.statemachine.SkipperStateMachine.TriggerKind
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactAfterHookRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactEventRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactJournalSegment
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactSpan
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactStateSnapshot
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactTimeoutRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactTransitionRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.RuntimeState
import com.github.luben.zstd.Zstd
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StateMachineStateCodecTest {
    // ── Encode / decode round-trips ──

    @Test
    fun `encode then decode round-trips an empty RuntimeState`() {
        val original = RuntimeState()
        val encoded = StateMachineStateCodec.encode(original)
        val decoded = StateMachineStateCodec.decode(encoded, "wf-1")

        assertThat(decoded.stateNames).isEmpty()
        assertThat(decoded.eventTypeNames).isEmpty()
        assertThat(decoded.events).isEmpty()
        assertThat(decoded.afterHooks).isEmpty()
        assertThat(decoded.timeouts).isEmpty()
        assertThat(decoded.transitions).isEmpty()
    }

    @Test
    fun `encode then decode round-trips a fully populated RuntimeState`() {
        val original = RuntimeState()
        with(StateMachineStateCodec) {
            original.stateId("IDLE")
            original.stateId("RUNNING")
            original.eventTypeId("Go")
            original.events.add(CompactEventRecord(typeId = 0, receivedAtEpochNanos = 1_000_000_000L))
            original.transitions.add(
                CompactTransitionRecord(
                    fromStateId = 0,
                    toStateId = 1,
                    outcome = TransitionOutcome.TRANSITION_TO,
                    triggerKind = TriggerKind.EVENT,
                    stateEntryEpochNanos = 2_000_000_000L,
                    eventIndex = 0,
                    span = CompactSpan(startEpochNanos = 1_000_000_000L, durationNanos = 1_000_000_000L),
                ),
            )
        }

        val encoded = StateMachineStateCodec.encode(original)
        val decoded = StateMachineStateCodec.decode(encoded, "wf-1")

        assertThat(decoded.stateNames).containsExactly("IDLE", "RUNNING")
        assertThat(decoded.eventTypeNames).containsExactly("Go")
        assertThat(decoded.events).hasSize(1)
        assertThat(decoded.transitions).hasSize(1)
        assertThat(decoded.transitions[0].fromStateId).isEqualTo(0)
        assertThat(decoded.transitions[0].toStateId).isEqualTo(1)
    }

    // ── Journal segment round-trips ──

    @Test
    fun `transition journal segment round-trips compact bytes`() {
        val transition = CompactTransitionRecord(
            fromStateId = 0,
            toStateId = 1,
            outcome = TransitionOutcome.TRANSITION_TO,
            triggerKind = TriggerKind.EVENT,
            stateEntryEpochNanos = 2_000_000_000L,
            eventIndex = 0,
            span = CompactSpan(startEpochNanos = 1_000_000_000L, durationNanos = 1_000L),
        )

        val decoded = StateMachineStateCodec.decodeTransitionSegment(
            StateMachineStateCodec.encodeTransitionSegment(transition),
            "wf-transition",
        )

        assertThat(decoded).isEqualTo(transition)
    }

    @Test
    fun `after hook journal segment round-trips compact bytes`() {
        val afterHook = CompactAfterHookRecord(
            firedAtEpochNanos = 3_000_000_000L,
            durationNanos = Duration.ofMinutes(5).toNanos(),
            handlerSpan = CompactSpan(startEpochNanos = 3_000_000_000L, durationNanos = 500L),
        )

        val decoded = StateMachineStateCodec.decodeAfterHookSegment(
            StateMachineStateCodec.encodeAfterHookSegment(afterHook),
            "wf-after",
        )

        assertThat(decoded).isEqualTo(afterHook)
    }

    @Test
    fun `timeout journal segment round-trips compact bytes`() {
        val timeout = CompactTimeoutRecord(
            firedAtEpochNanos = 4_000_000_000L,
            durationNanos = Duration.ofHours(2).toNanos(),
        )

        val decoded = StateMachineStateCodec.decodeTimeoutSegment(
            StateMachineStateCodec.encodeTimeoutSegment(timeout),
            "wf-timeout",
        )

        assertThat(decoded).isEqualTo(timeout)
    }

    @Test
    fun `timestamp marker journal segment round-trips compact bytes`() {
        val decoded = StateMachineStateCodec.decodeTimestampMarkerSegment(
            StateMachineStateCodec.encodeTimestampMarkerSegment(6_000_000_000L),
            "wf-marker",
        )

        assertThat(decoded).isEqualTo(6_000_000_000L)
    }

    // ── Decode failures ──

    @Test
    fun `decode returns empty RuntimeState for empty byte array`() {
        val decoded = StateMachineStateCodec.decode(ByteArray(0), "wf-empty")
        assertThat(decoded.stateNames).isEmpty()
        assertThat(decoded.transitions).isEmpty()
    }

    @Test
    fun `PersistedStateBlob equality uses byte content`() {
        val left = SkipperStateMachine.PersistedStateBlob(byteArrayOf(1, 2, 3))
        val right = SkipperStateMachine.PersistedStateBlob(byteArrayOf(1, 2, 3))

        assertThat(left).isEqualTo(right)
        assertThat(left.hashCode()).isEqualTo(right.hashCode())
    }

    @Test
    fun `decode throws on corrupt data`() {
        val corruptData = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        assertThatThrownBy { StateMachineStateCodec.decode(corruptData, "wf-corrupt") }
            .isInstanceOf(Exception::class.java)
    }

    // ── Timestamp conversions ──

    @Test
    fun `PreciseTimestamp toEpochNanos combines seconds and nanos`() {
        with(StateMachineStateCodec) {
            val ts = PreciseTimestamp(epochSecond = 10, nano = 500_000_000)
            assertThat(ts.toEpochNanos()).isEqualTo(10_500_000_000L)
        }
    }

    @Test
    fun `toPreciseTimestamp and toEpochNanos round-trip`() {
        with(StateMachineStateCodec) {
            val original = 12_345_678_901L
            val ts = original.toPreciseTimestamp()
            assertThat(ts.toEpochNanos()).isEqualTo(original)
        }
    }

    @Test
    fun `toInstant converts epoch nanos to Instant`() {
        with(StateMachineStateCodec) {
            val epochNanos = 1_500_000_000_123_456_789L
            val instant = epochNanos.toInstant()
            assertThat(instant).isEqualTo(Instant.ofEpochSecond(1_500_000_000L, 123_456_789L))
        }
    }

    // ── Span conversions ──

    @Test
    fun `CompactSpan to PreciseTimeRange round-trip`() {
        with(StateMachineStateCodec) {
            val span = CompactSpan(startEpochNanos = 5_000_000_000L, durationNanos = 2_000_000_000L)
            val range = span.toPreciseTimeRange()

            assertThat(range.start.toEpochNanos()).isEqualTo(5_000_000_000L)
            assertThat(range.end.toEpochNanos()).isEqualTo(7_000_000_000L)

            val roundTripped = range.toCompactSpan()
            assertThat(roundTripped.startEpochNanos).isEqualTo(span.startEpochNanos)
            assertThat(roundTripped.durationNanos).isEqualTo(span.durationNanos)
        }
    }

    @Test
    fun `CompactSpan with zero duration round-trips`() {
        with(StateMachineStateCodec) {
            val span = CompactSpan(startEpochNanos = 1_000_000_000L, durationNanos = 0L)
            val range = span.toPreciseTimeRange()
            assertThat(range.start).isEqualTo(range.end)

            val roundTripped = range.toCompactSpan()
            assertThat(roundTripped.durationNanos).isEqualTo(0L)
        }
    }

    // ── Name dictionary helpers ──

    @Test
    fun `stateId assigns sequential ids`() {
        with(StateMachineStateCodec) {
            val state = RuntimeState()
            assertThat(state.stateId("IDLE")).isEqualTo(0)
            assertThat(state.stateId("RUNNING")).isEqualTo(1)
            assertThat(state.stateId("DONE")).isEqualTo(2)
        }
    }

    @Test
    fun `stateId is idempotent`() {
        with(StateMachineStateCodec) {
            val state = RuntimeState()
            assertThat(state.stateId("IDLE")).isEqualTo(0)
            assertThat(state.stateId("IDLE")).isEqualTo(0)
            assertThat(state.stateNames).hasSize(1)
        }
    }

    @Test
    fun `eventTypeId assigns sequential ids`() {
        with(StateMachineStateCodec) {
            val state = RuntimeState()
            assertThat(state.eventTypeId("Go")).isEqualTo(0)
            assertThat(state.eventTypeId("Complete")).isEqualTo(1)
        }
    }

    @Test
    fun `eventTypeId is idempotent`() {
        with(StateMachineStateCodec) {
            val state = RuntimeState()
            assertThat(state.eventTypeId("Go")).isEqualTo(0)
            assertThat(state.eventTypeId("Go")).isEqualTo(0)
            assertThat(state.eventTypeNames).hasSize(1)
        }
    }

    // ── State queries ──

    @Test
    fun `currentStateId returns null for empty RuntimeState`() {
        assertThat(StateMachineStateCodec.currentStateId(RuntimeState())).isNull()
    }

    @Test
    fun `currentStateEntryEpochNanos returns null for empty RuntimeState`() {
        assertThat(StateMachineStateCodec.currentStateEntryEpochNanos(RuntimeState())).isNull()
    }

    @Test
    fun `clearReplayDerivedHistory preserves dictionaries and durable event log`() {
        val state = RuntimeState()
        with(StateMachineStateCodec) {
            state.stateId("IDLE")
            state.eventTypeId("Go")
            state.events.add(CompactEventRecord(typeId = 0, receivedAtEpochNanos = 1_000_000_000L))
            state.afterHooks.add(CompactAfterHookRecord(firedAtEpochNanos = 2_000_000_000L, durationNanos = 1L))
            state.timeouts.add(CompactTimeoutRecord(firedAtEpochNanos = 3_000_000_000L, durationNanos = 2L))
            state.transitions.add(
                CompactTransitionRecord(
                    toStateId = 0,
                    outcome = TransitionOutcome.TRANSITION_TO,
                    triggerKind = TriggerKind.INITIAL,
                    stateEntryEpochNanos = 1_000_000_000L,
                    span = CompactSpan(startEpochNanos = 0L, durationNanos = 1L),
                ),
            )

            state.clearReplayDerivedHistory()
        }

        assertThat(state.stateNames).containsExactly("IDLE")
        assertThat(state.eventTypeNames).containsExactly("Go")
        assertThat(state.events).hasSize(1)
        assertThat(state.afterHooks).isEmpty()
        assertThat(state.timeouts).isEmpty()
        assertThat(state.transitions).isEmpty()
    }

    @Test
    fun `currentStateId returns the toStateId of the last transition with stateEntryEpochNanos`() {
        val state = RuntimeState()
        with(StateMachineStateCodec) {
            state.stateId("IDLE")
            state.stateId("RUNNING")
        }
        state.transitions.add(
            CompactTransitionRecord(
                fromStateId = null,
                toStateId = 0,
                outcome = TransitionOutcome.TRANSITION_TO,
                triggerKind = TriggerKind.INITIAL,
                stateEntryEpochNanos = 1_000_000_000L,
                span = CompactSpan(startEpochNanos = 0L, durationNanos = 1_000_000_000L),
            ),
        )
        state.transitions.add(
            CompactTransitionRecord(
                fromStateId = 0,
                toStateId = 1,
                outcome = TransitionOutcome.TRANSITION_TO,
                triggerKind = TriggerKind.EVENT,
                stateEntryEpochNanos = 2_000_000_000L,
                span = CompactSpan(startEpochNanos = 1_000_000_000L, durationNanos = 1_000_000_000L),
            ),
        )

        assertThat(StateMachineStateCodec.currentStateId(state)).isEqualTo(1)
        assertThat(StateMachineStateCodec.currentStateEntryEpochNanos(state)).isEqualTo(2_000_000_000L)
    }

    // ── resolveStateName / resolveEventTypeAlias ──

    @Test
    fun `resolveStateName returns the name for a valid stateId`() {
        val state = RuntimeState()
        with(StateMachineStateCodec) { state.stateId("ACTIVE") }
        assertThat(StateMachineStateCodec.resolveStateName(state, 0, "wf-1")).isEqualTo("ACTIVE")
    }

    @Test
    fun `resolveStateName throws for an invalid stateId`() {
        val state = RuntimeState()
        assertThatThrownBy { StateMachineStateCodec.resolveStateName(state, 99, "wf-1") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Missing state id 99")
    }

    @Test
    fun `resolveEventTypeAlias returns the alias for a valid typeId`() {
        val state = RuntimeState()
        with(StateMachineStateCodec) { state.eventTypeId("Go") }
        assertThat(StateMachineStateCodec.resolveEventTypeAlias(state, 0, "wf-1")).isEqualTo("Go")
    }

    @Test
    fun `resolveEventTypeAlias throws for an invalid typeId`() {
        val state = RuntimeState()
        assertThatThrownBy { StateMachineStateCodec.resolveEventTypeAlias(state, 99, "wf-1") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Missing event type id 99")
    }

    // ── triggerMetadata ──

    @Test
    fun `triggerMetadata for Event returns EVENT kind and null duration`() {
        val (kind, nanos) = StateMachineStateCodec.triggerMetadata(TransitionTrigger.Event("go"))
        assertThat(kind).isEqualTo(TriggerKind.EVENT)
        assertThat(nanos).isNull()
    }

    @Test
    fun `triggerMetadata for Timeout returns TIMEOUT kind and duration nanos`() {
        val (kind, nanos) = StateMachineStateCodec.triggerMetadata(TransitionTrigger.Timeout(Duration.ofSeconds(30)))
        assertThat(kind).isEqualTo(TriggerKind.TIMEOUT)
        assertThat(nanos).isEqualTo(30_000_000_000L)
    }

    @Test
    fun `triggerMetadata for AutoTransition returns AUTO_TRANSITION kind and null duration`() {
        val (kind, nanos) = StateMachineStateCodec.triggerMetadata(TransitionTrigger.AutoTransition)
        assertThat(kind).isEqualTo(TriggerKind.AUTO_TRANSITION)
        assertThat(nanos).isNull()
    }

    @Test
    fun `triggerMetadata for InitialState returns INITIAL kind and null duration`() {
        val (kind, nanos) = StateMachineStateCodec.triggerMetadata(TransitionTrigger.InitialState)
        assertThat(kind).isEqualTo(TriggerKind.INITIAL)
        assertThat(nanos).isNull()
    }

    // ── buildPendingTransition ──

    @Test
    fun `buildPendingTransition populates all fields`() {
        val start = PreciseTimestamp(epochSecond = 100, nano = 0)
        val pending = StateMachineStateCodec.buildPendingTransition(
            fromStateName = "IDLE",
            toStateName = "RUNNING",
            outcome = TransitionOutcome.TRANSITION_TO,
            triggerKind = TriggerKind.EVENT,
            timeoutDurationNanos = null,
            transitionStart = start,
            eventIndex = 3,
        )

        assertThat(pending.fromStateName).isEqualTo("IDLE")
        assertThat(pending.toStateName).isEqualTo("RUNNING")
        assertThat(pending.outcome).isEqualTo(TransitionOutcome.TRANSITION_TO)
        assertThat(pending.triggerKind).isEqualTo(TriggerKind.EVENT)
        assertThat(pending.transitionStart).isEqualTo(start)
        assertThat(pending.eventIndex).isEqualTo(3)
        assertThat(pending.timeoutDurationNanos).isNull()
    }

    // ── transitionRecord ──

    @Test
    fun `transitionRecord resolves state names to ids`() {
        val state = RuntimeState()
        with(StateMachineStateCodec) {
            state.stateId("IDLE")
            state.stateId("RUNNING")

            val pending = buildPendingTransition(
                fromStateName = "IDLE",
                toStateName = "RUNNING",
                outcome = TransitionOutcome.TRANSITION_TO,
                triggerKind = TriggerKind.EVENT,
                timeoutDurationNanos = null,
                transitionStart = PreciseTimestamp(1, 0),
            )

            val span = PreciseTimeRange(
                start = PreciseTimestamp(1, 0),
                end = PreciseTimestamp(2, 0),
            )

            val record = state.transitionRecord(pending, span, stateEntryEpochNanos = 2_000_000_000L)
            assertThat(record.fromStateId).isEqualTo(0)
            assertThat(record.toStateId).isEqualTo(1)
            assertThat(record.stateEntryEpochNanos).isEqualTo(2_000_000_000L)
            assertThat(record.outcome).isEqualTo(TransitionOutcome.TRANSITION_TO)
        }
    }

    // ── materializeTransition ──

    @Test
    fun `materializeTransition produces full TransitionLogEntry`() {
        val state = RuntimeState()
        with(StateMachineStateCodec) {
            state.stateId("IDLE")
            state.stateId("RUNNING")
            state.eventTypeId("Go")
            state.events.add(CompactEventRecord(typeId = 0, receivedAtEpochNanos = 1_000_000_000L))
        }

        val record = CompactTransitionRecord(
            fromStateId = 0,
            toStateId = 1,
            outcome = TransitionOutcome.TRANSITION_TO,
            triggerKind = TriggerKind.EVENT,
            stateEntryEpochNanos = 2_000_000_000L,
            eventIndex = 0,
            span = CompactSpan(startEpochNanos = 1_000_000_000L, durationNanos = 1_000_000_000L),
        )

        val entry = StateMachineStateCodec.materializeTransition(state, record)
        assertThat(entry.fromState).isEqualTo("IDLE")
        assertThat(entry.toState).isEqualTo("RUNNING")
        assertThat(entry.outcome).isEqualTo(TransitionOutcome.TRANSITION_TO)
        assertThat(entry.triggerKind).isEqualTo(TriggerKind.EVENT)
        assertThat(entry.triggerName).isEqualTo("Go")
        assertThat(entry.eventIndex).isEqualTo(0)
    }

    @Test
    fun `materializeTransition handles null fromStateId and toStateId`() {
        val state = RuntimeState()
        with(StateMachineStateCodec) { state.stateId("IDLE") }

        val record = CompactTransitionRecord(
            fromStateId = null,
            toStateId = null,
            outcome = TransitionOutcome.IGNORE_EXPLICIT,
            triggerKind = TriggerKind.AUTO_TRANSITION,
            span = CompactSpan(startEpochNanos = 0L, durationNanos = 100L),
        )

        val entry = StateMachineStateCodec.materializeTransition(state, record)
        assertThat(entry.fromState).isNull()
        assertThat(entry.toState).isNull()
    }

    // ── deriveTriggerName ──

    @Test
    fun `deriveTriggerName for EVENT with valid eventIndex`() {
        val state = RuntimeState()
        with(StateMachineStateCodec) {
            state.eventTypeId("Go")
            state.events.add(CompactEventRecord(typeId = 0, receivedAtEpochNanos = 1_000_000_000L))
        }

        val record = CompactTransitionRecord(
            fromStateId = null,
            toStateId = null,
            outcome = TransitionOutcome.TRANSITION_TO,
            triggerKind = TriggerKind.EVENT,
            eventIndex = 0,
            span = CompactSpan(startEpochNanos = 0L, durationNanos = 100L),
        )

        assertThat(StateMachineStateCodec.deriveTriggerName(state, record)).isEqualTo("Go")
    }

    @Test
    fun `deriveTriggerName for EVENT without eventIndex returns Unknown`() {
        val state = RuntimeState()
        val record = CompactTransitionRecord(
            fromStateId = null,
            toStateId = null,
            outcome = TransitionOutcome.TRANSITION_TO,
            triggerKind = TriggerKind.EVENT,
            eventIndex = null,
            span = CompactSpan(startEpochNanos = 0L, durationNanos = 100L),
        )

        assertThat(StateMachineStateCodec.deriveTriggerName(state, record)).isEqualTo("Unknown")
    }

    @Test
    fun `deriveTriggerName for TIMEOUT with timeoutDurationNanos`() {
        val state = RuntimeState()
        val record = CompactTransitionRecord(
            fromStateId = null,
            toStateId = null,
            outcome = TransitionOutcome.TRANSITION_TO,
            triggerKind = TriggerKind.TIMEOUT,
            timeoutDurationNanos = Duration.ofMinutes(5).toNanos(),
            span = CompactSpan(startEpochNanos = 0L, durationNanos = 100L),
        )

        assertThat(StateMachineStateCodec.deriveTriggerName(state, record)).isEqualTo("timeout(PT5M)")
    }

    @Test
    fun `deriveTriggerName for TIMEOUT without timeoutDurationNanos`() {
        val state = RuntimeState()
        val record = CompactTransitionRecord(
            fromStateId = null,
            toStateId = null,
            outcome = TransitionOutcome.TRANSITION_TO,
            triggerKind = TriggerKind.TIMEOUT,
            timeoutDurationNanos = null,
            span = CompactSpan(startEpochNanos = 0L, durationNanos = 100L),
        )

        assertThat(StateMachineStateCodec.deriveTriggerName(state, record)).isEqualTo("timeout")
    }

    @Test
    fun `deriveTriggerName for AUTO_TRANSITION`() {
        val state = RuntimeState()
        val record = CompactTransitionRecord(
            fromStateId = null,
            toStateId = null,
            outcome = TransitionOutcome.TRANSITION_TO,
            triggerKind = TriggerKind.AUTO_TRANSITION,
            span = CompactSpan(startEpochNanos = 0L, durationNanos = 100L),
        )

        assertThat(StateMachineStateCodec.deriveTriggerName(state, record)).isEqualTo("auto")
    }

    @Test
    fun `deriveTriggerName for INITIAL`() {
        val state = RuntimeState()
        val record = CompactTransitionRecord(
            fromStateId = null,
            toStateId = null,
            outcome = TransitionOutcome.TRANSITION_TO,
            triggerKind = TriggerKind.INITIAL,
            span = CompactSpan(startEpochNanos = 0L, durationNanos = 100L),
        )

        assertThat(StateMachineStateCodec.deriveTriggerName(state, record)).isEqualTo("initial")
    }

    // ── Forward-compatible decode tolerance ──

    @Test
    fun `decode accepts a snapshot stamped with a newer version than the writer`() {
        val futureBlob = Zstd.compress(
            StateMachineStateCodec.mapper.writeValueAsBytes(
                CompactStateSnapshot(version = 2, stateNames = listOf("IDLE")),
            ),
            6,
        )

        val decoded = StateMachineStateCodec.decode(futureBlob, "wf-future")

        assertThat(decoded.stateNames).containsExactly("IDLE")
    }

    @Test
    fun `decode throws when the snapshot version is below the supported minimum`() {
        val staleBlob = Zstd.compress(
            StateMachineStateCodec.mapper.writeValueAsBytes(
                CompactStateSnapshot(version = 0, stateNames = listOf("IDLE")),
            ),
            6,
        )

        assertThatThrownBy { StateMachineStateCodec.decode(staleBlob, "wf-stale") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("0")
            .hasMessageContaining("minimum supported")
    }

    @Test
    fun `decode tolerates an unknown property added by a newer writer`() {
        // Mirror a CompactStateSnapshot with one extra field a future writer might emit.
        val blobWithUnknownField = Zstd.compress(
            StateMachineStateCodec.mapper.writeValueAsBytes(
                mapOf(
                    "version" to 1,
                    "stateNames" to listOf("IDLE"),
                    "unknownFutureField" to "ignored",
                ),
            ),
            6,
        )

        val decoded = StateMachineStateCodec.decode(blobWithUnknownField, "wf-unknown-field")

        assertThat(decoded.stateNames).containsExactly("IDLE")
    }

    @Test
    fun `decodeTransitionSegment accepts a journal segment stamped with a newer version`() {
        val transition = CompactTransitionRecord(
            fromStateId = 0,
            toStateId = 1,
            outcome = TransitionOutcome.TRANSITION_TO,
            triggerKind = TriggerKind.EVENT,
            span = CompactSpan(startEpochNanos = 1_000_000_000L, durationNanos = 1_000L),
        )
        val futureSegment = Zstd.compress(
            StateMachineStateCodec.mapper.writeValueAsBytes(
                CompactJournalSegment(version = 2, transition = transition),
            ),
            6,
        )

        val decoded = StateMachineStateCodec.decodeTransitionSegment(futureSegment, "wf-future-segment")

        assertThat(decoded).isEqualTo(transition)
    }

    @Test
    fun `decodeTransitionSegment throws when the journal segment version is below the supported minimum`() {
        val staleSegment = Zstd.compress(
            StateMachineStateCodec.mapper.writeValueAsBytes(
                CompactJournalSegment(
                    version = 0,
                    transition = CompactTransitionRecord(
                        outcome = TransitionOutcome.TRANSITION_TO,
                        triggerKind = TriggerKind.INITIAL,
                        span = CompactSpan(startEpochNanos = 0L, durationNanos = 1L),
                    ),
                ),
            ),
            6,
        )

        assertThatThrownBy { StateMachineStateCodec.decodeTransitionSegment(staleSegment, "wf-stale-segment") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("0")
            .hasMessageContaining("minimum supported")
    }
}
