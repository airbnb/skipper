package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.Event
import com.airbnb.skipper.EventPublisher
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.NoOpMetrics
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.OptimisticLockingError
import com.airbnb.skipper.RawActionInvocation
import com.airbnb.skipper.RawRequestContextMiddleware
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.SkipperInjector
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowCallbackHandler
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.api.WorkflowInstanceView
import com.airbnb.skipper.internal.CompensationExecutor
import com.airbnb.skipper.internal.ExecutionContext
import com.airbnb.skipper.internal.TestUtils
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.internal.storage.WorkflowUpdateRequest
import com.airbnb.skipper.metrics.SkipperCounter
import io.vavr.collection.List
import io.vavr.control.Option
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CompensationFlowTaskHandlerTest {
    // All dependencies mocked for proper unit testing
    private lateinit var mockCompensationExecutor: CompensationExecutor
    private lateinit var mockClock: java.time.Clock
    private lateinit var mockWorkflowStore: WorkflowStore
    private lateinit var mockEventPublisher: EventPublisher
    private lateinit var mockInjector: SkipperInjector
    private lateinit var mockCallbackHandler: TestCallbackHandler
    private lateinit var taskHandler: CompensationFlowTaskHandler
    private lateinit var mockExecutorService: ExecutorService
    private val unexpectedErrorRetryDelay = Duration.ofSeconds(10)
    private val metrics: Metrics = NoOpMetrics.INSTANCE

    @BeforeEach
    fun setUp() {
        // Set up all mocks for proper unit testing
        mockCompensationExecutor = mock()
        mockClock = mock()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        mockWorkflowStore = mock()
        whenever(mockWorkflowStore.getActionCheckpoints(any())).thenReturn(List.empty())
        mockExecutorService = mock()
        mockEventPublisher = mock()
        mockInjector = mock()
        mockCallbackHandler = mock()

        // Create handler with all mocked dependencies
        taskHandler =
            CompensationFlowTaskHandler(
                mockWorkflowStore,
                mockCompensationExecutor,
                unexpectedErrorRetryDelay,
                mockClock,
                metrics,
                mockEventPublisher,
                mockInjector,
                RawRequestContextMiddleware.NOOP
            )
    }

    // Tests for ready-for-processing states
    @Test
    fun testHandleWithWorkflowInErrorStatus() {
        testReadyForProcessingStatus(WorkflowInstance.Status.ERROR, "ready for processing")
    }

    @Test
    fun testHandleWithWorkflowInCompensationInProgressStatus() {
        testReadyForProcessingStatus(
            WorkflowInstance.Status.COMPENSATION_IN_PROGRESS,
            "ready for processing"
        )
    }

    @Test
    fun testHandleWithWorkflowInCompensationErrorStatus() {
        testReadyForProcessingStatus(
            WorkflowInstance.Status.COMPENSATION_ERROR,
            "ready for processing"
        )
    }

    // Tests for non-terminal states (should retry in 1 second)
    @Test
    fun testHandleWithWorkflowInRunningStatus() {
        testNonTerminalStatus(WorkflowInstance.Status.RUNNING)
    }

    @Test
    fun testHandleWithWorkflowInWaitingStatus() {
        testNonTerminalStatus(WorkflowInstance.Status.WAITING)
    }

    @Test
    fun testHandleWithWorkflowInRetriesExhaustedStatus() {
        testNonTerminalStatus(WorkflowInstance.Status.RETRIES_EXHAUSTED)
    }

    // Tests for terminal states that are no-op
    @Test
    fun testHandleWithWorkflowInCompletedStatus() {
        testAlreadyCompletedStatus(WorkflowInstance.Status.COMPLETED, "no-op")
    }

    @Test
    fun testHandleWithWorkflowInCompensationCompletedStatus() {
        testAlreadyCompletedStatus(WorkflowInstance.Status.COMPENSATION_COMPLETED, "no-op")
    }

    @Test
    fun testHandleWithWorkflowInTimeoutStatus() {
        testAlreadyCompletedStatus(WorkflowInstance.Status.TIMEOUT, "no-op")
    }

    // Tests for error conditions
    @Test
    fun testHandleWithNonExistentWorkflow() {
        testErrorCondition("non-existent-workflow-id", "non-existent", "non-existent workflow")
    }

    @Test
    fun testHandleWithNullPayload() {
        testErrorCondition(null, "null-payload", "null payload")
    }

    @Test
    fun testHandleWithEmptyPayload() {
        testErrorCondition("", "empty-payload", "empty payload")
    }

    // Helper methods to reduce duplication
    private fun testReadyForProcessingStatus(
        status: WorkflowInstance.Status,
        description: String
    ) {
        val workflowInstance = createTestWorkflowInstance(status)

        // Mock workflow store to return our test instance
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))

        // Mock successful compensation result
        val successResult = CompensationExecutor.CompensationResult.success()
        whenever(mockCompensationExecutor.executeCompensationFlow(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(successResult))

        val task = createCompensationTask(WORKFLOW_ID)
        val result = taskHandler.handle(task, mockExecutorService).join()

        assertTrue(
            result.isEmpty,
            String.format(
                "Should complete successfully for %s status workflow (%s)",
                status,
                description
            )
        )
    }

    private fun testNonTerminalStatus(status: WorkflowInstance.Status) {
        val workflowInstance = createTestWorkflowInstance(status)

        // Mock workflow store to return our test instance
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))

        val task = createCompensationTask(WORKFLOW_ID)
        val result = taskHandler.handle(task, mockExecutorService).join()

        assertTrue(
            result.isDefined,
            String.format("Should return retry delay for %s status workflow", status)
        )
        assertEquals(1, result.get().epochSecond, "Should retry after 1 second")
    }

    private fun testAlreadyCompletedStatus(
        status: WorkflowInstance.Status,
        description: String
    ) {
        val workflowInstance = createTestWorkflowInstance(status)

        // Mock workflow store to return our test instance
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))

        val task = createCompensationTask(WORKFLOW_ID)
        val result = taskHandler.handle(task, mockExecutorService).join()

        assertTrue(
            result.isEmpty,
            String.format(
                "Should complete successfully for %s status workflow (%s)",
                status,
                description
            )
        )
    }

    private fun testErrorCondition(
        workflowId: String?,
        taskIdSuffix: String,
        description: String
    ) {
        val task = createCompensationTask(workflowId, taskIdSuffix)
        val result = taskHandler.handle(task, mockExecutorService).join()

        assertTrue(result.isDefined, String.format("Should retry after error for %s", description))
        assertEquals(
            10,
            result.get().epochSecond,
            "Should retry after unexpectedErrorRetryDelay"
        )
    }

    private fun createCompensationTask(workflowId: String?): Task<String> =
        createCompensationTask(
            workflowId,
            if (workflowId == null) "null" else (if (workflowId.isEmpty()) "empty" else workflowId)
        )

    private fun createCompensationTask(
        workflowId: String?,
        taskIdSuffix: String
    ): Task<String> =
        Task.builder<String>()
            .id("compensation-$taskIdSuffix")
            .type(Task.Type.COMPENSATION)
            .payload(workflowId)
            .dedupToken("compensation-$taskIdSuffix")
            .createdAt(Instant.EPOCH)
            .runAfter(Instant.EPOCH)
            .executionTimeout(Duration.ofMinutes(5))
            .build()

    private fun createTestWorkflowInstance(status: WorkflowInstance.Status): WorkflowInstance =
        WorkflowInstance.builder()
            .workflowId(WORKFLOW_ID)
            .workflowClass(Workflow::class.java)
            .workflowMethod("test-method")
            .input("test")
            .requestContext(TestUtils.REQUEST_CONTEXT)
            .extraRequestData(TestUtils.EXTRA_REQUEST_DATA)
            .state(io.vavr.collection.HashMap.empty())
            .result(CompletableFuture())
            .version(1)
            .status(status)
            .createdAt(Instant.EPOCH)
            .build()

    /**
     * [Metrics] that records every counter it is asked for (both the tagged and untagged
     * overloads), so a test can assert which branch of the persistence error handling ran.
     */
    private class RecordingMetrics : NoOpMetrics() {
        val counters = mutableListOf<String>()

        override fun counter(vararg names: String): SkipperCounter {
            counters.add(names.joinToString("."))
            return super.counter(*names)
        }

        override fun counter(
            tags: kotlin.collections.Map<String, String>,
            vararg names: String
        ): SkipperCounter {
            counters.add(names.joinToString("."))
            return super.counter(tags, *names)
        }
    }

    /**
     * Run a compensation flow whose result persistence throws [error] and return the counters the
     * handler emitted.
     */
    private fun countersForPersistenceFailure(error: Throwable): kotlin.collections.List<String> {
        val recordingMetrics = RecordingMetrics()
        val handler =
            CompensationFlowTaskHandler(
                mockWorkflowStore,
                mockCompensationExecutor,
                unexpectedErrorRetryDelay,
                mockClock,
                recordingMetrics,
                mockEventPublisher,
                mockInjector,
                RawRequestContextMiddleware.NOOP
            )
        val workflowInstance = createTestWorkflowInstance()
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))
        whenever(mockCompensationExecutor.executeCompensationFlow(any(), any(), any()))
            .thenReturn(
                CompletableFuture.completedFuture(CompensationExecutor.CompensationResult.success())
            )
        whenever(mockWorkflowStore.updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any()))
            .thenThrow(error)
        val result = handler.handle(createCompensationTask(WORKFLOW_ID), mockExecutorService).join()
        // Either way the task is retried after the configured delay - only the reporting differs.
        assertTrue(result.isDefined, "task should be retried")
        assertEquals(mockClock.instant().plus(unexpectedErrorRetryDelay), result.get())
        return recordingMetrics.counters
    }

    @Test
    fun testOptimisticLockOnPersistIsReportedAsContentionNotAsProcessingError() {
        val counters = countersForPersistenceFailure(OptimisticLockingError("stale version"))
        // Same expected contention as the workflow execution path: counted, not errored.
        assertTrue(
            counters.any { it.endsWith("optimisticLockingError") },
            "expected an optimisticLockingError counter, got $counters",
        )
        assertFalse(
            counters.any { it.endsWith("compensationProcessingError") },
            "contention must not be counted as a processing error, got $counters",
        )
    }

    @Test
    fun testUnexpectedPersistErrorIsStillReportedAsProcessingError() {
        // Guard against the contention branch being too broad.
        val counters = countersForPersistenceFailure(IllegalStateException("boom"))
        assertTrue(
            counters.any { it.endsWith("compensationProcessingError") },
            "expected a compensationProcessingError counter, got $counters",
        )
        assertFalse(
            counters.any { it.endsWith("optimisticLockingError") },
            "a genuine fault must not be counted as contention, got $counters",
        )
    }

    @Test
    fun testCompensationExecutionSuccessful() {
        // Create test workflow instance
        val workflowInstance = createTestWorkflowInstance()

        // Mock workflow store to return our test instance
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))

        // Mock successful compensation result
        val successResult = CompensationExecutor.CompensationResult.success()
        whenever(mockCompensationExecutor.executeCompensationFlow(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(successResult))

        // Execute compensation task
        val task = createCompensationTask(WORKFLOW_ID)
        val result = taskHandler.handle(task, mockExecutorService).join()

        // Verify task completes (no retry needed)
        assertTrue(result.isEmpty, "Task should complete successfully")

        // Verify CompensationExecutor was called
        verify(mockCompensationExecutor)
            .executeCompensationFlow(
                eq(workflowInstance),
                eq(mockExecutorService),
                any<ExecutionContext>()
            )

        // Verify workflow store was updated with COMPENSATION_COMPLETED status
        verify(mockWorkflowStore)
            .updateWorkflowAndStoreCheckpointsAndTimers(any<WorkflowUpdateRequest>(), any(), any())
    }

    @Test
    fun testCompensationExecutionRetryableError() {
        // Create test workflow instance
        val workflowInstance = createTestWorkflowInstance()

        // Mock workflow store to return our test instance
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))

        // Mock retryable error result
        val retryableError = RetryableError("compensation retry needed")
        val retryResult = CompensationExecutor.CompensationResult.retryableError(retryableError)
        whenever(mockCompensationExecutor.executeCompensationFlow(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(retryResult))

        val task = createCompensationTask(WORKFLOW_ID)
        val result = taskHandler.handle(task, mockExecutorService).join()

        // Verify task should retry
        assertTrue(result.isDefined, "Task should schedule retry")

        // Verify CompensationExecutor was called
        verify(mockCompensationExecutor)
            .executeCompensationFlow(
                eq(workflowInstance),
                eq(mockExecutorService),
                any<ExecutionContext>()
            )

        // Verify workflow store was updated with COMPENSATION_IN_PROGRESS status
        verify(mockWorkflowStore)
            .updateWorkflowAndStoreCheckpointsAndTimers(any<WorkflowUpdateRequest>(), any(), any())
    }

    @Test
    fun testCompensationExecutionNonRetryableError() {
        // Create test workflow instance
        val workflowInstance = createTestWorkflowInstance()

        // Mock workflow store to return our test instance
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))

        // Mock non-retryable error result
        val nonRetryableError = NonRetryableError("compensation failed permanently")
        val errorResult =
            CompensationExecutor.CompensationResult.nonRetryableError(nonRetryableError)
        whenever(mockCompensationExecutor.executeCompensationFlow(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(errorResult))

        val task = createCompensationTask(WORKFLOW_ID)
        val result = taskHandler.handle(task, mockExecutorService).join()

        // Verify task completes (no retry for terminal error)
        assertTrue(result.isEmpty, "Task should complete (terminal error)")

        // Verify CompensationExecutor was called
        verify(mockCompensationExecutor)
            .executeCompensationFlow(
                eq(workflowInstance),
                eq(mockExecutorService),
                any<ExecutionContext>()
            )

        // Verify workflow store was updated with COMPENSATION_ERROR status
        verify(mockWorkflowStore)
            .updateWorkflowAndStoreCheckpointsAndTimers(any<WorkflowUpdateRequest>(), any(), any())
    }

    @Test
    fun testCompensationExecutionUnexpectedException() {
        // Create test workflow instance
        val workflowInstance = createTestWorkflowInstance()

        // Mock workflow store to return our test instance
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))

        // Mock unexpected exception
        val failedFuture = CompletableFuture<CompensationExecutor.CompensationResult>()
        failedFuture.completeExceptionally(RuntimeException("Unexpected compensation error"))
        whenever(mockCompensationExecutor.executeCompensationFlow(any(), any(), any()))
            .thenReturn(failedFuture)

        val task = createCompensationTask(WORKFLOW_ID)
        val result = taskHandler.handle(task, mockExecutorService).join()

        // Verify task schedules retry
        assertTrue(result.isDefined, "Task should schedule retry after unexpected error")

        // Verify CompensationExecutor was called
        verify(mockCompensationExecutor)
            .executeCompensationFlow(
                eq(workflowInstance),
                eq(mockExecutorService),
                any<ExecutionContext>()
            )

        // Note: For unexpected exceptions, workflow store is NOT updated
        // The exception bypasses processCompensationResult and goes directly to exceptionally block
    }

    private fun createTestWorkflowInstance(): WorkflowInstance = createTestWorkflowInstance(WorkflowInstance.Status.ERROR)

    // Tests for callback notification functionality
    @Test
    fun testCompensationCompleted_WithCallbackHandler_Success() {
        // Create workflow instance with callback handler
        val workflowInstance = createTestWorkflowInstanceWithCallback()

        // Mock workflow store and injector
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))
        whenever(mockInjector.getInstance(TestCallbackHandler::class.java))
            .thenReturn(mockCallbackHandler)

        // Mock successful compensation result
        val successResult = CompensationExecutor.CompensationResult.success()
        whenever(mockCompensationExecutor.executeCompensationFlow(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(successResult))

        // Execute compensation task
        val task = createCompensationTask(WORKFLOW_ID)
        val result = taskHandler.handle(task, mockExecutorService).join()

        // Verify task completes successfully
        assertTrue(result.isEmpty, "Task should complete successfully")

        // Verify callback handler was called
        verify(mockCallbackHandler).onCompensationCompleted(any())

        // Verify workflow store was updated
        verify(mockWorkflowStore).updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any())
    }

    @Test
    fun testCompensationCompleted_WithCallbackHandler_CallbackFails() {
        // Create workflow instance with callback handler
        val workflowInstance = createTestWorkflowInstanceWithCallback()

        // Mock workflow store and injector
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))
        whenever(mockInjector.getInstance(TestCallbackHandler::class.java))
            .thenReturn(mockCallbackHandler)

        // Mock callback to throw exception
        doThrow(RuntimeException("Callback failed"))
            .whenever(mockCallbackHandler)
            .onCompensationCompleted(any())

        // Mock successful compensation result
        val successResult = CompensationExecutor.CompensationResult.success()
        whenever(mockCompensationExecutor.executeCompensationFlow(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(successResult))

        // Execute compensation task
        val task = createCompensationTask(WORKFLOW_ID)
        val result = taskHandler.handle(task, mockExecutorService).join()

        // Verify task schedules retry due to callback failure
        assertTrue(result.isDefined, "Task should schedule retry after callback failure")
        assertEquals(Instant.EPOCH.plus(unexpectedErrorRetryDelay), result.get())

        // Verify callback was attempted
        verify(mockCallbackHandler).onCompensationCompleted(any())
    }

    @Test
    fun testCompensationCompleted_WithoutCallbackHandler() {
        // Create workflow instance without callback handler
        val workflowInstance = createTestWorkflowInstance(WorkflowInstance.Status.ERROR)

        // Mock workflow store
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))

        // Mock successful compensation result
        val successResult = CompensationExecutor.CompensationResult.success()
        whenever(mockCompensationExecutor.executeCompensationFlow(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(successResult))

        // Execute compensation task
        val task = createCompensationTask(WORKFLOW_ID)
        val result = taskHandler.handle(task, mockExecutorService).join()

        // Verify task completes successfully
        assertTrue(result.isEmpty, "Task should complete successfully")

        // Verify no callback was attempted
        verify(mockCallbackHandler, never()).onCompensationCompleted(any())
    }

    @Test
    fun testCompensationError_WithCallbackHandler() {
        // Create workflow instance with callback handler
        val workflowInstance = createTestWorkflowInstanceWithCallback()

        // Mock workflow store and injector
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))
        whenever(mockInjector.getInstance(TestCallbackHandler::class.java))
            .thenReturn(mockCallbackHandler)

        // Mock compensation error result
        val error = NonRetryableError("Compensation failed")
        val errorResult = CompensationExecutor.CompensationResult.nonRetryableError(error)
        whenever(mockCompensationExecutor.executeCompensationFlow(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(errorResult))

        // Execute compensation task
        val task = createCompensationTask(WORKFLOW_ID)
        val result = taskHandler.handle(task, mockExecutorService).join()

        // Verify task completes (no retry for non-retryable error)
        assertTrue(result.isEmpty, "Task should complete after compensation error")

        // Verify error callback was called
        verify(mockCallbackHandler).onCompensationError(any(), eq(error))

        // Verify workflow store was updated
        verify(mockWorkflowStore).updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any())
    }

    @Test
    fun testCompensationError_CallbackFails() {
        // Create workflow instance with callback handler
        val workflowInstance = createTestWorkflowInstanceWithCallback()

        // Mock workflow store and injector
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))
        whenever(mockInjector.getInstance(TestCallbackHandler::class.java))
            .thenReturn(mockCallbackHandler)

        // Mock error callback to throw exception
        doThrow(RuntimeException("Error callback failed"))
            .whenever(mockCallbackHandler)
            .onCompensationError(any(), any())

        // Mock compensation error result
        val error = NonRetryableError("Compensation failed")
        val errorResult = CompensationExecutor.CompensationResult.nonRetryableError(error)
        whenever(mockCompensationExecutor.executeCompensationFlow(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(errorResult))

        // Execute compensation task
        val task = createCompensationTask(WORKFLOW_ID)
        val result = taskHandler.handle(task, mockExecutorService).join()

        // Verify task still completes (error callbacks are best-effort)
        assertTrue(result.isEmpty, "Task should still complete even if error callback fails")

        // Verify error callback was attempted
        verify(mockCallbackHandler).onCompensationError(any(), eq(error))
    }

    @Test
    fun testNoOpWithCompensationCompleted_RetryCallback_Success() {
        // Create workflow instance with COMPENSATION_COMPLETED status and callback handler
        val workflowInstance =
            createTestWorkflowInstanceWithCallback(WorkflowInstance.Status.COMPENSATION_COMPLETED)

        // Mock workflow store and injector
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))
        whenever(mockInjector.getInstance(TestCallbackHandler::class.java))
            .thenReturn(mockCallbackHandler)

        // Execute compensation task
        val task = createCompensationTask(WORKFLOW_ID)
        val result = taskHandler.handle(task, mockExecutorService).join()

        // Verify task completes successfully
        assertTrue(result.isEmpty, "Task should complete successfully after callback retry")

        // Verify callback handler was called for retry
        verify(mockCallbackHandler).onCompensationCompleted(any())

        // Verify compensation executor was NOT called (no-op case)
        verify(mockCompensationExecutor, never()).executeCompensationFlow(any(), any(), any())
    }

    @Test
    fun testNoOpWithCompensationCompleted_RetryCallback_Fails() {
        // Create workflow instance with COMPENSATION_COMPLETED status and callback handler
        val workflowInstance =
            createTestWorkflowInstanceWithCallback(WorkflowInstance.Status.COMPENSATION_COMPLETED)

        // Mock workflow store and injector
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))
        whenever(mockInjector.getInstance(TestCallbackHandler::class.java))
            .thenReturn(mockCallbackHandler)

        // Mock callback to fail again
        doThrow(RuntimeException("Callback still failing"))
            .whenever(mockCallbackHandler)
            .onCompensationCompleted(any())

        // Execute compensation task
        val task = createCompensationTask(WORKFLOW_ID)
        val result = taskHandler.handle(task, mockExecutorService).join()

        // Verify task schedules retry
        assertTrue(result.isDefined, "Task should schedule retry after callback failure")
        assertEquals(Instant.EPOCH.plus(unexpectedErrorRetryDelay), result.get())

        // Verify callback was attempted
        verify(mockCallbackHandler).onCompensationCompleted(any())
    }

    @Test
    fun testNoOpWithOtherTerminalStatus_NoCallbackRetry() {
        // Create workflow instance with other terminal status (not COMPENSATION_COMPLETED)
        val workflowInstance =
            createTestWorkflowInstanceWithCallback(WorkflowInstance.Status.COMPLETED)

        // Mock workflow store
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))

        // Execute compensation task
        val task = createCompensationTask(WORKFLOW_ID)
        val result = taskHandler.handle(task, mockExecutorService).join()

        // Verify task completes without retry
        assertTrue(
            result.isEmpty,
            "Task should complete without callback retry for non-compensation-completed status"
        )

        // Verify no callback was attempted
        verify(mockCallbackHandler, never()).onCompensationCompleted(any())
        verify(mockCallbackHandler, never()).onCompensationError(any(), any())
    }

    // Helper methods for callback tests
    private fun createTestWorkflowInstanceWithCallback(): WorkflowInstance = createTestWorkflowInstanceWithCallback(WorkflowInstance.Status.ERROR)

    private fun createTestWorkflowInstanceWithCallback(status: WorkflowInstance.Status): WorkflowInstance =
        WorkflowInstance.builder()
            .workflowId(WORKFLOW_ID)
            .workflowClass(Workflow::class.java)
            .workflowMethod("test-method")
            .input("test")
            .requestContext(TestUtils.REQUEST_CONTEXT)
            .extraRequestData(TestUtils.EXTRA_REQUEST_DATA)
            .state(io.vavr.collection.HashMap.empty())
            .result(CompletableFuture())
            .version(1)
            .status(status)
            .createdAt(Instant.EPOCH)
            .callbackHandler(TestCallbackHandler::class.java)
            .build()

    // -----------------------------------------------------------------------------------------
    // Callback request context. Both callbacks this handler fires run with the workflow's own
    // request context installed and torn down again, and the engine's reaction to the callback's
    // outcome — the returned retry instant, the notification counters, and the published
    // compensation events — is unchanged. The two paths have deliberately different error
    // contracts: the complete-callback rethrows so the task is rescheduled, the error-callback
    // swallows because it is best-effort.
    //
    // The suite above wires RawRequestContextMiddleware.NOOP, which is a pure pass-through, so it
    // is evidence for the no-middleware configuration only. These tests install a middleware that
    // really runs.
    // -----------------------------------------------------------------------------------------

    @Test
    fun testOnCompensationCompletedRunsInWorkflowRequestContextAndKeepsOutcome() {
        val middleware = RecordingRequestContextMiddleware()
        val recordedMetrics = CapturingMetrics()
        val workflowInstance = givenCompensationCompletes()
        doAnswer {
            middleware.events.add("callback")
            null
        }
            .whenever(mockCallbackHandler)
            .onCompensationCompleted(any())

        val result =
            handlerWith(middleware, recordedMetrics)
                .handle(createCompensationTask(WORKFLOW_ID), mockExecutorService)
                .join()

        assertEquals(listOf("before", "callback", "after"), middleware.events)
        assertInstalledContextIsWorkflowsOwn(middleware, workflowInstance)
        assertTrue(result.isEmpty, "Task should complete successfully")
        assertCountedOnce(recordedMetrics, "callbackNotificationSuccess")
        assertEquals(
            listOf(Event.Type.COMPENSATION_STARTED, Event.Type.COMPENSATION_COMPLETED),
            publishedEventTypes()
        )
    }

    @Test
    fun testOnCompensationCompletedTearsDownRequestContextWhenCallbackThrows() {
        val middleware = RecordingRequestContextMiddleware()
        val recordedMetrics = CapturingMetrics()
        givenCompensationCompletes()
        doAnswer {
            middleware.events.add("callback")
            throw RuntimeException("Callback failed")
        }
            .whenever(mockCallbackHandler)
            .onCompensationCompleted(any())

        val result =
            handlerWith(middleware, recordedMetrics)
                .handle(createCompensationTask(WORKFLOW_ID), mockExecutorService)
                .join()

        assertEquals(listOf("before", "callback", "after"), middleware.events)
        assertEquals(Instant.EPOCH.plus(unexpectedErrorRetryDelay), result.get())
        assertCountedOnce(recordedMetrics, "callbackNotificationFailed")
        assertEquals(
            listOf(Event.Type.COMPENSATION_STARTED, Event.Type.COMPENSATION_COMPLETED),
            publishedEventTypes()
        )
    }

    @Test
    fun testOnCompensationCompletedContextInstallFailureRoutesLikeCallbackFailure() {
        val middleware = RecordingRequestContextMiddleware(beforeError = contextInstallError())
        val recordedMetrics = CapturingMetrics()
        givenCompensationCompletes()

        val result =
            handlerWith(middleware, recordedMetrics)
                .handle(createCompensationTask(WORKFLOW_ID), mockExecutorService)
                .join()

        assertEquals(listOf("before"), middleware.events)
        verify(mockCallbackHandler, never()).onCompensationCompleted(any())
        assertContextFailureCounted(recordedMetrics, "onCompensationCompleted")
        // Same observables as the callback-throws case above.
        assertEquals(Instant.EPOCH.plus(unexpectedErrorRetryDelay), result.get())
        assertCountedOnce(recordedMetrics, "callbackNotificationFailed")
        assertEquals(
            listOf(Event.Type.COMPENSATION_STARTED, Event.Type.COMPENSATION_COMPLETED),
            publishedEventTypes()
        )
    }

    @Test
    fun testRetriedCompleteNotificationRunsInWorkflowRequestContext() {
        val middleware = RecordingRequestContextMiddleware()
        val recordedMetrics = CapturingMetrics()
        val workflowInstance = givenCompensationAlreadyCompleted()
        doAnswer {
            middleware.events.add("callback")
            null
        }
            .whenever(mockCallbackHandler)
            .onCompensationCompleted(any())

        val result =
            handlerWith(middleware, recordedMetrics)
                .handle(createCompensationTask(WORKFLOW_ID), mockExecutorService)
                .join()

        assertEquals(listOf("before", "callback", "after"), middleware.events)
        assertInstalledContextIsWorkflowsOwn(middleware, workflowInstance)
        assertTrue(result.isEmpty, "Task should complete successfully after callback retry")
        assertCountedOnce(recordedMetrics, "callbackNotificationSuccess")
    }

    @Test
    fun testRetriedCompleteNotificationContextInstallFailureReschedules() {
        val middleware = RecordingRequestContextMiddleware(beforeError = contextInstallError())
        val recordedMetrics = CapturingMetrics()
        givenCompensationAlreadyCompleted()

        val result =
            handlerWith(middleware, recordedMetrics)
                .handle(createCompensationTask(WORKFLOW_ID), mockExecutorService)
                .join()

        assertEquals(listOf("before"), middleware.events)
        verify(mockCallbackHandler, never()).onCompensationCompleted(any())
        assertContextFailureCounted(recordedMetrics, "onCompensationCompleted")
        assertEquals(Instant.EPOCH.plus(unexpectedErrorRetryDelay), result.get())
        assertCountedOnce(recordedMetrics, "callbackNotificationRetryFailed")
    }

    @Test
    fun testOnCompensationErrorRunsInWorkflowRequestContextAndKeepsOutcome() {
        val middleware = RecordingRequestContextMiddleware()
        val recordedMetrics = CapturingMetrics()
        val workflowInstance = givenCompensationFails()
        doAnswer {
            middleware.events.add("callback")
            null
        }
            .whenever(mockCallbackHandler)
            .onCompensationError(any(), any())

        val result =
            handlerWith(middleware, recordedMetrics)
                .handle(createCompensationTask(WORKFLOW_ID), mockExecutorService)
                .join()

        assertEquals(listOf("before", "callback", "after"), middleware.events)
        assertInstalledContextIsWorkflowsOwn(middleware, workflowInstance)
        assertTrue(result.isEmpty, "Task should complete after compensation error")
        assertCountedOnce(recordedMetrics, "errorCallbackNotificationSuccess")
        assertEquals(
            listOf(Event.Type.COMPENSATION_STARTED, Event.Type.COMPENSATION_ERROR),
            publishedEventTypes()
        )
    }

    @Test
    fun testOnCompensationErrorTearsDownRequestContextWhenCallbackThrows() {
        val middleware = RecordingRequestContextMiddleware()
        val recordedMetrics = CapturingMetrics()
        givenCompensationFails()
        doAnswer {
            middleware.events.add("callback")
            throw RuntimeException("Error callback failed")
        }
            .whenever(mockCallbackHandler)
            .onCompensationError(any(), any())

        val result =
            handlerWith(middleware, recordedMetrics)
                .handle(createCompensationTask(WORKFLOW_ID), mockExecutorService)
                .join()

        assertEquals(listOf("before", "callback", "after"), middleware.events)
        assertTrue(result.isEmpty, "Task should still complete even if error callback fails")
        assertCountedOnce(recordedMetrics, "errorCallbackNotificationFailed")
        assertEquals(
            listOf(Event.Type.COMPENSATION_STARTED, Event.Type.COMPENSATION_ERROR),
            publishedEventTypes()
        )
    }

    @Test
    fun testOnCompensationErrorContextInstallFailureIsSwallowedLikeCallbackFailure() {
        val middleware = RecordingRequestContextMiddleware(beforeError = contextInstallError())
        val recordedMetrics = CapturingMetrics()
        givenCompensationFails()

        val result =
            handlerWith(middleware, recordedMetrics)
                .handle(createCompensationTask(WORKFLOW_ID), mockExecutorService)
                .join()

        assertEquals(listOf("before"), middleware.events)
        verify(mockCallbackHandler, never()).onCompensationError(any(), any())
        assertContextFailureCounted(recordedMetrics, "onCompensationError")
        // Same observables as the callback-throws case above: error callbacks are best-effort, so
        // the failure is counted and logged rather than rethrown.
        assertTrue(result.isEmpty, "Task should still complete even if the context cannot be set")
        assertCountedOnce(recordedMetrics, "errorCallbackNotificationFailed")
        assertEquals(
            listOf(Event.Type.COMPENSATION_STARTED, Event.Type.COMPENSATION_ERROR),
            publishedEventTypes()
        )
    }

    /** A [CompensationFlowTaskHandler] wired with [middleware] instead of the NOOP one. */
    private fun handlerWith(
        middleware: RawRequestContextMiddleware,
        handlerMetrics: Metrics
    ): CompensationFlowTaskHandler =
        CompensationFlowTaskHandler(
            mockWorkflowStore,
            mockCompensationExecutor,
            unexpectedErrorRetryDelay,
            mockClock,
            handlerMetrics,
            mockEventPublisher,
            mockInjector,
            middleware
        )

    /**
     * What [com.airbnb.skipper.common.AirbnbContextMiddleware] throws when it cannot establish the
     * context — a missing user id, or a failure minting an offline-user token.
     */
    private fun contextInstallError(): Throwable = NonRetryableError("userId is missing")

    /** Stubs the run so it reaches the completed-compensation callback, and returns the workflow. */
    private fun givenCompensationCompletes(): WorkflowInstance {
        val workflowInstance = createTestWorkflowInstanceWithCallback()
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))
        whenever(mockInjector.getInstance(TestCallbackHandler::class.java))
            .thenReturn(mockCallbackHandler)
        whenever(mockCompensationExecutor.executeCompensationFlow(any(), any(), any()))
            .thenReturn(
                CompletableFuture.completedFuture(CompensationExecutor.CompensationResult.success())
            )
        return workflowInstance
    }

    /** Stubs the run so it reaches the compensation-error callback, and returns the workflow. */
    private fun givenCompensationFails(): WorkflowInstance {
        val workflowInstance = createTestWorkflowInstanceWithCallback()
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))
        whenever(mockInjector.getInstance(TestCallbackHandler::class.java))
            .thenReturn(mockCallbackHandler)
        val errorResult =
            CompensationExecutor.CompensationResult.nonRetryableError(
                NonRetryableError("Compensation failed")
            )
        whenever(mockCompensationExecutor.executeCompensationFlow(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(errorResult))
        return workflowInstance
    }

    /**
     * Stubs a workflow already in COMPENSATION_COMPLETED, the state a previously failed callback
     * notification leaves behind, so `handle` re-attempts the notification instead of compensating.
     */
    private fun givenCompensationAlreadyCompleted(): WorkflowInstance {
        val workflowInstance =
            createTestWorkflowInstanceWithCallback(WorkflowInstance.Status.COMPENSATION_COMPLETED)
        whenever(mockWorkflowStore.getWorkflow(WORKFLOW_ID)).thenReturn(Option.of(workflowInstance))
        whenever(mockInjector.getInstance(TestCallbackHandler::class.java))
            .thenReturn(mockCallbackHandler)
        return workflowInstance
    }

    /** The lifecycle events the handler published, in order. */
    private fun publishedEventTypes(): kotlin.collections.List<Event.Type> {
        val captor = argumentCaptor<Event>()
        verify(mockEventPublisher, atLeastOnce()).publishEvent(captor.capture())
        return captor.allValues.map { it.type }
    }

    private fun assertInstalledContextIsWorkflowsOwn(
        middleware: RecordingRequestContextMiddleware,
        workflowInstance: WorkflowInstance
    ) {
        val expected =
            RawActionInvocation(
                workflowInstance.workflowId,
                workflowInstance.requestContext,
                workflowInstance.extraRequestData,
            )
        assertEquals(listOf(expected), middleware.beforeInvocations)
        assertEquals(listOf(expected), middleware.afterInvocations)
    }

    private fun assertCountedOnce(
        recordedMetrics: CapturingMetrics,
        counterName: String
    ) {
        assertEquals(
            1,
            recordedMetrics.incrementsOf(counterName).size,
            String.format("Expected exactly one %s increment", counterName)
        )
    }

    /**
     * A context-install failure is counted on its own counter, under this handler's own metrics
     * component, so an identity-infrastructure outage is distinguishable from a host callback bug.
     */
    private fun assertContextFailureCounted(
        recordedMetrics: CapturingMetrics,
        source: String
    ) {
        val contextErrors = recordedMetrics.incrementsOf("callbackContextErrors")
        assertEquals(1, contextErrors.size)
        assertEquals(
            listOf("compensationFlowTaskHandler", "callbackContextErrors"),
            contextErrors[0].names
        )
        assertEquals(
            mapOf("source" to source, "error" to "NonRetryableError"),
            contextErrors[0].tags
        )
    }

    // Test callback handler class
    open class TestCallbackHandler : WorkflowCallbackHandler {
        override fun onSuccess(workflowInstance: WorkflowInstanceView) {}

        override fun onNonRetryableError(
            workflowInstance: WorkflowInstanceView,
            error: Throwable
        ) {}

        override fun onWorkflowInWaitingStatus(workflowInstance: WorkflowInstanceView) {}

        override fun onWorkflowTimeout(workflowInstance: WorkflowInstanceView) {}
    }

    companion object {
        private const val WORKFLOW_ID = "test-workflow-id"
    }
}
