package com.airbnb.skipper.statemachine

import com.airbnb.skipper.Actions
import com.airbnb.skipper.CheckpointMode
import com.airbnb.skipper.Execute
import com.airbnb.skipper.SkipperOpen
import com.airbnb.skipper.internal.serde.Serializable

/**
 * Serializable wrapper for one compressed journal segment.
 *
 * Skipper's action-checkpoint serde does not support raw [ByteArray] as a top-level result.
 * This wrapper keeps the payload compact (Smile + Zstd bytes) while giving Skipper a normal POJO
 * envelope it can store and replay.
 */
@Serializable
data class StateMachineJournalSegment(
    val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean = this === other || (other is StateMachineJournalSegment && payload.contentEquals(other.payload))

    override fun hashCode(): Int = payload.contentHashCode()
}

/**
 * Checkpointed actions for state machine internal journal records.
 *
 * These actions intentionally do not mutate workflow state. The state machine builds a compact
 * Smile + Zstd journal segment before invoking the action, wraps it in [StateMachineJournalSegment],
 * and Skipper stores the returned wrapper as the action checkpoint result. On replay, Skipper
 * returns the original bytes instead of re-running the method, so admin timestamps and spans come
 * from the first durable execution rather than from the replay attempt.
 *
 * ### Why compact bytes?
 *
 * State machine histories can grow quickly. The checkpoint result is deliberately a compact binary
 * segment produced by [StateMachineStateCodec] and wrapped only so Skipper's action-checkpoint
 * serde can persist it. This preserves the same storage discipline as the main
 * [SkipperStateMachine.PersistedStateBlob].
 *
 * ### Why IMMEDIATE_CHECKPOINT?
 *
 * Transition starts, lifecycle progress markers, timeout firings, and timestamp markers use
 * immediate checkpointing. The journal action is pure pass-through metadata: it does not mutate
 * external systems or execute product side effects. Immediate checkpointing is therefore safe for
 * those records, preserves the original state-entry/hook/middleware timestamp if a later action
 * fails, and persists quick transitions before a later workflow-state update can race with queued
 * signals. Action checkpoints still carry the durable idempotency contract for product side
 * effects; state machine authors should use `IMMEDIATE_CHECKPOINT` on those actions when a side
 * effect cannot be safely retried.
 *
 * ### Why not just use `Workflow.checkpoint { }`?
 *
 * `Workflow.checkpoint` delegates to `CheckpointHelpers.checkpointRunnable`, which shows as
 * a generic "checkpointRunnable" in Skipper admin. This class provides a stable method name for
 * internal state-machine journal records.
 *
 * Instantiated via `actions<StateMachineCheckpoint>()` inside [SkipperStateMachine].
 * Not intended for direct use by state machine authors.
 */
@SkipperOpen
open class StateMachineCheckpoint : Actions() {
    /** Returns a compact state-machine journal segment that is durable before workflow update. */
    @Execute(checkpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT)
    open fun recordStateMachineJournalSegment(segment: StateMachineJournalSegment): StateMachineJournalSegment = segment

    /** Returns a compact state-machine journal segment that commits with workflow state. */
    @Execute(checkpointMode = CheckpointMode.EVENTUAL_CHECKPOINT)
    open fun recordStateMachineJournalSegmentEventually(segment: StateMachineJournalSegment): StateMachineJournalSegment = segment
}
