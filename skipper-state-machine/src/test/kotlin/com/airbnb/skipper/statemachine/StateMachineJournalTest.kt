package com.airbnb.skipper.statemachine

import com.airbnb.skipper.statemachine.SkipperStateMachine.PreciseTimeRange
import com.airbnb.skipper.statemachine.SkipperStateMachine.PreciseTimestamp
import com.airbnb.skipper.statemachine.SkipperStateMachine.TransitionOutcome
import com.airbnb.skipper.statemachine.StateMachineStateCodec.MarkerKind
import com.airbnb.skipper.statemachine.StateMachineStateCodec.toEpochNanos
import com.airbnb.skipper.statemachine.StateMachineStateCodec.toPreciseTimeRange
import com.airbnb.skipper.statemachine.StateMachineStateCodec.toPreciseTimestamp
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StateMachineJournalTest {
    @Test
    fun `transition progress records upsert into one durable transition and preserve first phase start`() {
        val harness = StateMachineRuntimeHarness()
        val pending = harness.journal.buildPendingTransition(
            fromState = InternalTestState.IDLE,
            toState = InternalTestState.RUNNING,
            outcome = TransitionOutcome.TRANSITION_TO,
            trigger = TransitionTrigger.Event(InternalTestEvent.Go),
            transitionStart = timestamp(100L),
            eventIndex = 0,
        )
        var durableRecord = harness.journal.recordTransitionJournal(
            pendingTransition = pending,
            span = range(100L, 100L),
            checkpointMode = JournalCheckpointMode.IMMEDIATE,
        )

        durableRecord = harness.journal.recordTransitionProgressJournal(
            durableRecord = durableRecord,
            spanEnd = timestamp(150L),
            onEntrySpan = range(150L, 150L),
            phase = StateMachineStateCodec.JournalPhase.BEFORE_MIDDLEWARE,
        )
        harness.journal.recordTransitionProgressJournal(
            durableRecord = durableRecord,
            spanEnd = timestamp(500L),
            onEntrySpan = range(300L, 500L),
            afterMiddlewareSpan = range(500L, 500L),
            phase = StateMachineStateCodec.JournalPhase.ON_EXIT,
        )

        val transitions = harness.runtimeStore.runtimeState().transitions
        assertThat(transitions).hasSize(1)
        val transition = transitions.single()
        assertThat(transition.span.toPreciseTimeRange()).isEqualTo(range(100L, 500L))
        assertThat(transition.onEntrySpan?.toPreciseTimeRange()).isEqualTo(range(150L, 500L))
        assertThat(harness.checkpoint.immediateSegments).hasSize(3)
        assertThat(harness.checkpoint.eventualSegments).isEmpty()
    }

    @Test
    fun `after hook progress records upsert by fired time and preserve first handler start`() {
        val harness = StateMachineRuntimeHarness()
        harness.journal.recordAfterHookProgressJournal(
            duration = Duration.ofMinutes(5),
            firedAtEpochNanos = 1_000L,
            handlerSpan = range(1_000L, 1_000L),
            checkpointMode = JournalCheckpointMode.IMMEDIATE,
        )
        harness.journal.recordAfterHookProgressJournal(
            duration = Duration.ofMinutes(5),
            firedAtEpochNanos = 1_000L,
            handlerSpan = range(1_500L, 2_000L),
        )

        val afterHooks = harness.runtimeStore.runtimeState().afterHooks
        assertThat(afterHooks).hasSize(1)
        assertThat(afterHooks.single().handlerSpan?.toPreciseTimeRange()).isEqualTo(range(1_000L, 2_000L))
        assertThat(harness.checkpoint.immediateSegments).hasSize(1)
        assertThat(harness.checkpoint.eventualSegments).hasSize(1)
    }

    @Test
    fun `after hook progress merges by hookId across a duration change`() {
        // 1d -> 7d redeploy analog at the journal level: a firing was recorded when the hook's
        // duration was 1d; after the duration is edited to 7d the replay re-records the same firing
        // (same hookId, same firedAt) with the new duration. Keying the upsert on hookId merges it
        // into the original record instead of appending a second one — the property that keeps a
        // duration edit from double-recording (and, in the loop, double-firing) the hook.
        val harness = StateMachineRuntimeHarness()
        harness.journal.recordAfterHookProgressJournal(
            duration = Duration.ofDays(1),
            firedAtEpochNanos = 1_000L,
            handlerSpan = range(1_000L, 1_000L),
            hookId = "#0",
            checkpointMode = JournalCheckpointMode.IMMEDIATE,
        )
        harness.journal.recordAfterHookProgressJournal(
            duration = Duration.ofDays(7),
            firedAtEpochNanos = 1_000L,
            handlerSpan = range(1_500L, 2_000L),
            hookId = "#0",
        )

        val afterHooks = harness.runtimeStore.runtimeState().afterHooks
        assertThat(afterHooks).hasSize(1)
        val merged = afterHooks.single()
        assertThat(merged.hookId).isEqualTo("#0")
        // First-attempt start is preserved; the merged record carries the latest (7d) duration.
        assertThat(merged.handlerSpan?.toPreciseTimeRange()).isEqualTo(range(1_000L, 2_000L))
        assertThat(merged.durationNanos).isEqualTo(Duration.ofDays(7).toNanos())
    }

    @Test
    fun `timestamp markers are durable but not visible in replay-derived history`() {
        val harness = StateMachineRuntimeHarness()

        val durableTimestamp = harness.journal.recordTimestampMarker(timestamp(42L), MarkerKind.EVENT_HANDLER, eventIndex = 0)

        assertThat(durableTimestamp.toEpochNanos()).isEqualTo(42L)
        assertThat(harness.checkpoint.immediateSegments).hasSize(1)
        assertThat(harness.runtimeStore.runtimeState().transitions).isEmpty()
        assertThat(harness.runtimeStore.runtimeState().afterHooks).isEmpty()
        assertThat(harness.runtimeStore.runtimeState().timeouts).isEmpty()
    }

    @Test
    fun `timeout journal records use immediate checkpointing by default`() {
        val harness = StateMachineRuntimeHarness()

        harness.journal.appendTimeoutJournal(
            duration = Duration.ofSeconds(30),
            firedAtEpochNanos = 3_000L,
        )

        assertThat(harness.runtimeStore.runtimeState().timeouts).hasSize(1)
        assertThat(harness.checkpoint.immediateSegments).hasSize(1)
        assertThat(harness.checkpoint.eventualSegments).isEmpty()
    }

    @Test
    fun `terminal middleware progress updates the last transition`() {
        val harness = StateMachineRuntimeHarness()
        val pending = harness.journal.buildPendingTransition(
            fromState = InternalTestState.IDLE,
            toState = InternalTestState.DONE,
            outcome = TransitionOutcome.TRANSITION_TO,
            trigger = TransitionTrigger.Event(InternalTestEvent.Go),
            transitionStart = timestamp(100L),
        )
        val durableRecord = harness.journal.recordTransitionJournal(
            pendingTransition = pending,
            span = range(100L, 200L),
            stateEntryEpochNanos = 200L,
            checkpointMode = JournalCheckpointMode.IMMEDIATE,
        )
        harness.journal.recordTransitionCompletionJournal(
            durableStartRecord = durableRecord,
            spanEnd = timestamp(200L),
            onEntrySpan = range(180L, 200L),
        )

        harness.journal.recordTerminalMiddlewareProgress(range(210L, 240L))

        val transition = harness.runtimeStore.runtimeState().transitions.single()
        assertThat(transition.terminalMiddlewareSpan?.toPreciseTimeRange()).isEqualTo(range(210L, 240L))
        assertThat(transition.span.toPreciseTimeRange()).isEqualTo(range(100L, 240L))
    }

    @Test
    fun `all phase writes of one transition share a key prefix and differ only by phase suffix`() {
        val harness = StateMachineRuntimeHarness()
        val pending = harness.journal.buildPendingTransition(
            fromState = InternalTestState.IDLE,
            toState = InternalTestState.RUNNING,
            outcome = TransitionOutcome.TRANSITION_TO,
            trigger = TransitionTrigger.Event(InternalTestEvent.Go),
            transitionStart = timestamp(100L),
            eventIndex = 0,
        )
        // One logical transition driven through start + two progress writes + completion. Every write
        // targets the same row, so the checkpoint key must be identical except for the phase suffix.
        var durableRecord = harness.journal.recordTransitionJournal(
            pendingTransition = pending,
            span = range(100L, 100L),
            checkpointMode = JournalCheckpointMode.IMMEDIATE,
            phase = StateMachineStateCodec.JournalPhase.STARTED,
        )
        durableRecord = harness.journal.recordTransitionProgressJournal(
            durableRecord = durableRecord,
            spanEnd = timestamp(150L),
            onEntrySpan = range(150L, 150L),
            phase = StateMachineStateCodec.JournalPhase.BEFORE_MIDDLEWARE,
        )
        durableRecord = harness.journal.recordTransitionProgressJournal(
            durableRecord = durableRecord,
            spanEnd = timestamp(300L),
            afterMiddlewareSpan = range(300L, 300L),
            phase = StateMachineStateCodec.JournalPhase.ON_EXIT,
        )
        harness.journal.recordTransitionCompletionJournal(
            durableStartRecord = durableRecord,
            spanEnd = timestamp(500L),
            onEntrySpan = range(150L, 500L),
        )

        // All four writes are immediate (start IMMEDIATE, progress defaults to IMMEDIATE, completion
        // forces IMMEDIATE), so every checkpoint name is captured in immediateNames.
        val names = harness.checkpoint.immediateNames.filterNotNull()
        assertThat(names).hasSize(4)
        names.forEach { assertThat(it).startsWith("sm:txn:") }
        // The phase is the final colon-delimited segment; the rest is the invariant row key.
        val prefixes = names.map { it.substringBeforeLast(":") }
        val phaseSuffixes = names.map { it.substringAfterLast(":") }
        assertThat(prefixes.distinct()).hasSize(1)
        assertThat(phaseSuffixes).containsExactly("STARTED", "BEFORE_MIDDLEWARE", "ON_EXIT", "COMPLETED")
        // Despite four distinct named writes, they upsert into exactly one durable transition row.
        assertThat(harness.runtimeStore.runtimeState().transitions).hasSize(1)
    }

    @Test
    fun `after-hook phase writes for one firing share a hook key and differ only by phase suffix`() {
        val harness = StateMachineRuntimeHarness()
        // Two writes for the SAME firing: a STARTED marker then a COMPLETED write, same hookId and same
        // firedAt. They must land on one upserted after-hook row, named identically but for the phase.
        harness.journal.recordAfterHookProgressJournal(
            duration = Duration.ofMinutes(5),
            firedAtEpochNanos = 1_000L,
            handlerSpan = range(1_000L, 1_000L),
            hookId = "#0",
            checkpointMode = JournalCheckpointMode.IMMEDIATE,
            phase = StateMachineStateCodec.JournalPhase.STARTED,
        )
        harness.journal.recordAfterHookProgressJournal(
            duration = Duration.ofMinutes(5),
            firedAtEpochNanos = 1_000L,
            handlerSpan = range(1_500L, 2_000L),
            hookId = "#0",
            checkpointMode = JournalCheckpointMode.IMMEDIATE,
            phase = StateMachineStateCodec.JournalPhase.COMPLETED,
        )

        val names = harness.checkpoint.immediateNames.filterNotNull()
        assertThat(names).hasSize(2)
        names.forEach {
            assertThat(it).startsWith("sm:after:")
            // hookId is encoded into the key, distinguishing this firing from other hooks.
            assertThat(it).contains(":#0:")
        }
        assertThat(names.map { it.substringBeforeLast(":") }.distinct()).hasSize(1)
        assertThat(names.map { it.substringAfterLast(":") }).containsExactly("STARTED", "COMPLETED")
        // Both phase writes merge into one after-hook record.
        assertThat(harness.runtimeStore.runtimeState().afterHooks).hasSize(1)
    }

    @Test
    fun `transition timeout after-hook and marker names use disjoint record-kind prefixes`() {
        val harness = StateMachineRuntimeHarness()
        val pending = harness.journal.buildPendingTransition(
            fromState = InternalTestState.IDLE,
            toState = InternalTestState.RUNNING,
            outcome = TransitionOutcome.TRANSITION_TO,
            trigger = TransitionTrigger.Event(InternalTestEvent.Go),
            transitionStart = timestamp(100L),
            eventIndex = 0,
        )
        harness.journal.recordTransitionJournal(
            pendingTransition = pending,
            span = range(100L, 100L),
            stateEntryEpochNanos = 100L,
            checkpointMode = JournalCheckpointMode.IMMEDIATE,
            phase = StateMachineStateCodec.JournalPhase.STARTED,
        )
        harness.journal.appendTimeoutJournal(duration = Duration.ofMinutes(5), firedAtEpochNanos = 5_000L)
        harness.journal.recordAfterHookProgressJournal(
            duration = Duration.ofMinutes(3),
            firedAtEpochNanos = 3_000L,
            handlerSpan = range(3_000L, 3_000L),
            hookId = "#0",
            checkpointMode = JournalCheckpointMode.IMMEDIATE,
        )
        harness.journal.recordTimestampMarker(timestamp(42L), MarkerKind.EVENT_HANDLER, eventIndex = 0)

        val names = harness.checkpoint.immediateNames.filterNotNull()
        // One name per record kind, each on its own namespace prefix — no cross-contamination.
        assertThat(names.count { it.startsWith("sm:txn:") }).isEqualTo(1)
        assertThat(names.count { it.startsWith("sm:timeout:") }).isEqualTo(1)
        assertThat(names.count { it.startsWith("sm:after:") }).isEqualTo(1)
        assertThat(names.count { it.startsWith("sm:mark:") }).isEqualTo(1)
        // The marker prefix is distinct from the after prefix even though both start with "sm:".
        assertThat(names.single { it.startsWith("sm:mark:") }).doesNotStartWith("sm:after:")
    }

    private fun timestamp(epochNanos: Long): PreciseTimestamp = with(StateMachineStateCodec) { epochNanos.toPreciseTimestamp() }

    private fun range(
        startEpochNanos: Long,
        endEpochNanos: Long,
    ): PreciseTimeRange = PreciseTimeRange(timestamp(startEpochNanos), timestamp(endEpochNanos))
}
