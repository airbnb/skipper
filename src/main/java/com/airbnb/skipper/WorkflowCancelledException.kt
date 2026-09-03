package com.airbnb.skipper

/**
 * Thrown at an action boundary when the workflow has been cancelled, so no further action starts and
 * the execution settles as CANCELLED. Workflow code may observe it; a caller waiting on the workflow
 * gets [CancelledWorkflow]. Swallowing it around an action call only defers it to the next boundary.
 */
class WorkflowCancelledException(
    message: String?
) : NonRetryableError(message)
