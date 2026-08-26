package com.airbnb.skipper.statemachine

import com.airbnb.skipper.named
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactAfterHookRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactTimeoutRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactTransitionRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.PendingTransition
import com.airbnb.skipper.statemachine.StateMachineStateCodec.RuntimeState
import java.time.Duration

/**
 * Internal durability choice for compact journal segments.
 *
 * Product actions still choose their own Skipper checkpoint mode. This enum only controls
 * framework-owned metadata that makes admin history replay-stable.
 */
internal enum class JournalCheckpointMode {
    /** Persist the journal segment before the workflow state update commits. */
    IMMEDIATE,

    /** Batch the journal segment with the workflow state update for lower write overhead. */
    EVENTUAL,
}

/**
 * Replay-safe journal writer for admin/debug history.
 *
 * Journal records are encoded as compact Smile + Zstd segments and persisted through Skipper
 * action checkpoints. Replaying the workflow replays those action results, then this class rebuilds
 * the compact runtime snapshot from durable bytes instead of recording fresh timestamps. Transition
 * and hook progress records are upserts rather than append-only rows, so a failed attempt can
 * durably mark the first start time and a later retry can merge in the final end time.
 */
internal class StateMachineJournal<StateT, EventT : StateMachineEvent>(
    private val workflowId: () -> String,
    private val checkpointActions: StateMachineCheckpoint,
    private val runtimeStore: StateMachineRuntimeStore,
) where StateT : Enum<StateT> {
    /**
     * Per-run counter assigning each transition a sequence number for its replay-stable checkpoint
     * name. Incremented at a transition's first write. Reconstructed deterministically because the
     * event loop re-walks the durable log in the same order every replay; reset via [resetForRun].
     */
    private var nextTransitionSeq = 0

    /** Resets per-run name-assignment state. Called at the start of every replay walk. */
    fun resetForRun() {
        nextTransitionSeq = 0
    }

    /**
     * Builds transition metadata while state and trigger values are still in runtime form.
     *
     * The codec later resolves state names into compact dictionary ids when a concrete
     * [StateMachineStateCodec.RuntimeState] is available.
     */
    fun buildPendingTransition(
        fromState: StateT?,
        toState: StateT?,
        outcome: SkipperStateMachine.TransitionOutcome,
        trigger: TransitionTrigger<EventT>,
        transitionStart: SkipperStateMachine.PreciseTimestamp,
        handlerSpan: SkipperStateMachine.PreciseTimeRange? = null,
        eventIndex: Int? = null,
        initialMiddlewareSpan: SkipperStateMachine.PreciseTimeRange? = null,
        beforeMiddlewareSpan: SkipperStateMachine.PreciseTimeRange? = null,
        onExitSpan: SkipperStateMachine.PreciseTimeRange? = null,
    ): PendingTransition =
        with(StateMachineStateCodec) {
            val (triggerKind, timeoutDurationNanos) = triggerMetadata(trigger)
            buildPendingTransition(
                fromStateName = fromState?.name,
                toStateName = toState?.name,
                outcome = outcome,
                triggerKind = triggerKind,
                timeoutDurationNanos = timeoutDurationNanos,
                transitionStart = transitionStart,
                handlerSpan = handlerSpan,
                eventIndex = eventIndex,
                initialMiddlewareSpan = initialMiddlewareSpan,
                beforeMiddlewareSpan = beforeMiddlewareSpan,
                onExitSpan = onExitSpan,
            )
        }

    /**
     * Writes or replays a complete transition journal record.
     *
     * Used for ignored/invalid transitions and for first transition-start records. The returned
     * record is the durable value returned by Skipper action replay, not necessarily the freshly
     * measured value from this execution.
     */
    fun recordTransitionJournal(
        pendingTransition: PendingTransition,
        span: SkipperStateMachine.PreciseTimeRange,
        stateEntryEpochNanos: Long? = null,
        checkpointMode: JournalCheckpointMode = JournalCheckpointMode.EVENTUAL,
        phase: StateMachineStateCodec.JournalPhase = StateMachineStateCodec.JournalPhase.COMPLETED,
    ): CompactTransitionRecord {
        with(StateMachineStateCodec) {
            val runtimeState = runtimeStore.runtimeState()
            // First write of a transition: assign its per-run sequence number, carried through every
            // later phase write via the record so they share one replay-stable checkpoint name.
            val proposedRecord = runtimeState.transitionRecord(
                pendingTransition = pendingTransition,
                span = span,
                stateEntryEpochNanos = stateEntryEpochNanos,
            ).copy(seq = nextTransitionSeq++)
            return appendStateMachineJournalSegment(
                encodedSegment = encodeTransitionSegment(proposedRecord),
                checkpointName = transitionCheckpointName(proposedRecord, phase),
                decodeSegment = { payload, id -> decodeTransitionSegment(payload, id) },
                checkpointMode = checkpointMode,
            ) { record ->
                upsertTransitionRecord(record)
            }
        }
    }

    /**
     * Finalizes a transition record using immediate checkpointing.
     *
     * This is the last progress write for a successful transition, so immediate checkpointing keeps
     * the final visible span durable even if queued signals race with the workflow state update.
     */
    fun recordTransitionCompletionJournal(
        durableStartRecord: CompactTransitionRecord,
        spanEnd: SkipperStateMachine.PreciseTimestamp,
        onEntrySpan: SkipperStateMachine.PreciseTimeRange,
        afterMiddlewareSpan: SkipperStateMachine.PreciseTimeRange? = null,
    ): CompactTransitionRecord =
        recordTransitionProgressJournal(
            durableRecord = durableStartRecord,
            spanEnd = spanEnd,
            onEntrySpan = onEntrySpan,
            afterMiddlewareSpan = afterMiddlewareSpan,
            checkpointMode = JournalCheckpointMode.IMMEDIATE,
            phase = StateMachineStateCodec.JournalPhase.COMPLETED,
        )

    /**
     * Upserts partial transition progress and merges measured phase spans into a durable record.
     *
     * Existing span starts win over later measurements. That preserves the first-attempt start time
     * when a retryable product action fails inside a hook or middleware callback and the workflow
     * later replays successfully.
     */
    fun recordTransitionProgressJournal(
        durableRecord: CompactTransitionRecord,
        spanEnd: SkipperStateMachine.PreciseTimestamp? = null,
        stateEntryEpochNanos: Long? = null,
        initialMiddlewareSpan: SkipperStateMachine.PreciseTimeRange? = null,
        beforeMiddlewareSpan: SkipperStateMachine.PreciseTimeRange? = null,
        onExitSpan: SkipperStateMachine.PreciseTimeRange? = null,
        onEntrySpan: SkipperStateMachine.PreciseTimeRange? = null,
        afterMiddlewareSpan: SkipperStateMachine.PreciseTimeRange? = null,
        terminalMiddlewareSpan: SkipperStateMachine.PreciseTimeRange? = null,
        checkpointMode: JournalCheckpointMode = JournalCheckpointMode.IMMEDIATE,
        phase: StateMachineStateCodec.JournalPhase,
    ): CompactTransitionRecord {
        with(StateMachineStateCodec) {
            val durableStart = durableRecord.span.toPreciseTimeRange().start
            val updatedRecord = durableRecord.copy(
                stateEntryEpochNanos = stateEntryEpochNanos ?: durableRecord.stateEntryEpochNanos,
                span = spanEnd?.let { SkipperStateMachine.PreciseTimeRange(durableStart, it).toCompactSpan() } ?: durableRecord.span,
                initialMiddlewareSpan = mergeSpanStart(durableRecord.initialMiddlewareSpan, initialMiddlewareSpan),
                beforeMiddlewareSpan = mergeSpanStart(durableRecord.beforeMiddlewareSpan, beforeMiddlewareSpan),
                onExitSpan = mergeSpanStart(durableRecord.onExitSpan, onExitSpan),
                onEntrySpan = mergeSpanStart(durableRecord.onEntrySpan, onEntrySpan),
                afterMiddlewareSpan = mergeSpanStart(durableRecord.afterMiddlewareSpan, afterMiddlewareSpan),
                terminalMiddlewareSpan = mergeSpanStart(durableRecord.terminalMiddlewareSpan, terminalMiddlewareSpan),
            )
            // .copy preserves seq, so every phase of one transition shares the key and differs only
            // by the phase suffix.
            return appendStateMachineJournalSegment(
                encodedSegment = encodeTransitionSegment(updatedRecord),
                checkpointName = transitionCheckpointName(updatedRecord, phase),
                decodeSegment = { payload, id -> decodeTransitionSegment(payload, id) },
                checkpointMode = checkpointMode,
            ) { record ->
                upsertTransitionRecord(record)
            }
        }
    }

    /**
     * Attaches terminal-middleware timing to the most recent transition, when one exists.
     *
     * A directly-terminal initial state has no prior transition row, so this returns null and the
     * middleware still runs without an admin span.
     */
    fun recordTerminalMiddlewareProgress(terminalMiddlewareSpan: SkipperStateMachine.PreciseTimeRange): CompactTransitionRecord? =
        runtimeStore.runtimeState().transitions.lastOrNull()?.let { transition ->
            recordTransitionProgressJournal(
                durableRecord = transition,
                spanEnd = terminalMiddlewareSpan.end,
                terminalMiddlewareSpan = terminalMiddlewareSpan,
                checkpointMode = JournalCheckpointMode.IMMEDIATE,
                phase = StateMachineStateCodec.JournalPhase.TERMINAL_STARTED,
            )
        }

    /**
     * Appends a timeout firing to the compact history.
     *
     * Timeout firings use immediate checkpointing by default so the admin UI can show that the
     * timer fired even if the timeout handler later fails and is retried.
     */
    fun appendTimeoutJournal(
        duration: Duration,
        firedAtEpochNanos: Long,
        checkpointMode: JournalCheckpointMode = JournalCheckpointMode.IMMEDIATE,
    ) {
        with(StateMachineStateCodec) {
            val proposedRecord = CompactTimeoutRecord(
                firedAtEpochNanos = firedAtEpochNanos,
                durationNanos = duration.toNanosExact(),
            )
            val stateEntry = currentStateEntryEpochNanos(runtimeStore.runtimeState())
            appendStateMachineJournalSegment(
                encodedSegment = encodeTimeoutSegment(proposedRecord),
                checkpointName = timeoutCheckpointName(stateEntry, proposedRecord.durationNanos),
                decodeSegment = { payload, id -> decodeTimeoutSegment(payload, id) },
                checkpointMode = checkpointMode,
            ) { record ->
                timeouts.add(record)
            }
        }
    }

    /**
     * Upserts after-hook execution progress for a specific state-entry deadline.
     *
     * The first call records a durable zero-length start marker. A later successful completion
     * merges the real end time while retaining that original start.
     */
    fun recordAfterHookProgressJournal(
        duration: Duration,
        firedAtEpochNanos: Long,
        handlerSpan: SkipperStateMachine.PreciseTimeRange,
        hookId: String? = null,
        checkpointMode: JournalCheckpointMode = JournalCheckpointMode.EVENTUAL,
        phase: StateMachineStateCodec.JournalPhase = StateMachineStateCodec.JournalPhase.COMPLETED,
    ): CompactAfterHookRecord {
        with(StateMachineStateCodec) {
            val proposedRecord = CompactAfterHookRecord(
                firedAtEpochNanos = firedAtEpochNanos,
                durationNanos = duration.toNanosExact(),
                hookId = hookId,
                handlerSpan = handlerSpan.toCompactSpan(),
            )
            val stateEntry = currentStateEntryEpochNanos(runtimeStore.runtimeState())
            return appendStateMachineJournalSegment(
                encodedSegment = encodeAfterHookSegment(proposedRecord),
                checkpointName = afterHookCheckpointName(stateEntry, hookId, proposedRecord.durationNanos, phase),
                decodeSegment = { payload, id -> decodeAfterHookSegment(payload, id) },
                checkpointMode = checkpointMode,
            ) { record ->
                upsertAfterHookRecord(record)
            }
        }
    }

    /**
     * Persists a timestamp for code that has not produced a visible transition record yet.
     *
     * Event handlers, timeout handlers, and invalid-transition middleware can fail before the
     * framework knows the final transition outcome. This hidden marker gives a retry the original
     * first-attempt start time without rendering an orphan admin row.
     */
    fun recordTimestampMarker(
        timestamp: SkipperStateMachine.PreciseTimestamp,
        markerKind: StateMachineStateCodec.MarkerKind,
        eventIndex: Int? = null,
        checkpointMode: JournalCheckpointMode = JournalCheckpointMode.IMMEDIATE,
    ): SkipperStateMachine.PreciseTimestamp {
        with(StateMachineStateCodec) {
            val markerKey = markerCheckpointName(
                markerKind = markerKind,
                eventIndex = eventIndex,
                stateEntryEpochNanos = currentStateEntryEpochNanos(runtimeStore.runtimeState()),
            )
            return appendStateMachineJournalSegment(
                encodedSegment = encodeTimestampMarkerSegment(timestamp.toEpochNanos()),
                checkpointName = markerKey,
                decodeSegment = { payload, id -> decodeTimestampMarkerSegment(payload, id).toPreciseTimestamp() },
                checkpointMode = checkpointMode,
            ) {
                // Timestamp markers are durable control data only. They are intentionally absent
                // from the admin snapshot unless a later transition/after-hook record consumes
                // the timestamp as the stable start of a visible span.
            }
        }
    }

    /**
     * Persists one encoded journal segment through the selected Skipper checkpoint mode.
     *
     * On first execution this stores [encodedSegment]. On replay Skipper returns the durable bytes
     * from the original checkpoint, which are decoded and applied to the rebuilt runtime snapshot.
     */
    private fun <RecordT> appendStateMachineJournalSegment(
        encodedSegment: ByteArray,
        checkpointName: String,
        decodeSegment: (ByteArray, String) -> RecordT,
        checkpointMode: JournalCheckpointMode = JournalCheckpointMode.EVENTUAL,
        applyRecord: RuntimeState.(RecordT) -> Unit,
    ): RecordT {
        val segment = StateMachineJournalSegment(encodedSegment)
        // Name the journal checkpoint so its identity is content-derived, not the shared positional
        // iteration counter. This keeps each record stable when other journal writes are added,
        // removed, or reordered by a code edit on an in-flight instance.
        val durableSegment = when (checkpointMode) {
            JournalCheckpointMode.IMMEDIATE -> checkpointActions.named(checkpointName).recordStateMachineJournalSegment(segment)
            JournalCheckpointMode.EVENTUAL -> checkpointActions.named(checkpointName).recordStateMachineJournalSegmentEventually(segment)
        }
        val record = decodeSegment(durableSegment.payload, workflowId())
        val runtimeState = runtimeStore.runtimeState()
        runtimeState.applyRecord(record)
        runtimeStore.persist(runtimeState)
        return record
    }

    /** Inserts or replaces a transition record that represents the same durable attempt. */
    private fun RuntimeState.upsertTransitionRecord(record: CompactTransitionRecord) {
        val existingIndex = transitions.indexOfLast {
            it.isSameTransitionAttempt(record) ||
                (record.stateEntryEpochNanos != null && it.isSameStateEntryTransition(record))
        }
        if (existingIndex >= 0) {
            transitions[existingIndex] = record
        } else {
            transitions.add(record)
        }
    }

    /** Inserts or replaces an after-hook record for the same firing. */
    private fun RuntimeState.upsertAfterHookRecord(record: CompactAfterHookRecord) {
        val existingIndex = afterHooks.indexOfLast { it.isSameFiring(record) }
        if (existingIndex >= 0) {
            val existing = afterHooks[existingIndex]
            afterHooks[existingIndex] = record.copy(
                handlerSpan = mergeSpanStart(
                    existing = existing.handlerSpan,
                    measured = with(StateMachineStateCodec) { record.handlerSpan?.toPreciseTimeRange() },
                ),
            )
        } else {
            afterHooks.add(record)
        }
    }

    /**
     * Returns true when two after-hook records identify the same firing. A firing is keyed by its
     * hookId + firedAt when an id is present (stable across a duration edit); records written before
     * hook ids existed fall back to (firedAt, duration).
     */
    private fun CompactAfterHookRecord.isSameFiring(other: CompactAfterHookRecord): Boolean =
        if (hookId != null && other.hookId != null) {
            hookId == other.hookId && firedAtEpochNanos == other.firedAtEpochNanos
        } else {
            firedAtEpochNanos == other.firedAtEpochNanos && durationNanos == other.durationNanos
        }

    /** Returns true when two transition records identify the same trigger attempt. */
    private fun CompactTransitionRecord.isSameTransitionAttempt(other: CompactTransitionRecord): Boolean =
        span.startEpochNanos == other.span.startEpochNanos &&
            fromStateId == other.fromStateId &&
            toStateId == other.toStateId &&
            outcome == other.outcome &&
            triggerKind == other.triggerKind &&
            eventIndex == other.eventIndex &&
            timeoutDurationNanos == other.timeoutDurationNanos

    /** Returns true when two transition records identify the same state entry after replay. */
    private fun CompactTransitionRecord.isSameStateEntryTransition(other: CompactTransitionRecord): Boolean =
        stateEntryEpochNanos == other.stateEntryEpochNanos &&
            fromStateId == other.fromStateId &&
            toStateId == other.toStateId &&
            outcome == other.outcome &&
            triggerKind == other.triggerKind &&
            eventIndex == other.eventIndex &&
            timeoutDurationNanos == other.timeoutDurationNanos

    /** Merges a newly measured span into an existing durable span while preserving earliest start. */
    private fun mergeSpanStart(
        existing: StateMachineStateCodec.CompactSpan?,
        measured: SkipperStateMachine.PreciseTimeRange?,
    ): StateMachineStateCodec.CompactSpan? =
        with(StateMachineStateCodec) {
            if (measured == null) {
                return@with existing
            }
            val start = existing
                ?.toPreciseTimeRange()
                ?.start
                ?.takeIf { it.toEpochNanos() <= measured.end.toEpochNanos() }
                ?: measured.start
            SkipperStateMachine.PreciseTimeRange(start, measured.end).toCompactSpan()
        }
}
