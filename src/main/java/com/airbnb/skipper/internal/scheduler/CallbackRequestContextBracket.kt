package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.Metrics
import com.airbnb.skipper.RawActionInvocation
import com.airbnb.skipper.RawRequestContextMiddleware
import com.airbnb.skipper.WorkflowInstance
import com.google.common.collect.ImmutableMap

/**
 * Installs the workflow's own request context around a callback notification.
 *
 * The engine fires [com.airbnb.skipper.WorkflowCallbackHandler] methods from a scheduler pool
 * thread, which carries whatever request context earlier, unrelated work left behind. This bracket
 * gives those notifications the same [RawRequestContextMiddleware] treatment the action, signal, and
 * compensation paths already get, so a callback body observes the workflow's own context instead of
 * a foreign one.
 *
 * The scheduler task handlers own an instance of this class; it is never injected.
 */
internal class CallbackRequestContextBracket(
    private val middleware: RawRequestContextMiddleware,
    private val metrics: Metrics,
    private val metricsComponent: String,
) {
    /**
     * Runs [callback] with the request context of [workflowInstance] installed, and tears it down
     * afterwards whether [callback] returns normally or throws.
     *
     * A failure to install the context is counted on `callbackContextErrors` and rethrown, so the
     * caller's existing error handling routes it exactly as it routes a failing callback body.
     * [callback] is not invoked in that case, and there is nothing to tear down.
     *
     * @param source the callback method being notified, e.g. `onSuccess`. Tags the counter with the
     *   same values the handlers' own `callbackHandlerErrors` counter uses.
     */
    fun around(
        workflowInstance: WorkflowInstance,
        source: String,
        callback: () -> Unit,
    ) {
        val invocation =
            RawActionInvocation(
                workflowInstance.workflowId,
                workflowInstance.requestContext,
                workflowInstance.extraRequestData,
            )
        try {
            middleware.rawBeforeExecution(invocation)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable
        ) {
            metrics
                .counter(
                    ImmutableMap.of(
                        "source",
                        source,
                        "error",
                        e.javaClass.simpleName,
                    ),
                    metricsComponent,
                    "callbackContextErrors",
                )
                .inc()
            throw e
        }
        try {
            callback()
        } finally {
            middleware.rawAfterExecution(invocation)
        }
    }
}
