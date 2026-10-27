package com.airbnb.skipper

import com.airbnb.skipper.internal.InternalError
import java.time.Duration
import java.util.function.Function

/**
 * Starts a child workflow from inside its parent's workflow method, so that a failure to start the
 * child retries the parent instead of failing it.
 *
 * A parent starts its children on its own execution thread, so whatever a start throws escapes the
 * parent's workflow method, and there an exception Skipper does not recognize is taken for a bug in
 * the workflow code: the parent ends in ERROR for good. But starting a child writes to the workflow
 * store and the scheduler, and a timeout or a dropped connection on one of those writes is no bug.
 * Run again, the parent starts the same child under the same id and carries on, so one such blip
 * among the thousands of starts of a large fan-out need not end the whole parent.
 *
 * Get one from [InvocationBuilder.asChildOf], after setting the invocation's other options:
 *
 * ```java
 * workflowFactory.builder(ChildWorkflow.class, childId)
 *     .callbackHandler(ChildCallbackHandler.class)
 *     .asChildOf(this)
 *     .start(child -> child.run(input));
 * ```
 */
class ChildWorkflowBuilder<T : Workflow> internal constructor(
    private val invocation: InvocationBuilder<T>,
    private val workflowId: String
) {
    private var startFailureRetryDelay: Duration = DEFAULT_START_FAILURE_RETRY_DELAY

    /**
     * How long the parent waits before running again after the child failed to start. Defaults to
     * [DEFAULT_START_FAILURE_RETRY_DELAY].
     */
    fun startFailureRetryDelay(delay: Duration): ChildWorkflowBuilder<T> {
        this.startFailureRetryDelay = delay
        return this
    }

    /**
     * Builds the child and runs [call] on it, returning what [call] returns. [call] should be only the
     * call to the child's workflow method: build its input beforehand, so that a bug in that code is
     * not mistaken for a failure to start.
     *
     * What [call] throws reaches the parent as follows:
     *
     * 1. An error Skipper has already classified (a [SkipperError] such as [NonRetryableError] or
     *    [RetryableError], or one of Skipper's internal errors) is rethrown unchanged, so it means what
     *    it meant before.
     * 2. A request Skipper rejected as invalid ([IllegalArgumentException] or [ValidationError]), such
     *    as input that fails validation or a child id that is already taken while duplicates are not
     *    allowed, is rethrown unchanged too. Running the parent again would only repeat it, so it
     *    still fails the parent.
     * 3. Anything else, such as a timeout or a dropped connection while writing the child to the store
     *    or the scheduler, becomes a [RetryableError]. The parent runs again after
     *    [startFailureRetryDelay] and starts the child again under the same id, which picks up an
     *    instance the failed attempt already wrote.
     *
     * For a workflow method that blocks until the child finishes, [call] covers that wait as well. The
     * child's own failure is already a Skipper error (case 1), and giving up on the wait (case 3) makes
     * the parent wait again on its next run.
     *
     * In Java, start a child whose workflow method returns `void` with
     * `start(child -> { child.run(); return null; })`.
     */
    @Suppress("TooGenericExceptionCaught")
    fun <R> start(call: Function<in T, out R>): R {
        // Outside the try: building touches neither the store nor the scheduler, so what it throws is
        // a mistake in the child's definition and must not be retried.
        val child = invocation.build()
        try {
            return call.apply(child)
        } catch (e: Exception) {
            if (e is SkipperError ||
                e is InternalError ||
                e is IllegalArgumentException ||
                e is ValidationError
            ) {
                throw e
            }
            throw RetryableError(
                "unable to start child workflow $workflowId: ${e.message}",
                ApplicationError.fromException(e),
                startFailureRetryDelay
            )
        }
    }

    companion object {
        /** Long enough for a brief store or scheduler outage to pass, short enough not to stall a fan-out. */
        @JvmField
        val DEFAULT_START_FAILURE_RETRY_DELAY: Duration = Duration.ofSeconds(5)
    }
}
