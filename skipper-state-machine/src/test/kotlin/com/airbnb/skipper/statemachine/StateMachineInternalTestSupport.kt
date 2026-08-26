package com.airbnb.skipper.statemachine

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal enum class InternalTestState {
    IDLE,
    RUNNING,
    DONE,
}

internal sealed class InternalTestEvent : StateMachineEvent() {
    object Go : InternalTestEvent()

    object Unknown : InternalTestEvent()
}

internal data class InternalTestInput(val value: String = "test")

internal class InternalTestWorkflow : SkipperStateMachine<InternalTestState, InternalTestEvent, InternalTestInput>(
    InternalTestState.IDLE,
) {
    override fun StateMachineBuilder<InternalTestState, InternalTestEvent, InternalTestInput>.define() {}
}

internal class MutableTestClock(
    private var current: Instant = Instant.EPOCH,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableTestClock(current, zone)

    override fun instant(): Instant = current

    fun advanceNanos(nanos: Long) {
        current = current.plusNanos(nanos)
    }
}

internal class RecordingStateMachineCheckpoint : StateMachineCheckpoint() {
    val immediateSegments = mutableListOf<StateMachineJournalSegment>()
    val eventualSegments = mutableListOf<StateMachineJournalSegment>()

    // Checkpoint name captured from [pendingCheckpointName] (set by `named(...)` immediately before
    // each call) so unit-level tests can assert the framework named each journal write distinctly.
    // Production routes through a Skipper proxy that consumes the name; this double reads it directly.
    val immediateNames = mutableListOf<String?>()
    val eventualNames = mutableListOf<String?>()

    override fun recordStateMachineJournalSegment(segment: StateMachineJournalSegment): StateMachineJournalSegment {
        immediateNames.add(pendingCheckpointName)
        immediateSegments.add(segment)
        return segment
    }

    override fun recordStateMachineJournalSegmentEventually(segment: StateMachineJournalSegment): StateMachineJournalSegment {
        eventualNames.add(pendingCheckpointName)
        eventualSegments.add(segment)
        return segment
    }
}

internal class StateMachineRuntimeHarness(
    val workflowId: String = "wf-test",
) {
    var persistedState = SkipperStateMachine.PersistedStateBlob()
    val runtimeStore = StateMachineRuntimeStore(
        workflowId = { workflowId },
        getPersistedState = { persistedState },
        setPersistedState = { persistedState = it },
    )
    val checkpoint = RecordingStateMachineCheckpoint()
    val journal = StateMachineJournal<InternalTestState, InternalTestEvent>(
        workflowId = { workflowId },
        checkpointActions = checkpoint,
        runtimeStore = runtimeStore,
    )
}
