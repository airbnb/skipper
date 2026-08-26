package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.NoOpMetrics
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.RawActionInvocation
import com.airbnb.skipper.RawRequestContextMiddleware
import com.airbnb.skipper.internal.TestUtils
import com.airbnb.skipper.metrics.SkipperCounter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * A [RawRequestContextMiddleware] that records what the engine did to it, so tests can assert that
 * a callback really ran with the workflow's context installed instead of only asserting that
 * nothing looked wrong. Shared with [WorkflowExecutionTaskHandlerTest] and
 * [CompensationFlowTaskHandlerTest].
 *
 * @param beforeError when set, [rawBeforeExecution] throws it — the "host middleware could not
 *   establish the context" case.
 */
internal class RecordingRequestContextMiddleware(
    private val beforeError: Throwable? = null,
) : RawRequestContextMiddleware {
    /**
     * The hooks that ran, in order. Callers append `"callback"` from inside the callback body so a
     * single assertion pins the whole install / invoke / tear-down sequence.
     */
    val events: MutableList<String> = mutableListOf()

    /** The payload each [rawBeforeExecution] was handed. */
    val beforeInvocations: MutableList<RawActionInvocation> = mutableListOf()

    /** The payload each [rawAfterExecution] was handed. */
    val afterInvocations: MutableList<RawActionInvocation> = mutableListOf()

    override fun rawBeforeExecution(invocation: RawActionInvocation): Any? {
        events.add("before")
        beforeInvocations.add(invocation)
        if (beforeError != null) {
            throw beforeError
        }
        return invocation.requestContext
    }

    override fun rawAfterExecution(invocation: RawActionInvocation) {
        events.add("after")
        afterInvocations.add(invocation)
    }
}

/** One counter increment captured by [CapturingMetrics]. Untagged increments carry empty [tags]. */
internal class CounterIncrement(
    /** The positional name parts — for Skipper counters, `component` then counter name. */
    val names: List<String>,
    val tags: Map<String, String>,
)

/**
 * A [com.airbnb.skipper.Metrics] double that captures counter increments, tagged or not — the
 * bracket's `callbackContextErrors` uses the tagged overload, the handlers' own counters the
 * untagged one. The production default, [NoOpMetrics], discards everything, so metric assertions
 * need this instead. Shared with [WorkflowExecutionTaskHandlerTest] and
 * [CompensationFlowTaskHandlerTest].
 */
internal class CapturingMetrics : NoOpMetrics() {
    private val increments: MutableList<CounterIncrement> = mutableListOf()

    override fun counter(vararg names: String): SkipperCounter = recording(CounterIncrement(names.toList(), emptyMap()))

    override fun counter(
        tags: Map<String, String>,
        vararg names: String
    ): SkipperCounter = recording(CounterIncrement(names.toList(), tags.toMap()))

    /** Every increment of the counter named [counterName], in emission order. */
    fun incrementsOf(counterName: String): List<CounterIncrement> = increments.filter { it.names.last() == counterName }

    private fun recording(increment: CounterIncrement): SkipperCounter =
        object : SkipperCounter {
            override fun inc() {
                increments.add(increment)
            }

            override fun inc(n: Long) {
                increments.add(increment)
            }
        }
}

class CallbackRequestContextBracketTest {
    private val workflowInstance = TestUtils.getWorkflowInstance()

    @Test
    fun callbackRunsBetweenContextInstallAndTeardown() {
        val middleware = RecordingRequestContextMiddleware()

        bracketWith(middleware).around(workflowInstance, "onSuccess") {
            middleware.events.add("callback")
        }

        assertEquals(listOf("before", "callback", "after"), middleware.events)
    }

    @Test
    fun contextIsInstalledWithTheWorkflowsOwnPayload() {
        val middleware = RecordingRequestContextMiddleware()

        bracketWith(middleware).around(workflowInstance, "onSuccess") {}

        val expected =
            RawActionInvocation(
                workflowInstance.workflowId,
                workflowInstance.requestContext,
                workflowInstance.extraRequestData,
            )
        assertEquals(listOf(expected), middleware.beforeInvocations)
        assertEquals(listOf(expected), middleware.afterInvocations)
    }

    @Test
    fun contextIsTornDownWhenTheCallbackThrows() {
        val middleware = RecordingRequestContextMiddleware()
        val callbackError = RuntimeException("handler error!")

        val thrown =
            assertThrows(RuntimeException::class.java) {
                bracketWith(middleware).around(workflowInstance, "onSuccess") {
                    middleware.events.add("callback")
                    throw callbackError
                }
            }

        assertSame(callbackError, thrown)
        assertEquals(listOf("before", "callback", "after"), middleware.events)
    }

    @Test
    fun contextInstallFailureSkipsBothTheCallbackAndItsTeardown() {
        val installError = NonRetryableError("userId is missing")
        val middleware = RecordingRequestContextMiddleware(beforeError = installError)
        var callbackRan = false

        val thrown =
            assertThrows(NonRetryableError::class.java) {
                bracketWith(middleware).around(workflowInstance, "onSuccess") {
                    callbackRan = true
                }
            }

        assertSame(installError, thrown)
        assertFalse(callbackRan, "the callback must not run without its own request context")
        // No "after": rawBeforeExecution threw before installing anything, so there is nothing to
        // tear down.
        assertEquals(listOf("before"), middleware.events)
    }

    @Test
    fun contextInstallFailureIsCountedOncePerSourceAndErrorType() {
        val middleware = RecordingRequestContextMiddleware(beforeError = NonRetryableError("boom"))
        val metrics = CapturingMetrics()

        assertThrows(NonRetryableError::class.java) {
            CallbackRequestContextBracket(middleware, metrics, "workflowExecutionTaskHandler")
                .around(workflowInstance, "onWorkflowTimeout") {}
        }

        val increments = metrics.incrementsOf("callbackContextErrors")
        assertEquals(1, increments.size)
        assertEquals(
            listOf("workflowExecutionTaskHandler", "callbackContextErrors"),
            increments[0].names,
        )
        assertEquals(
            mapOf("source" to "onWorkflowTimeout", "error" to "NonRetryableError"),
            increments[0].tags,
        )
    }

    @Test
    fun successfulCallbackEmitsNoErrorCounter() {
        val metrics = CapturingMetrics()

        CallbackRequestContextBracket(
            RecordingRequestContextMiddleware(),
            metrics,
            "workflowExecutionTaskHandler",
        )
            .around(workflowInstance, "onSuccess") {}

        assertEquals(emptyList<CounterIncrement>(), metrics.incrementsOf("callbackContextErrors"))
    }

    private fun bracketWith(middleware: RawRequestContextMiddleware): CallbackRequestContextBracket =
        CallbackRequestContextBracket(
            middleware,
            NoOpMetrics.INSTANCE,
            "workflowExecutionTaskHandler",
        )
}
