package com.airbnb.skipper.internal.api

import java.time.Instant

/**
 * Represents a durably persisted signal invocation.
 *
 * When a [com.airbnb.skipper.SignalMethod] opts into persistence via `@SignalMethod(persist =
 * true)`, a [PersistedSignal] record is written *before* the signal method executes. This
 * guarantees that the signal — including the input it carried — is not lost if execution fails or
 * the process crashes mid-execution, and allows it to be manually replayed later.
 *
 * The record transitions through a [Status] lifecycle: it starts as [Status.PENDING] when first
 * written, becomes [Status.EXECUTED] once the signal method runs successfully and the workflow
 * state is updated, or [Status.FAILED] if execution raises an error. Rows in a non-[Status.EXECUTED]
 * state are the candidates for manual replay.
 *
 * @property workflowId The id of the workflow instance the signal targets.
 * @property signalMethod The name of the `@SignalMethod` that was invoked.
 * @property status The current lifecycle status of the signal.
 * @property input The deserialized input the signal was invoked with, or null if the signal method
 *   takes no argument. The store is responsible for (de)serializing this value.
 * @property requestContext The request context that was active when the signal was sent, captured
 *   so that a replay can faithfully reconstruct the original invocation. May be null.
 * @property error A human-readable stack trace of the error captured when [status] is
 *   [Status.FAILED]. This is stored for operator reference only and is never used at runtime, so it
 *   is a plain string rather than a serialized exception.
 * @property id The auto-generated identifier of the persisted signal row. Null until the record has
 *   been inserted by the store.
 */
data class PersistedSignal
    @JvmOverloads
    constructor(
        val workflowId: String,
        val signalMethod: String,
        val status: Status,
        val input: Any? = null,
        val requestContext: Any? = null,
        val error: String? = null,
        val id: Long? = null,
        val createdAt: Instant? = null,
        val updatedAt: Instant? = null,
    ) {
        /** The lifecycle status of a persisted signal. */
        enum class Status {
            /** The signal has been persisted but its execution has not yet completed successfully. */
            PENDING,

            /** The signal method executed successfully and the workflow state was updated. */
            EXECUTED,

            /** The signal method execution raised an error. The signal can be manually replayed. */
            FAILED,
        }
    }
