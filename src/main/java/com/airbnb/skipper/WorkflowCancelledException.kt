package com.airbnb.skipper

/**
 * Thrown at an action boundary when the workflow has been cancelled, so no further action starts and
 * the execution settles as CANCELLED. Workflow code may observe it; a caller waiting on the workflow
 * gets [CancelledWorkflow]. Swallowing it around an action call only defers it to the next boundary.
 *
 * @property interruptedAction `Class#method` of the action the cancel interrupted mid-flight, or
 * `null` when the cancel was seen at an action boundary. The interrupted action has no checkpoint.
 */
class WorkflowCancelledException
    @JvmOverloads
    constructor(
        message: String?,
        internal val interruptedAction: String? = null,
    ) : NonRetryableError(message)
