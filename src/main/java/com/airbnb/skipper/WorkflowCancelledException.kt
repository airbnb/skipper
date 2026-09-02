package com.airbnb.skipper

/**
 * Signals that a workflow was cancelled while it was still executing. Thrown at an action boundary
 * once the workflow's stored status is CANCELLED, so the execution stops instead of starting the
 * next action, and then settles as CANCELLED rather than ERROR.
 *
 * Extends [NonRetryableError] so a cancelled workflow is never re-driven. Because it is delivered as
 * an exception from action-call code, a workflow method that swallows exceptions around an action
 * call (`catch (Exception)` / `catch (Throwable)`) can suppress it; the next action boundary
 * raises it again.
 */
class WorkflowCancelledException(
    message: String?
) : NonRetryableError(message)
