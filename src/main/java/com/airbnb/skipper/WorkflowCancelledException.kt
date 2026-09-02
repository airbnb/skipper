package com.airbnb.skipper

/**
 * Internal control-flow signal raised when a workflow is found to have been CANCELLED while it was
 * still executing. It is thrown from the action boundary (see `ActionExecutor.executeAction`) by the
 * Layer 1 in-flight cancellation checkpoint, and is recognised by `WorkflowExecutor.handleExecutionError`,
 * which settles the execution as CANCELLED rather than ERROR.
 *
 * It extends [NonRetryableError] so that, on any path that does not special-case it, the execution is
 * still not retried — a cancelled workflow must not be re-driven.
 *
 * Caveat: cancellation is delivered as an exception thrown from action-call code, so a workflow method
 * that wraps an action call in a broad `catch (Exception)` / `catch (Throwable)` can swallow the signal
 * and keep running. This is self-healing across boundaries — the next action the workflow starts
 * re-reads the CANCELLED status and re-throws — but a workflow that swallows it and then starts no
 * further action will run to its normal return before stopping. It is the same risk profile as
 * swallowing any [NonRetryableError] thrown from action code.
 */
class WorkflowCancelledException(
    message: String?
) : NonRetryableError(message)
