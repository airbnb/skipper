package com.airbnb.skipper

import com.airbnb.skipper.internal.InternalError
import java.time.Duration
import java.util.concurrent.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ChildWorkflowBuilderTest {
    open class ParentWorkflow : Workflow()

    open class ChildWorkflow : Workflow() {
        @WorkflowMethod
        open fun run(): String = "done"
    }

    /** Hands back a plain child and records the parent id the builder passed. */
    class RecordingFactory : IWorkflowFactory {
        var parentWorkflowId: String? = null

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
            this.parentWorkflowId = parentWorkflowId
            return ChildWorkflow() as T
        }

        override fun cloneAsNew(
            workflowIdToClone: String,
            newWorkflowId: String,
            requestContext: Any?
        ) {
        }
    }

    private val factory = RecordingFactory()
    private val parent = ParentWorkflow().apply { id = "parent" }

    private fun childBuilder() = factory.builder(ChildWorkflow::class.java, "child").asChildOf(parent)

    private fun startThrowing(error: Exception): Exception = assertThrows<Exception> { childBuilder().start<String> { throw error } }

    @Test
    fun start_returnsWhatTheCallReturnsAndRecordsTheParent() {
        assertThat(childBuilder().start { it.run() }).isEqualTo("done")
        assertThat(factory.parentWorkflowId).isEqualTo("parent")
    }

    @Test
    fun start_infrastructureFailure_becomesRetryableAfterTheDefaultDelay() {
        val thrown = startThrowing(TimeoutException("store timed out"))

        assertThat(thrown).isInstanceOf(RetryableError::class.java)
        assertThat((thrown as RetryableError).getNextRetryDelay())
            .isEqualTo(ChildWorkflowBuilder.DEFAULT_START_FAILURE_RETRY_DELAY)
        assertThat(thrown.message).contains("child").contains("store timed out")
        assertThat(thrown.cause).isInstanceOf(ApplicationError::class.java)
    }

    @Test
    fun start_infrastructureFailure_usesTheConfiguredDelay() {
        val thrown =
            assertThrows<RetryableError> {
                childBuilder()
                    .startFailureRetryDelay(Duration.ofSeconds(42))
                    .start<String> { throw IllegalStateException("connection dropped") }
            }

        assertThat(thrown.getNextRetryDelay()).isEqualTo(Duration.ofSeconds(42))
    }

    @Test
    fun start_alreadyClassifiedOrInvalid_isRethrownUnchanged() {
        val unchanged =
            listOf(
                NonRetryableError("child failed"),
                RetryableError("child asked to retry", null, Duration.ofSeconds(1)),
                InternalError("engine failure"),
                IllegalArgumentException("invalid child request"),
                ValidationError("invalid child input"),
            )
        for (error in unchanged) {
            assertThat(startThrowing(error)).isSameAs(error)
        }
    }
}
