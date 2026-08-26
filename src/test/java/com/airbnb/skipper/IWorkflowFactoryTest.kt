package com.airbnb.skipper

import com.airbnb.skipper.api.WorkflowInstanceView
import com.airbnb.skipper.testutils.TestRequestContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.same
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify

class IWorkflowFactoryTest {
    class SampleWorkflow : Workflow()

    open class TestFactory : IWorkflowFactory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Workflow> invoke(
            workflowClass: Class<T>,
            workflowId: String,
            requestContext: Any?,
            callbackHandler: Class<out WorkflowCallbackHandler>?,
            workflowOptions: WorkflowOptions?,
            runAsync: Boolean,
            parentWorkflowId: String?
        ): T {
            return SampleWorkflow() as T
        }

        /** Counts dispatches through the detached overload, so tests can assert which one was used. */
        var detachedOverloadCalls = 0

        // Overridden so the detached overload is a real method on this class, which lets the spy
        // record the arguments the builder passed rather than only the delegated call.
        @Suppress("UNCHECKED_CAST")
        override fun <T : Workflow> invoke(
            workflowClass: Class<T>,
            workflowId: String,
            requestContext: Any?,
            callbackHandler: Class<out WorkflowCallbackHandler>?,
            workflowOptions: WorkflowOptions?,
            runAsync: Boolean,
            parentWorkflowId: String?,
            detached: Boolean
        ): T {
            detachedOverloadCalls++
            return SampleWorkflow() as T
        }

        override fun cloneAsNew(
            workflowIdToClone: String,
            newWorkflowId: String,
            requestContext: Any?
        ) {
        }
    }

    open class SampleCallbackHandler : WorkflowCallbackHandler {
        override fun onSuccess(workflowInstance: WorkflowInstanceView) {
        }

        override fun onNonRetryableError(
            workflowInstance: WorkflowInstanceView,
            error: Throwable?
        ) {
        }

        override fun onWorkflowInWaitingStatus(workflowInstance: WorkflowInstanceView) {
        }

        override fun onWorkflowTimeout(workflowInstance: WorkflowInstanceView) {
        }
    }

    private val WORKFLOW_ID = "test-id"
    private val SKIPPER_CONTEXT: Any = TestRequestContext.builder().userId("1").build()

    @Test
    fun invoke_workflowClass_workflowId_requestContext() {
        val factory = spy(TestFactory())
        factory(SampleWorkflow::class.java, WORKFLOW_ID, SKIPPER_CONTEXT)
        verify(factory).invoke(eq(SampleWorkflow::class.java), eq(WORKFLOW_ID), same(SKIPPER_CONTEXT))
    }

    @Test
    fun invoke_reified_workflowId_requestContext() {
        val factory = spy(TestFactory())
        factory<SampleWorkflow>(WORKFLOW_ID, SKIPPER_CONTEXT)
        verify(factory).invoke(eq(SampleWorkflow::class.java), eq(WORKFLOW_ID), same(SKIPPER_CONTEXT))
    }

    @Test
    fun invoke_callbackhandler() {
        val factory = spy(TestFactory())
        factory.builder(SampleWorkflow::class.java, WORKFLOW_ID)
            .requestContext(SKIPPER_CONTEXT)
            .callbackHandler(SampleCallbackHandler::class.java)
            .build()
        verify(factory).invoke(
            eq(SampleWorkflow::class.java),
            eq(WORKFLOW_ID),
            same(SKIPPER_CONTEXT),
            eq(SampleCallbackHandler::class.java),
            eq(null),
            eq(false),
            eq(null)
        )
    }

    @Test
    fun invoke_callbackhandler_andRunAsync() {
        val factory = spy(TestFactory())
        factory.builder(SampleWorkflow::class.java, WORKFLOW_ID)
            .requestContext(SKIPPER_CONTEXT)
            .callbackHandler(SampleCallbackHandler::class.java)
            .runAsync()
            .build()
        verify(factory).invoke(
            eq(SampleWorkflow::class.java),
            eq(WORKFLOW_ID),
            same(SKIPPER_CONTEXT),
            eq(SampleCallbackHandler::class.java),
            eq(null),
            eq(true),
            eq(null)
        )
    }

    @Test
    fun invoke_detached() {
        val factory = spy(TestFactory())
        factory.builder(SampleWorkflow::class.java, WORKFLOW_ID)
            .requestContext(SKIPPER_CONTEXT)
            .runAsync()
            .detached()
            .build()
        verify(factory).invoke(
            eq(SampleWorkflow::class.java),
            eq(WORKFLOW_ID),
            same(SKIPPER_CONTEXT),
            eq(null),
            eq(null),
            eq(true),
            eq(null),
            eq(true)
        )
    }

    /**
     * A non-detached build must dispatch through the seven-argument overload, not the detached one:
     * consumers stub that exact overload on mocked factories, and routing through the new one would
     * silently bypass their stubs.
     */
    @Test
    fun invoke_withoutDetached_doesNotUseTheDetachedOverload() {
        val factory = TestFactory()
        factory.builder(SampleWorkflow::class.java, WORKFLOW_ID).build()
        assertThat(factory.detachedOverloadCalls).isZero()
    }

    @Test
    fun invoke_detached_usesTheDetachedOverload() {
        val factory = TestFactory()
        factory.builder(SampleWorkflow::class.java, WORKFLOW_ID).detached().build()
        assertThat(factory.detachedOverloadCalls).isEqualTo(1)
    }
}
