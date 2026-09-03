package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.Actions
import com.airbnb.skipper.Compensate
import com.airbnb.skipper.Event
import com.airbnb.skipper.EventPublisher
import com.airbnb.skipper.Execute
import com.airbnb.skipper.ExecutionTimeout
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.NoOpMetrics
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.CancelledWorkflow
import com.airbnb.skipper.OptimisticLockingError
import com.airbnb.skipper.RawActionInvocation
import com.airbnb.skipper.RawRequestContextMiddleware
import com.airbnb.skipper.RetriesExhaustedError
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.SkipperError
import com.airbnb.skipper.SkipperInjector
import com.airbnb.skipper.Timer
import com.airbnb.skipper.TransientError
import com.airbnb.skipper.ValidationError
import com.airbnb.skipper.WorkflowCallbackHandler
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.api.WorkflowInstanceView
import com.airbnb.skipper.internal.CheckpointTag
import com.airbnb.skipper.internal.ExecutionContext
import com.airbnb.skipper.internal.PersistentRetryableError
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.TestUtils
import com.airbnb.skipper.internal.WorkflowExecutor
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.internal.api.WaitSignal
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.internal.storage.WorkflowUpdateRequest
import com.airbnb.skipper.metrics.SkipperCounter
import io.vavr.Tuple3
import io.vavr.collection.HashMap
import io.vavr.collection.List
import io.vavr.collection.Map
import io.vavr.control.Either
import io.vavr.control.Option
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class WorkflowExecutionTaskHandlerTest {
    private lateinit var mockWorkflowExecutor: WorkflowExecutor
    private lateinit var mockClock: Clock
    private lateinit var mockWorkflowStore: WorkflowStore
    private lateinit var taskHandler: WorkflowExecutionTaskHandler
    private lateinit var executor: ExecutorService
    private lateinit var mockInjector: SkipperInjector
    private lateinit var mockScheduler: Scheduler
    private val unexpectedErrorRetryDelay = Duration.ofSeconds(10)
    private val metrics: Metrics = NoOpMetrics.INSTANCE
    private lateinit var mockEventPublisher: EventPublisher
    private lateinit var skipperEngine: SkipperEngine

    @BeforeEach
    fun setUp() {
        mockInjector = mock()
        executor = mock()
        mockWorkflowExecutor = mock()
        mockClock = mock()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        mockWorkflowStore = mock()
        whenever(mockWorkflowStore.getActionCheckpoints(any())).thenReturn(List.empty())
        mockScheduler = mock()
        mockEventPublisher = mock()
        skipperEngine = mock()
        taskHandler =
            WorkflowExecutionTaskHandler(
                mockWorkflowExecutor,
                mockClock,
                mockWorkflowStore,
                unexpectedErrorRetryDelay,
                mockInjector,
                metrics,
                mockScheduler,
                mockEventPublisher,
                skipperEngine,
                RawRequestContextMiddleware.NOOP
            )
    }

    @Test
    fun testHandle() {
        val newState: Map<String, Any?> = HashMap.of("name", "Ricardo")
        val result =
            WorkflowExecutor.ExecutionResult.builder()
                .result(Either.right("hello world"))
                .newState(newState)
                .newStatus(WorkflowInstance.Status.COMPLETED)
                .build()
        whenever(mockWorkflowExecutor.executeWorkflowMethod(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(result))
        val callbackHandler = mock<WorkflowCallbackHandler>()
        whenever(mockInjector.getInstance(WorkflowCallbackHandler::class.java))
            .thenReturn(callbackHandler)
        val workflowInstance =
            TestUtils.getWorkflowInstance().toBuilder()
                .callbackHandler(WorkflowCallbackHandler::class.java)
                .build()
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        whenever(mockWorkflowStore.updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any()))
            .thenReturn(Tuple3(workflowInstance, List.empty(), List.empty()))
        whenever(mockWorkflowStore.getTimers(eq(workflowInstance.workflowId)))
            .thenReturn(List.empty())
        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        val handleResult = taskHandler.handle(task, executor).join()
        assertTrue(handleResult.isEmpty)
        assertTrue((task.payload as WorkflowInstance).result.isDone)
        val updateRequest =
            WorkflowUpdateRequest.builder()
                .workflowInstance(workflowInstance)
                .newStatus(WorkflowInstance.Status.COMPLETED)
                .newState(newState)
                .result(Either.right("hello world"))
                .resultIsAsync(false)
                .build()

        verify(mockWorkflowStore, times(1))
            .updateWorkflowAndStoreCheckpointsAndTimers(eq(updateRequest), any(), any())
        verify(mockWorkflowStore, times(2)).getWorkflow(eq(workflowInstance.workflowId))
        val captor = argumentCaptor<WorkflowInstanceView>()
        verify(callbackHandler, times(1)).onSuccess(captor.capture())
        assertEquals(workflowInstance.workflowId, captor.firstValue.id)
        verify(mockScheduler, times(0)).schedule<Any>(any())
    }

    @Test
    fun testHandleWhenReloadReturnsUpdatedWorkflowInstance() {
        val newState: Map<String, Any?> = HashMap.of("name", "Ricardo")
        val result =
            WorkflowExecutor.ExecutionResult.builder()
                .result(Either.right("hello world"))
                .newState(newState)
                .newStatus(WorkflowInstance.Status.COMPLETED)
                .build()
        whenever(mockWorkflowExecutor.executeWorkflowMethod(any(), any(), any())).thenAnswer { i ->
            val ec = i.getArgument(2, ExecutionContext::class.java)
            // Force the workflow instance to be reloaded
            ec.shouldReloadWorkflowInstance.set(true)
            CompletableFuture.completedFuture(result)
        }
        val callbackHandler = mock<WorkflowCallbackHandler>()
        whenever(mockInjector.getInstance(WorkflowCallbackHandler::class.java))
            .thenReturn(callbackHandler)
        val workflowInstance =
            TestUtils.getWorkflowInstance().toBuilder()
                .callbackHandler(WorkflowCallbackHandler::class.java)
                .build()
        val updatedWorkflowInstance =
            workflowInstance.toBuilder()
                .state(HashMap.of("test", 1))
                .version(workflowInstance.version + 1)
                .build()
        val getWorkflowInvocations = AtomicInteger(0)
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId))).thenAnswer {
            if (getWorkflowInvocations.incrementAndGet() == 1) {
                Option.of(workflowInstance)
            } else {
                Option.of(updatedWorkflowInstance)
            }
        }
        whenever(mockWorkflowStore.updateWorkflow(any())).thenReturn(updatedWorkflowInstance)
        whenever(mockWorkflowStore.updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any()))
            .thenReturn(Tuple3(workflowInstance, List.empty(), List.empty()))
        whenever(mockWorkflowStore.getTimers(eq(workflowInstance.workflowId)))
            .thenReturn(List.empty())
        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        val handleResult = taskHandler.handle(task, executor).join()
        assertTrue(handleResult.isDefined)
        assertTrue((task.payload as WorkflowInstance).result.isCompletedExceptionally)
        val updateRequest =
            WorkflowUpdateRequest.builder()
                .workflowInstance(updatedWorkflowInstance)
                .newStatus(WorkflowInstance.Status.COMPLETED)
                .newState(newState)
                .result(Either.right("hello world"))
                .resultIsAsync(false)
                .build()

        verify(mockWorkflowStore, times(0))
            .updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any())
        verify(mockWorkflowStore, times(3)).getWorkflow(eq(workflowInstance.workflowId))
    }

    @Test
    fun testHandleWhenNonRetryableError() {
        val newState: Map<String, Any?> = HashMap.of("name", "Ricardo")
        val expectedError = NonRetryableError("non retryable error")
        val result =
            WorkflowExecutor.ExecutionResult.builder()
                .result(Either.left(expectedError))
                .newState(newState)
                .newStatus(WorkflowInstance.Status.ERROR)
                .build()
        whenever(mockWorkflowExecutor.executeWorkflowMethod(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(result))
        val callbackHandler = mock<WorkflowCallbackHandler>()
        whenever(mockInjector.getInstance(WorkflowCallbackHandler::class.java))
            .thenReturn(callbackHandler)
        val workflowInstance =
            TestUtils.getWorkflowInstance().toBuilder()
                .callbackHandler(WorkflowCallbackHandler::class.java)
                .build()
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        whenever(mockWorkflowStore.getTimers(eq(workflowInstance.workflowId)))
            .thenReturn(List.empty())
        whenever(mockWorkflowStore.updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any()))
            .thenReturn(Tuple3(workflowInstance, List.empty(), List.empty()))
        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        val handleResult = taskHandler.handle(task, executor).join()
        assertTrue(handleResult.isEmpty)
        assertTrue((task.payload as WorkflowInstance).result.isDone)
        val updateRequest =
            WorkflowUpdateRequest.builder()
                .workflowInstance(workflowInstance)
                .newStatus(WorkflowInstance.Status.ERROR)
                .newState(newState)
                .result(Either.left(expectedError))
                .resultIsAsync(false)
                .build()
        verify(mockWorkflowStore, times(1))
            .updateWorkflowAndStoreCheckpointsAndTimers(eq(updateRequest), any(), any())
        val captor = argumentCaptor<WorkflowInstanceView>()
        verify(callbackHandler, times(1)).onNonRetryableError(captor.capture(), eq(expectedError))
        assertEquals(workflowInstance.workflowId, captor.firstValue.id)
    }

    @Test
    fun testHandleSchedulesCompensationWhenWorkflowTransitionsToErrorWithCompensationFlow() {
        val newState: Map<String, Any?> = HashMap.of("name", "Ricardo")
        val expectedError = NonRetryableError("non retryable error")
        val result =
            WorkflowExecutor.ExecutionResult.builder()
                .result(Either.left(expectedError))
                .newState(newState)
                .newStatus(WorkflowInstance.Status.ERROR)
                .build()
        whenever(mockWorkflowExecutor.executeWorkflowMethod(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(result))

        val workflowInstance = TestUtils.getWorkflowInstance()
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        whenever(mockWorkflowStore.getTimers(eq(workflowInstance.workflowId)))
            .thenReturn(List.empty())

        // Mock compensable action checkpoints to trigger hasCompensationFlow() = true
        // Create a simple Actions class for testing compensation flow detection
        class TestActions : Actions() {
            @Execute
            fun testAction(input: String): String = "result"

            @Compensate(forExecute = "testAction")
            fun compensateTestAction(input: String) {
                // compensation logic
            }
        }

        val compensableCheckpoint =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId(workflowInstance.workflowId)
                        .actionClass(TestActions::class.java)
                        .actionMethod("testAction")
                        .iteration(0)
                        .build()
                )
                .executionStartTime(Instant.EPOCH)
                .executionEndTime(Instant.EPOCH)
                .result(Either.right("success"))
                .isTransient(false)
                .resultIsAsync(false)
                .build()

        whenever(mockWorkflowStore.getActionCheckpoints(eq(workflowInstance.workflowId)))
            .thenReturn(List.of(compensableCheckpoint))

        whenever(mockWorkflowStore.updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any()))
            .thenReturn(Tuple3(workflowInstance, List.empty(), List.empty()))

        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        val handleResult = taskHandler.handle(task, executor).join()

        assertTrue(handleResult.isEmpty)
        // Verify compensation manager is called when workflow transitions to ERROR and has
        // compensation flow
        verify(skipperEngine, times(1)).scheduleCompensationTask(eq(workflowInstance))
    }

    @Test
    fun testHandleDoesNotScheduleCompensationWhenWorkflowTransitionsToNonErrorStatus() {
        val newState: Map<String, Any?> = HashMap.of("name", "Ricardo")
        val result =
            WorkflowExecutor.ExecutionResult.builder()
                .result(Either.right("success"))
                .newState(newState)
                .newStatus(WorkflowInstance.Status.COMPLETED)
                .build()
        whenever(mockWorkflowExecutor.executeWorkflowMethod(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(result))

        val workflowInstance = TestUtils.getWorkflowInstance()
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        whenever(mockWorkflowStore.getTimers(eq(workflowInstance.workflowId)))
            .thenReturn(List.empty())

        // Mock compensable action checkpoints - even with compensation flow, no scheduling on
        // success
        // Create a simple Actions class for testing compensation flow detection
        class TestActions : Actions() {
            @Execute
            fun testAction(input: String): String = "result"

            @Compensate(forExecute = "testAction")
            fun compensateTestAction(input: String) {
                // compensation logic
            }
        }

        val compensableCheckpoint =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId(workflowInstance.workflowId)
                        .actionClass(TestActions::class.java)
                        .actionMethod("testAction")
                        .iteration(0)
                        .build()
                )
                .executionStartTime(Instant.EPOCH)
                .executionEndTime(Instant.EPOCH)
                .result(Either.right("success"))
                .isTransient(false)
                .resultIsAsync(false)
                .build()

        whenever(mockWorkflowStore.getActionCheckpoints(eq(workflowInstance.workflowId)))
            .thenReturn(List.of(compensableCheckpoint))

        whenever(mockWorkflowStore.updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any()))
            .thenReturn(Tuple3(workflowInstance, List.empty(), List.empty()))

        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        val handleResult = taskHandler.handle(task, executor).join()

        assertTrue(handleResult.isEmpty)
        // Verify compensation manager is NOT called for successful workflows, even with compensation
        // flow
        verify(skipperEngine, times(0)).scheduleCompensationTask(any())
    }

    @Test
    fun testHandleWhenCallbackHandlerFails() {
        val newState: Map<String, Any?> = HashMap.of("name", "Ricardo")
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val result =
            WorkflowExecutor.ExecutionResult.builder()
                .result(Either.right("hello world"))
                .newState(newState)
                .newStatus(WorkflowInstance.Status.COMPLETED)
                .build()

        whenever(mockWorkflowExecutor.executeWorkflowMethod(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(result))
        val callbackHandler = mock<WorkflowCallbackHandler>()
        val handlerError = RuntimeException("handler error!")
        doThrow(handlerError).whenever(callbackHandler).onSuccess(any())
        whenever(mockInjector.getInstance(WorkflowCallbackHandler::class.java))
            .thenReturn(callbackHandler)
        val workflowInstance =
            TestUtils.getWorkflowInstance().toBuilder()
                .callbackHandler(WorkflowCallbackHandler::class.java)
                .build()
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        whenever(mockWorkflowStore.updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any()))
            .thenReturn(Tuple3(workflowInstance, List.empty(), List.empty()))
        whenever(mockWorkflowStore.getTimers(eq(workflowInstance.workflowId)))
            .thenReturn(List.empty())
        val handleResult = taskHandler.handle(task, executor).join()
        assertFalse(handleResult.isEmpty)
        assertTrue((task.payload as WorkflowInstance).result.isDone)
        val updateRequest =
            WorkflowUpdateRequest.builder()
                .workflowInstance(workflowInstance)
                .newStatus(WorkflowInstance.Status.COMPLETED)
                .newState(newState)
                .result(Either.right("hello world"))
                .resultIsAsync(false)
                .build()
        verify(mockWorkflowStore, times(1))
            .updateWorkflowAndStoreCheckpointsAndTimers(eq(updateRequest), any(), any())
        verify(mockScheduler, times(0)).schedule<Any>(any())
        val captor = argumentCaptor<WorkflowInstanceView>()
        verify(callbackHandler, times(1)).onSuccess(captor.capture())
        assertEquals(workflowInstance.workflowId, captor.firstValue.id)
        // The task should've been scheduled for retry
        assertEquals(Instant.EPOCH.plus(unexpectedErrorRetryDelay), handleResult.get())
    }

    @Test
    fun testHandleWhenWaitSignal() {
        val result =
            WorkflowExecutor.ExecutionResult.builder()
                .newStatus(WorkflowInstance.Status.WAITING)
                .waitDuration(Duration.ofMinutes(1))
                .build()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        whenever(mockWorkflowExecutor.executeWorkflowMethod(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(result))

        val callbackHandler = mock<WorkflowCallbackHandler>()
        whenever(mockInjector.getInstance(WorkflowCallbackHandler::class.java))
            .thenReturn(callbackHandler)
        val workflowInstance =
            TestUtils.getWorkflowInstance().toBuilder()
                .callbackHandler(WorkflowCallbackHandler::class.java)
                .build()
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        val timer =
            Timer(
                workflowInstance.workflowId,
                "1",
                null,
                Duration.ofMinutes(1),
                Instant.EPOCH.plus(Duration.ofMinutes(1)),
                Timer.Status.ACTIVE,
                0
            )
        val timer2 =
            Timer(
                workflowInstance.workflowId,
                "2",
                null,
                Duration.ofMinutes(1),
                Instant.EPOCH.plus(Duration.ofMinutes(1)),
                Timer.Status.ACTIVE,
                0
            )
        whenever(mockWorkflowStore.updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any()))
            .thenReturn(Tuple3(workflowInstance, List.empty(), List.of(timer, timer2)))
        whenever(mockWorkflowStore.getTimers(eq(workflowInstance.workflowId)))
            .thenReturn(List.empty())
        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        val retryDelay = taskHandler.handle(task, executor).join()
        // When a workflow execution results in a wait signal, there should not be retry delay
        // and a new task should be scheduled to execute the timer.
        assertTrue(retryDelay.isEmpty)
        assertTrue((task.payload as WorkflowInstance).result.isDone)
        val error =
            assertThrows(ExecutionException::class.java) {
                (task.payload as WorkflowInstance).result.get()
            }
        assertTrue(error.cause is WaitSignal)
        val updateRequest =
            WorkflowUpdateRequest.builder()
                .workflowInstance(workflowInstance)
                .newStatus(WorkflowInstance.Status.WAITING)
                .newState(null)
                // Even though upon a wait signal, the result's CompletableFuture will be used to
                // signal
                // the event to the caller who might be waiting on the result. This will NOT be
                // persisted
                // to the db as an actual result.
                .result(null)
                .resultIsAsync(false)
                .build()
        verify(mockWorkflowStore, times(1))
            .updateWorkflowAndStoreCheckpointsAndTimers(eq(updateRequest), any(), any())
        val captor = argumentCaptor<WorkflowInstanceView>()
        verify(callbackHandler, times(1)).onWorkflowInWaitingStatus(captor.capture())
        assertEquals(workflowInstance.workflowId, captor.firstValue.id)
        val scheduleRequestCaptor = argumentCaptor<ScheduleRequest<Timer>>()
        verify(mockScheduler, times(2)).schedule(scheduleRequestCaptor.capture())
        val requests = scheduleRequestCaptor.allValues
        assertEquals(Task.Type.TIMER, requests[0].type)
        assertEquals(timer, requests[0].payload)
        assertEquals(Task.Type.TIMER, requests[1].type)
        assertEquals(timer2, requests[1].payload)
    }

    @Test
    fun testHandleWhenExecutionReturnsRetriesExhaustedError() {
        val newState: Map<String, Any?> = HashMap.of("name", "Ricardo")
        val expectedError = PersistentRetryableError(RetryableError("retryable error"))
        val result =
            WorkflowExecutor.ExecutionResult.builder()
                .result(Either.left(expectedError))
                .newState(newState)
                .newStatus(WorkflowInstance.Status.RETRIES_EXHAUSTED)
                .build()
        whenever(mockWorkflowExecutor.executeWorkflowMethod(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(result))
        val callbackHandler = mock<WorkflowCallbackHandler>()
        whenever(mockInjector.getInstance(WorkflowCallbackHandler::class.java))
            .thenReturn(callbackHandler)
        val workflowInstance =
            TestUtils.getWorkflowInstance().toBuilder()
                .callbackHandler(WorkflowCallbackHandler::class.java)
                .build()
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        whenever(mockWorkflowStore.getTimers(eq(workflowInstance.workflowId)))
            .thenReturn(List.empty())
        whenever(mockWorkflowStore.updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any()))
            .thenReturn(Tuple3(workflowInstance, List.empty(), List.empty()))
        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        val handleResult = taskHandler.handle(task, executor).join()
        assertTrue(handleResult.isEmpty)
        assertTrue((task.payload as WorkflowInstance).result.isDone)
        assertTrue((task.payload as WorkflowInstance).result.isCompletedExceptionally)
        val error =
            assertThrows(CompletionException::class.java) {
                (task.payload as WorkflowInstance).result.join()
            }
        assertTrue(error.cause is RetriesExhaustedError)
        assertTrue(error.cause!!.cause is PersistentRetryableError)
        assertTrue(error.cause!!.message!!.contains("retryable error"))
        val updateRequest =
            WorkflowUpdateRequest.builder()
                .workflowInstance(workflowInstance)
                .newStatus(WorkflowInstance.Status.RETRIES_EXHAUSTED)
                .newState(newState)
                .resultIsAsync(false)
                .build()
        verify(mockWorkflowStore, times(1))
            .updateWorkflowAndStoreCheckpointsAndTimers(eq(updateRequest), any(), any())
        val captor = argumentCaptor<WorkflowInstanceView>()
        verify(callbackHandler, times(1)).onRetriesExhausted(captor.capture(), eq(expectedError))
        assertEquals(workflowInstance.workflowId, captor.firstValue.id)
    }

    @Test
    fun testWhenExecutionThrowsExceptionIsPropagated() {
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val workflowInstance = TestUtils.getWorkflowInstance()
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        whenever(mockWorkflowStore.getTimers(eq(workflowInstance.workflowId)))
            .thenReturn(List.empty())
        whenever(mockWorkflowExecutor.executeWorkflowMethod(any(), any(), any()))
            .thenReturn(
                CompletableFuture.supplyAsync {
                    throw ValidationError("test")
                }
            )
        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        taskHandler.handle(task, executor).join()
        assertTrue(workflowInstance.result.isDone)
        assertTrue(workflowInstance.result.isCompletedExceptionally)
        val error = assertThrows(CompletionException::class.java) { workflowInstance.result.join() }
        assertTrue(error.cause is TransientError)
    }

    @Test
    fun testOptimisticLockShouldResultInRetry() {
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val newState: Map<String, Any?> = HashMap.of("name", "Ricardo")
        val result =
            WorkflowExecutor.ExecutionResult.builder()
                .result(Either.right("hello world"))
                .newState(newState)
                .newStatus(WorkflowInstance.Status.COMPLETED)
                .build()
        whenever(mockWorkflowExecutor.executeWorkflowMethod(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(result))
        val workflowInstance = TestUtils.getWorkflowInstance()
        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        whenever(mockWorkflowStore.updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any()))
            .thenThrow(OptimisticLockingError("test"))
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        whenever(mockWorkflowStore.getTimers(eq(workflowInstance.workflowId)))
            .thenReturn(List.empty())
        val handleResult = taskHandler.handle(task, executor).join()
        assertTrue(handleResult.isDefined)
        assertEquals(mockClock.instant().plus(unexpectedErrorRetryDelay), handleResult.get())
        assertTrue((task.payload as WorkflowInstance).result.isDone)
        assertTrue((task.payload as WorkflowInstance).result.isCompletedExceptionally)
    }

    /**
     * [Metrics] that records the tagged counters it is asked for, so a test can assert which branch
     * of the error handling actually ran. Everything else keeps the no-op behavior.
     */
    private class RecordingMetrics : NoOpMetrics() {
        val counters = mutableListOf<String>()

        override fun counter(
            tags: kotlin.collections.Map<String, String>,
            vararg names: String
        ): SkipperCounter {
            counters.add(names.joinToString("."))
            return super.counter(tags, *names)
        }
    }

    private fun handlerWith(recordingMetrics: Metrics): WorkflowExecutionTaskHandler =
        WorkflowExecutionTaskHandler(
            mockWorkflowExecutor,
            mockClock,
            mockWorkflowStore,
            unexpectedErrorRetryDelay,
            mockInjector,
            recordingMetrics,
            mockScheduler,
            mockEventPublisher,
            skipperEngine,
            RawRequestContextMiddleware.NOOP
        )

    /**
     * Arrange a workflow whose execution succeeds but whose persistence throws [error], run it, and
     * return the counters the handler emitted.
     */
    private fun countersForPersistenceFailure(error: Throwable): kotlin.collections.List<String> {
        val recordingMetrics = RecordingMetrics()
        val result =
            WorkflowExecutor.ExecutionResult.builder()
                .result(Either.right("hello world"))
                .newStatus(WorkflowInstance.Status.COMPLETED)
                .build()
        whenever(mockWorkflowExecutor.executeWorkflowMethod(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(result))
        val workflowInstance = TestUtils.getWorkflowInstance()
        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        whenever(mockWorkflowStore.updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any()))
            .thenThrow(error)
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        whenever(mockWorkflowStore.getTimers(eq(workflowInstance.workflowId)))
            .thenReturn(List.empty())
        val handleResult = handlerWith(recordingMetrics).handle(task, executor).join()
        // Either way the task is retried after the configured delay - only the reporting differs.
        assertTrue(handleResult.isDefined)
        assertEquals(mockClock.instant().plus(unexpectedErrorRetryDelay), handleResult.get())
        return recordingMetrics.counters
    }

    @Test
    fun testOptimisticLockIsReportedAsContentionNotAsUnexpectedError() {
        val counters = countersForPersistenceFailure(OptimisticLockingError("stale version"))
        // Losing the version race is expected contention: it is counted as such and deliberately
        // NOT as an unexpected error, which is what used to pin an ERROR log line per lost race.
        assertTrue(
            counters.any { it.endsWith("optimisticLockingError") },
            "expected an optimisticLockingError counter, got $counters",
        )
        assertFalse(
            counters.any { it.endsWith("handleExecutionUnexpectedError") },
            "contention must not be counted as an unexpected error, got $counters",
        )
    }

    @Test
    fun testUnexpectedPersistenceErrorIsStillReportedAsUnexpectedError() {
        // Guard against the contention branch being too broad: a genuine fault must keep its
        // unexpected-error reporting (and its ERROR log).
        val counters = countersForPersistenceFailure(IllegalStateException("boom"))
        assertTrue(
            counters.any { it.endsWith("handleExecutionUnexpectedError") },
            "expected a handleExecutionUnexpectedError counter, got $counters",
        )
        assertFalse(
            counters.any { it.endsWith("optimisticLockingError") },
            "a genuine fault must not be counted as contention, got $counters",
        )
    }

    @Test
    fun testOptimisticLockWhenMaxRetriesShouldResultInError() {
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val newState: Map<String, Any?> = HashMap.of("name", "Ricardo")
        val result =
            WorkflowExecutor.ExecutionResult.builder()
                .result(Either.right("hello world"))
                .newState(newState)
                .newStatus(WorkflowInstance.Status.COMPLETED)
                .build()
        whenever(mockWorkflowExecutor.executeWorkflowMethod(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(result))
        val workflowInstance = TestUtils.getWorkflowInstance()
        val task: Task<*> = TestUtils.getTestTask(workflowInstance).toBuilder().retryCount(100).build()
        whenever(mockWorkflowStore.updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any()))
            .thenThrow(OptimisticLockingError("test"))
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        whenever(mockWorkflowStore.getTimers(eq(workflowInstance.workflowId)))
            .thenReturn(List.empty())
        taskHandler.handle(task, executor).join()
        assertTrue((task.payload as WorkflowInstance).result.isDone)
        assertTrue((task.payload as WorkflowInstance).result.isCompletedExceptionally)
        val error =
            assertThrows(CompletionException::class.java) {
                (task.payload as WorkflowInstance).result.join()
            }
        assertTrue(error.cause is TransientError)
    }

    @Test
    fun testWhenExecutionResultsInError() {
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val future = CompletableFuture<WorkflowExecutor.ExecutionResult>()
        future.completeExceptionally(IllegalArgumentException("unexpected error"))
        whenever(mockWorkflowExecutor.executeWorkflowMethod(any(), any(), any())).thenReturn(future)
        val workflowInstance = TestUtils.getWorkflowInstance()
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        whenever(mockWorkflowStore.getTimers(eq(workflowInstance.workflowId)))
            .thenReturn(List.empty())
        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        taskHandler.handle(task, executor).join()
        assertTrue(workflowInstance.result.isDone)
        assertTrue(workflowInstance.result.isCompletedExceptionally)
        val error = assertThrows(CompletionException::class.java) { workflowInstance.result.join() }
        assertTrue(error.cause is TransientError)
    }

    @Test
    fun testHandleWhenWorkflowIsTimeoutEligibleHappyPath() {
        val workflowInstance =
            TestUtils.getWorkflowInstance().toBuilder()
                .callbackHandler(WorkflowCallbackHandler::class.java)
                .timeoutTime(Instant.EPOCH)
                .build()
        val callbackHandler = mock<WorkflowCallbackHandler>()
        whenever(mockInjector.getInstance(WorkflowCallbackHandler::class.java))
            .thenReturn(callbackHandler)
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH.plusSeconds(1))
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        whenever(mockWorkflowStore.updateWorkflow(any())).thenReturn(workflowInstance)
        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        val handleResult = taskHandler.handle(task, executor).join()
        assertTrue(handleResult.isEmpty)
        val updateRequestCaptor = argumentCaptor<WorkflowUpdateRequest>()
        verify(mockWorkflowStore, times(1)).updateWorkflow(updateRequestCaptor.capture())
        val updateRequest = updateRequestCaptor.firstValue
        assertEquals(WorkflowInstance.Status.TIMEOUT, updateRequest.newStatus)
        val captor = argumentCaptor<WorkflowInstanceView>()
        verify(callbackHandler, times(1)).onWorkflowTimeout(captor.capture())
        assertEquals(workflowInstance.workflowId, captor.firstValue.id)
    }

    @Test
    fun testHandleWhenWorkflowIsTimeoutEligibleWhenUpdateFails() {
        val workflowInstance =
            TestUtils.getWorkflowInstance().toBuilder()
                .callbackHandler(WorkflowCallbackHandler::class.java)
                .timeoutTime(Instant.EPOCH)
                .build()
        val callbackHandler = mock<WorkflowCallbackHandler>()
        whenever(mockInjector.getInstance(WorkflowCallbackHandler::class.java))
            .thenReturn(callbackHandler)
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH.plusSeconds(1))
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        whenever(mockWorkflowStore.updateWorkflow(any())).thenThrow(RuntimeException("error"))
        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        val handleResult = taskHandler.handle(task, executor).join()
        assertTrue(handleResult.isDefined)
        val updateRequestCaptor = argumentCaptor<WorkflowUpdateRequest>()
        verify(mockWorkflowStore, times(1)).updateWorkflow(updateRequestCaptor.capture())
        val updateRequest = updateRequestCaptor.firstValue
        assertEquals(WorkflowInstance.Status.TIMEOUT, updateRequest.newStatus)
        val captor = argumentCaptor<WorkflowInstanceView>()
        verify(callbackHandler, times(1)).onWorkflowTimeout(captor.capture())
        assertEquals(workflowInstance.workflowId, captor.firstValue.id)
    }

    @Test
    fun testHandleWhenWorkflowIsTimeoutEligibleWhenTimeoutHandlerFails() {
        val workflowInstance =
            TestUtils.getWorkflowInstance().toBuilder()
                .callbackHandler(WorkflowCallbackHandler::class.java)
                .timeoutTime(Instant.EPOCH)
                .build()
        val callbackHandler = mock<WorkflowCallbackHandler>()
        whenever(mockInjector.getInstance(WorkflowCallbackHandler::class.java))
            .thenReturn(callbackHandler)
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH.plusSeconds(1))
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        doThrow(RuntimeException("error")).whenever(callbackHandler).onWorkflowTimeout(any())
        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        val handleResult = taskHandler.handle(task, executor).join()
        assertTrue(handleResult.isDefined)
        verify(mockWorkflowStore, times(0)).updateWorkflow(any())
        val captor = argumentCaptor<WorkflowInstanceView>()
        verify(callbackHandler, times(1)).onWorkflowTimeout(captor.capture())
        assertEquals(workflowInstance.workflowId, captor.firstValue.id)
    }

    @Test
    fun testHandleWhenWorkflowIsAlreadyTimeout() {
        val workflowInstance =
            TestUtils.getWorkflowInstance().toBuilder()
                .callbackHandler(WorkflowCallbackHandler::class.java)
                .timeoutTime(Instant.EPOCH)
                .status(WorkflowInstance.Status.TIMEOUT)
                .build()
        val callbackHandler = mock<WorkflowCallbackHandler>()
        whenever(mockInjector.getInstance(WorkflowCallbackHandler::class.java))
            .thenReturn(callbackHandler)
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH.minusSeconds(1))
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        whenever(mockWorkflowStore.getTimers(eq(workflowInstance.workflowId)))
            .thenReturn(List.empty())
        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        val handleResult = taskHandler.handle(task, executor).join()
        assertTrue(handleResult.isEmpty)
        verify(mockWorkflowStore, times(0)).updateWorkflow(any())
        verify(callbackHandler, times(0)).onWorkflowTimeout(any())
    }

    @Test
    fun testHandleWhenExecutionResultsInTransientErrorCallsOnRetryableError() {
        val newState: Map<String, Any?> = HashMap.of("name", "Ricardo")
        val originalError = RetryableError("original retryable error")
        val result =
            WorkflowExecutor.ExecutionResult.builder()
                .result(Either.left(originalError))
                .newState(newState)
                .newStatus(WorkflowInstance.Status.TRANSIENT_ERROR)
                .retryDelay(Duration.ofSeconds(5))
                .build()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        whenever(mockWorkflowExecutor.executeWorkflowMethod(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(result))
        val callbackHandler = mock<WorkflowCallbackHandler>()
        whenever(mockInjector.getInstance(WorkflowCallbackHandler::class.java))
            .thenReturn(callbackHandler)
        val workflowInstance =
            TestUtils.getWorkflowInstance().toBuilder()
                .callbackHandler(WorkflowCallbackHandler::class.java)
                .build()
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        whenever(mockWorkflowStore.getTimers(eq(workflowInstance.workflowId)))
            .thenReturn(List.empty())
        whenever(mockWorkflowStore.updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any()))
            .thenReturn(Tuple3(workflowInstance, List.empty(), List.empty()))
        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        val handleResult = taskHandler.handle(task, executor).join()
        assertTrue(handleResult.isDefined)
        assertEquals(Instant.EPOCH.plus(Duration.ofSeconds(5)), handleResult.get())
        assertTrue(workflowInstance.result.isDone)
        assertTrue(workflowInstance.result.isCompletedExceptionally)
        val error = assertThrows(CompletionException::class.java) { workflowInstance.result.join() }
        assertTrue(error.cause is TransientError)

        // Verify that onRetryableError was called with the original exception
        val captor = argumentCaptor<WorkflowInstanceView>()
        verify(callbackHandler, times(1)).onRetryableError(captor.capture(), eq(originalError))
        assertEquals(workflowInstance.workflowId, captor.firstValue.id)
    }

    @Test
    fun testHandleWhenExecutionResultsInTransientErrorWithoutCallbackHandler() {
        val newState: Map<String, Any?> = HashMap.of("name", "Ricardo")
        val originalError = RetryableError("original retryable error")
        val result =
            WorkflowExecutor.ExecutionResult.builder()
                .result(Either.left(originalError))
                .newState(newState)
                .newStatus(WorkflowInstance.Status.TRANSIENT_ERROR)
                .retryDelay(Duration.ofSeconds(5))
                .build()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        whenever(mockWorkflowExecutor.executeWorkflowMethod(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(result))
        val workflowInstance = TestUtils.getWorkflowInstance() // No callback handler set
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        whenever(mockWorkflowStore.getTimers(eq(workflowInstance.workflowId)))
            .thenReturn(List.empty())
        whenever(mockWorkflowStore.updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any()))
            .thenReturn(Tuple3(workflowInstance, List.empty(), List.empty()))
        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        val handleResult = taskHandler.handle(task, executor).join()
        assertTrue(handleResult.isDefined)
        assertEquals(Instant.EPOCH.plus(Duration.ofSeconds(5)), handleResult.get())
        assertTrue(workflowInstance.result.isDone)
        assertTrue(workflowInstance.result.isCompletedExceptionally)
        val error = assertThrows(CompletionException::class.java) { workflowInstance.result.join() }
        assertTrue(error.cause is TransientError)
        // Should not attempt to get callback handler when none is configured
        verify(mockInjector, times(0)).getInstance(WorkflowCallbackHandler::class.java)
    }

    // -----------------------------------------------------------------------------------------
    // Callback request context. Every callback this handler fires runs with the workflow's own
    // request context installed and torn down again, and the engine's reaction to the callback's
    // outcome — status transition, retry scheduling, published events, result future — is
    // unchanged. Three cases per callback: the middleware succeeds, the callback throws, and the
    // middleware fails to install the context.
    //
    // The suite above wires RawRequestContextMiddleware.NOOP, which is a pure pass-through, so it
    // is evidence for the no-middleware configuration only. These tests install a middleware that
    // really runs.
    // -----------------------------------------------------------------------------------------

    @Test
    fun testOnSuccessRunsInWorkflowRequestContextAndKeepsOutcome() {
        val middleware = RecordingRequestContextMiddleware()
        val callbackHandler = mock<WorkflowCallbackHandler>()
        doAnswer {
            middleware.events.add("callback")
            null
        }
            .whenever(callbackHandler)
            .onSuccess(any())
        val task = givenWorkflowResult(completedResult(), callbackHandler)

        val retryDelay = handlerWith(middleware).handle(task, executor).join()

        assertEquals(listOf("before", "callback", "after"), middleware.events)
        assertInstalledContextIsWorkflowsOwn(middleware, task)
        assertEquals(WorkflowInstance.Status.COMPLETED, persistedStatus())
        assertTrue(retryDelay.isEmpty)
        assertEquals(
            listOf(Event.Type.WORKFLOW_EXECUTION_STARTED, Event.Type.WORKFLOW_COMPLETED),
            publishedEventTypes()
        )
        assertFalse(resultOf(task).isCompletedExceptionally)
        assertEquals("hello world", resultOf(task).join())
    }

    @Test
    fun testOnSuccessTearsDownRequestContextWhenCallbackThrows() {
        val middleware = RecordingRequestContextMiddleware()
        val callbackHandler = mock<WorkflowCallbackHandler>()
        doAnswer {
            middleware.events.add("callback")
            throw RuntimeException("handler error!")
        }
            .whenever(callbackHandler)
            .onSuccess(any())
        val task = givenWorkflowResult(completedResult(), callbackHandler)

        val retryDelay = handlerWith(middleware).handle(task, executor).join()

        assertEquals(listOf("before", "callback", "after"), middleware.events)
        assertEquals(WorkflowInstance.Status.COMPLETED, persistedStatus())
        assertEquals(Instant.EPOCH.plus(unexpectedErrorRetryDelay), retryDelay.get())
        assertEquals(
            listOf(Event.Type.WORKFLOW_EXECUTION_STARTED, Event.Type.WORKFLOW_COMPLETED),
            publishedEventTypes()
        )
        assertEquals("hello world", resultOf(task).join())
    }

    @Test
    fun testOnSuccessContextInstallFailureRoutesLikeCallbackFailure() {
        val middleware = RecordingRequestContextMiddleware(beforeError = contextInstallError())
        val callbackHandler = mock<WorkflowCallbackHandler>()
        val capturedMetrics = CapturingMetrics()
        val task = givenWorkflowResult(completedResult(), callbackHandler)

        val retryDelay =
            handlerWith(middleware, capturedMetrics).handle(task, executor).join()

        assertEquals(listOf("before"), middleware.events)
        verify(callbackHandler, times(0)).onSuccess(any())
        assertContextFailureCounted(capturedMetrics, "onSuccess")
        // Same four observables as the callback-throws case above.
        assertEquals(WorkflowInstance.Status.COMPLETED, persistedStatus())
        assertEquals(Instant.EPOCH.plus(unexpectedErrorRetryDelay), retryDelay.get())
        assertEquals(
            listOf(Event.Type.WORKFLOW_EXECUTION_STARTED, Event.Type.WORKFLOW_COMPLETED),
            publishedEventTypes()
        )
        assertEquals("hello world", resultOf(task).join())
    }

    @Test
    fun testOnNonRetryableErrorRunsInWorkflowRequestContextAndKeepsOutcome() {
        val middleware = RecordingRequestContextMiddleware()
        val expectedError = NonRetryableError("non retryable error")
        val callbackHandler = mock<WorkflowCallbackHandler>()
        doAnswer {
            middleware.events.add("callback")
            null
        }
            .whenever(callbackHandler)
            .onNonRetryableError(any(), any())
        val task = givenWorkflowResult(erroredResult(expectedError), callbackHandler)

        val retryDelay = handlerWith(middleware).handle(task, executor).join()

        assertEquals(listOf("before", "callback", "after"), middleware.events)
        assertInstalledContextIsWorkflowsOwn(middleware, task)
        assertEquals(WorkflowInstance.Status.ERROR, persistedStatus())
        assertTrue(retryDelay.isEmpty)
        assertEquals(
            listOf(Event.Type.WORKFLOW_EXECUTION_STARTED, Event.Type.WORKFLOW_ERROR),
            publishedEventTypes()
        )
        assertEquals(expectedError, causeOfResult(task))
    }

    @Test
    fun testOnNonRetryableErrorTearsDownRequestContextWhenCallbackThrows() {
        val middleware = RecordingRequestContextMiddleware()
        val expectedError = NonRetryableError("non retryable error")
        val callbackHandler = mock<WorkflowCallbackHandler>()
        doAnswer {
            middleware.events.add("callback")
            throw RuntimeException("handler error!")
        }
            .whenever(callbackHandler)
            .onNonRetryableError(any(), any())
        val task = givenWorkflowResult(erroredResult(expectedError), callbackHandler)

        val retryDelay = handlerWith(middleware).handle(task, executor).join()

        assertEquals(listOf("before", "callback", "after"), middleware.events)
        assertEquals(WorkflowInstance.Status.ERROR, persistedStatus())
        assertEquals(Instant.EPOCH.plus(unexpectedErrorRetryDelay), retryDelay.get())
        assertEquals(
            listOf(Event.Type.WORKFLOW_EXECUTION_STARTED, Event.Type.WORKFLOW_ERROR),
            publishedEventTypes()
        )
        assertEquals(expectedError, causeOfResult(task))
    }

    @Test
    fun testOnNonRetryableErrorContextInstallFailureRoutesLikeCallbackFailure() {
        val middleware = RecordingRequestContextMiddleware(beforeError = contextInstallError())
        val expectedError = NonRetryableError("non retryable error")
        val callbackHandler = mock<WorkflowCallbackHandler>()
        val capturedMetrics = CapturingMetrics()
        val task = givenWorkflowResult(erroredResult(expectedError), callbackHandler)

        val retryDelay =
            handlerWith(middleware, capturedMetrics).handle(task, executor).join()

        assertEquals(listOf("before"), middleware.events)
        verify(callbackHandler, times(0)).onNonRetryableError(any(), any())
        assertContextFailureCounted(capturedMetrics, "onNonRetryableError")
        assertEquals(WorkflowInstance.Status.ERROR, persistedStatus())
        assertEquals(Instant.EPOCH.plus(unexpectedErrorRetryDelay), retryDelay.get())
        assertEquals(
            listOf(Event.Type.WORKFLOW_EXECUTION_STARTED, Event.Type.WORKFLOW_ERROR),
            publishedEventTypes()
        )
        assertEquals(expectedError, causeOfResult(task))
    }

    @Test
    fun testOnRetriesExhaustedRunsInWorkflowRequestContextAndKeepsOutcome() {
        val middleware = RecordingRequestContextMiddleware()
        val expectedError = PersistentRetryableError(RetryableError("retryable error"))
        val callbackHandler = mock<WorkflowCallbackHandler>()
        doAnswer {
            middleware.events.add("callback")
            null
        }
            .whenever(callbackHandler)
            .onRetriesExhausted(any(), any())
        val task = givenWorkflowResult(retriesExhaustedResult(expectedError), callbackHandler)

        val retryDelay = handlerWith(middleware).handle(task, executor).join()

        assertEquals(listOf("before", "callback", "after"), middleware.events)
        assertInstalledContextIsWorkflowsOwn(middleware, task)
        assertEquals(WorkflowInstance.Status.RETRIES_EXHAUSTED, persistedStatus())
        assertTrue(retryDelay.isEmpty)
        assertEquals(
            listOf(Event.Type.WORKFLOW_EXECUTION_STARTED, Event.Type.WORKFLOW_RETRIES_EXHAUSTED),
            publishedEventTypes()
        )
        assertTrue(causeOfResult(task) is RetriesExhaustedError)
        assertEquals(expectedError, causeOfResult(task)!!.cause)
    }

    @Test
    fun testOnRetriesExhaustedTearsDownRequestContextWhenCallbackThrows() {
        val middleware = RecordingRequestContextMiddleware()
        val expectedError = PersistentRetryableError(RetryableError("retryable error"))
        val callbackHandler = mock<WorkflowCallbackHandler>()
        doAnswer {
            middleware.events.add("callback")
            throw RuntimeException("handler error!")
        }
            .whenever(callbackHandler)
            .onRetriesExhausted(any(), any())
        val task = givenWorkflowResult(retriesExhaustedResult(expectedError), callbackHandler)

        val retryDelay = handlerWith(middleware).handle(task, executor).join()

        assertEquals(listOf("before", "callback", "after"), middleware.events)
        assertEquals(WorkflowInstance.Status.RETRIES_EXHAUSTED, persistedStatus())
        assertEquals(Instant.EPOCH.plus(unexpectedErrorRetryDelay), retryDelay.get())
        assertEquals(
            listOf(Event.Type.WORKFLOW_EXECUTION_STARTED, Event.Type.WORKFLOW_RETRIES_EXHAUSTED),
            publishedEventTypes()
        )
        assertTrue(causeOfResult(task) is RetriesExhaustedError)
    }

    @Test
    fun testOnRetriesExhaustedContextInstallFailureRoutesLikeCallbackFailure() {
        val middleware = RecordingRequestContextMiddleware(beforeError = contextInstallError())
        val expectedError = PersistentRetryableError(RetryableError("retryable error"))
        val callbackHandler = mock<WorkflowCallbackHandler>()
        val capturedMetrics = CapturingMetrics()
        val task = givenWorkflowResult(retriesExhaustedResult(expectedError), callbackHandler)

        val retryDelay =
            handlerWith(middleware, capturedMetrics).handle(task, executor).join()

        assertEquals(listOf("before"), middleware.events)
        verify(callbackHandler, times(0)).onRetriesExhausted(any(), any())
        assertContextFailureCounted(capturedMetrics, "onRetriesExhausted")
        assertEquals(WorkflowInstance.Status.RETRIES_EXHAUSTED, persistedStatus())
        assertEquals(Instant.EPOCH.plus(unexpectedErrorRetryDelay), retryDelay.get())
        assertEquals(
            listOf(Event.Type.WORKFLOW_EXECUTION_STARTED, Event.Type.WORKFLOW_RETRIES_EXHAUSTED),
            publishedEventTypes()
        )
        assertTrue(causeOfResult(task) is RetriesExhaustedError)
    }

    @Test
    fun testOnWorkflowInWaitingStatusRunsInWorkflowRequestContextAndKeepsOutcome() {
        val middleware = RecordingRequestContextMiddleware()
        val callbackHandler = mock<WorkflowCallbackHandler>()
        doAnswer {
            middleware.events.add("callback")
            null
        }
            .whenever(callbackHandler)
            .onWorkflowInWaitingStatus(any())
        val task = givenWorkflowResult(waitingResult(Duration.ofMinutes(1)), callbackHandler)

        val retryDelay = handlerWith(middleware).handle(task, executor).join()

        assertEquals(listOf("before", "callback", "after"), middleware.events)
        assertInstalledContextIsWorkflowsOwn(middleware, task)
        assertEquals(WorkflowInstance.Status.WAITING, persistedStatus())
        assertTrue(retryDelay.isEmpty)
        assertEquals(
            listOf(Event.Type.WORKFLOW_EXECUTION_STARTED, Event.Type.WORKFLOW_WAITING),
            publishedEventTypes()
        )
        assertTrue(causeOfResult(task) is WaitSignal)
    }

    @Test
    fun testOnWorkflowInWaitingStatusTearsDownRequestContextWhenCallbackThrows() {
        val middleware = RecordingRequestContextMiddleware()
        val callbackHandler = mock<WorkflowCallbackHandler>()
        doAnswer {
            middleware.events.add("callback")
            throw RuntimeException("handler error!")
        }
            .whenever(callbackHandler)
            .onWorkflowInWaitingStatus(any())
        val task = givenWorkflowResult(waitingResult(Duration.ofMinutes(1)), callbackHandler)

        val retryDelay = handlerWith(middleware).handle(task, executor).join()

        assertEquals(listOf("before", "callback", "after"), middleware.events)
        assertEquals(WorkflowInstance.Status.WAITING, persistedStatus())
        assertEquals(Instant.EPOCH.plus(unexpectedErrorRetryDelay), retryDelay.get())
        assertEquals(
            listOf(Event.Type.WORKFLOW_EXECUTION_STARTED, Event.Type.WORKFLOW_WAITING),
            publishedEventTypes()
        )
        assertTrue(causeOfResult(task) is WaitSignal)
    }

    @Test
    fun testOnWorkflowInWaitingStatusContextInstallFailureRoutesLikeCallbackFailure() {
        val middleware = RecordingRequestContextMiddleware(beforeError = contextInstallError())
        val callbackHandler = mock<WorkflowCallbackHandler>()
        val capturedMetrics = CapturingMetrics()
        val task = givenWorkflowResult(waitingResult(Duration.ofMinutes(1)), callbackHandler)

        val retryDelay =
            handlerWith(middleware, capturedMetrics).handle(task, executor).join()

        assertEquals(listOf("before"), middleware.events)
        verify(callbackHandler, times(0)).onWorkflowInWaitingStatus(any())
        assertContextFailureCounted(capturedMetrics, "onWorkflowInWaitingStatus")
        assertEquals(WorkflowInstance.Status.WAITING, persistedStatus())
        assertEquals(Instant.EPOCH.plus(unexpectedErrorRetryDelay), retryDelay.get())
        assertEquals(
            listOf(Event.Type.WORKFLOW_EXECUTION_STARTED, Event.Type.WORKFLOW_WAITING),
            publishedEventTypes()
        )
        assertTrue(causeOfResult(task) is WaitSignal)
    }

    @Test
    fun testOnRetryableErrorRunsInWorkflowRequestContextAndKeepsOutcome() {
        val middleware = RecordingRequestContextMiddleware()
        val originalError = RetryableError("original retryable error")
        val callbackHandler = mock<WorkflowCallbackHandler>()
        doAnswer {
            middleware.events.add("callback")
            null
        }
            .whenever(callbackHandler)
            .onRetryableError(any(), any())
        val task =
            givenWorkflowResult(
                transientErrorResult(originalError, Duration.ofSeconds(5)),
                callbackHandler
            )

        val retryDelay = handlerWith(middleware).handle(task, executor).join()

        assertEquals(listOf("before", "callback", "after"), middleware.events)
        assertInstalledContextIsWorkflowsOwn(middleware, task)
        assertEquals(WorkflowInstance.Status.TRANSIENT_ERROR, persistedStatus())
        assertEquals(Instant.EPOCH.plus(Duration.ofSeconds(5)), retryDelay.get())
        assertEquals(
            listOf(Event.Type.WORKFLOW_EXECUTION_STARTED, Event.Type.WORKFLOW_RETRIABLE_ERROR),
            publishedEventTypes()
        )
        assertTrue(causeOfResult(task) is TransientError)
    }

    @Test
    fun testOnRetryableErrorTearsDownRequestContextWhenCallbackThrows() {
        val middleware = RecordingRequestContextMiddleware()
        val originalError = RetryableError("original retryable error")
        val callbackHandler = mock<WorkflowCallbackHandler>()
        doAnswer {
            middleware.events.add("callback")
            throw RuntimeException("handler error!")
        }
            .whenever(callbackHandler)
            .onRetryableError(any(), any())
        val task =
            givenWorkflowResult(
                transientErrorResult(originalError, Duration.ofSeconds(5)),
                callbackHandler
            )

        val retryDelay = handlerWith(middleware).handle(task, executor).join()

        assertEquals(listOf("before", "callback", "after"), middleware.events)
        assertEquals(WorkflowInstance.Status.TRANSIENT_ERROR, persistedStatus())
        // The callback failure replaces the execution's own retry delay with the unexpected-error
        // one, exactly as it did before the bracket existed.
        assertEquals(Instant.EPOCH.plus(unexpectedErrorRetryDelay), retryDelay.get())
        assertEquals(
            listOf(Event.Type.WORKFLOW_EXECUTION_STARTED, Event.Type.WORKFLOW_RETRIABLE_ERROR),
            publishedEventTypes()
        )
        assertTrue(causeOfResult(task) is TransientError)
    }

    @Test
    fun testOnRetryableErrorContextInstallFailureRoutesLikeCallbackFailure() {
        val middleware = RecordingRequestContextMiddleware(beforeError = contextInstallError())
        val originalError = RetryableError("original retryable error")
        val callbackHandler = mock<WorkflowCallbackHandler>()
        val capturedMetrics = CapturingMetrics()
        val task =
            givenWorkflowResult(
                transientErrorResult(originalError, Duration.ofSeconds(5)),
                callbackHandler
            )

        val retryDelay =
            handlerWith(middleware, capturedMetrics).handle(task, executor).join()

        assertEquals(listOf("before"), middleware.events)
        verify(callbackHandler, times(0)).onRetryableError(any(), any())
        assertContextFailureCounted(capturedMetrics, "onRetryableError")
        assertEquals(WorkflowInstance.Status.TRANSIENT_ERROR, persistedStatus())
        assertEquals(Instant.EPOCH.plus(unexpectedErrorRetryDelay), retryDelay.get())
        assertEquals(
            listOf(Event.Type.WORKFLOW_EXECUTION_STARTED, Event.Type.WORKFLOW_RETRIABLE_ERROR),
            publishedEventTypes()
        )
        assertTrue(causeOfResult(task) is TransientError)
    }

    @Test
    fun testOnWorkflowTimeoutRunsInWorkflowRequestContextAndKeepsOutcome() {
        val middleware = RecordingRequestContextMiddleware()
        val callbackHandler = mock<WorkflowCallbackHandler>()
        doAnswer {
            middleware.events.add("callback")
            null
        }
            .whenever(callbackHandler)
            .onWorkflowTimeout(any())
        val task = givenTimedOutWorkflow(callbackHandler)

        val retryDelay = handlerWith(middleware).handle(task, executor).join()

        assertEquals(listOf("before", "callback", "after"), middleware.events)
        assertInstalledContextIsWorkflowsOwn(middleware, task)
        assertEquals(WorkflowInstance.Status.TIMEOUT, persistedTimeoutStatus())
        assertTrue(retryDelay.isEmpty)
        assertEquals(
            listOf(Event.Type.WORKFLOW_EXECUTION_STARTED, Event.Type.WORKFLOW_TIMEOUT),
            publishedEventTypes()
        )
        assertTrue(causeOfResult(task) is ExecutionTimeout)
    }

    @Test
    fun testOnWorkflowTimeoutTearsDownRequestContextWhenCallbackThrows() {
        val middleware = RecordingRequestContextMiddleware()
        val callbackHandler = mock<WorkflowCallbackHandler>()
        doAnswer {
            middleware.events.add("callback")
            throw RuntimeException("handler error!")
        }
            .whenever(callbackHandler)
            .onWorkflowTimeout(any())
        val task = givenTimedOutWorkflow(callbackHandler)

        val retryDelay = handlerWith(middleware).handle(task, executor).join()

        assertEquals(listOf("before", "callback", "after"), middleware.events)
        // The TIMEOUT status update is deliberately skipped so the retry re-attempts it.
        verify(mockWorkflowStore, times(0)).updateWorkflow(any())
        assertEquals(
            Instant.EPOCH.plusSeconds(1).plus(unexpectedErrorRetryDelay),
            retryDelay.get()
        )
        assertEquals(listOf(Event.Type.WORKFLOW_EXECUTION_STARTED), publishedEventTypes())
        assertTrue(causeOfResult(task) is ExecutionTimeout)
    }

    @Test
    fun testOnWorkflowTimeoutContextInstallFailureRoutesLikeCallbackFailure() {
        val middleware = RecordingRequestContextMiddleware(beforeError = contextInstallError())
        val callbackHandler = mock<WorkflowCallbackHandler>()
        val capturedMetrics = CapturingMetrics()
        val task = givenTimedOutWorkflow(callbackHandler)

        val retryDelay =
            handlerWith(middleware, capturedMetrics).handle(task, executor).join()

        assertEquals(listOf("before"), middleware.events)
        verify(callbackHandler, times(0)).onWorkflowTimeout(any())
        assertContextFailureCounted(capturedMetrics, "onWorkflowTimeout")
        verify(mockWorkflowStore, times(0)).updateWorkflow(any())
        assertEquals(
            Instant.EPOCH.plusSeconds(1).plus(unexpectedErrorRetryDelay),
            retryDelay.get()
        )
        assertEquals(listOf(Event.Type.WORKFLOW_EXECUTION_STARTED), publishedEventTypes())
        assertTrue(causeOfResult(task) is ExecutionTimeout)
    }

    /** A [WorkflowExecutionTaskHandler] wired with [middleware] instead of the NOOP one. */
    private fun handlerWith(
        middleware: RawRequestContextMiddleware,
        handlerMetrics: Metrics = metrics
    ): WorkflowExecutionTaskHandler =
        WorkflowExecutionTaskHandler(
            mockWorkflowExecutor,
            mockClock,
            mockWorkflowStore,
            unexpectedErrorRetryDelay,
            mockInjector,
            handlerMetrics,
            mockScheduler,
            mockEventPublisher,
            skipperEngine,
            middleware
        )

    /**
     * What [com.airbnb.skipper.common.AirbnbContextMiddleware] throws when it cannot establish the
     * context — a missing user id, or a failure minting an offline-user token.
     */
    private fun contextInstallError(): Throwable = NonRetryableError("userId is missing")

    /**
     * Stubs the executor and store so `handle` drives [result] through to its callback
     * notification, and returns the task to hand to `handle`.
     */
    private fun givenWorkflowResult(
        result: WorkflowExecutor.ExecutionResult,
        callbackHandler: WorkflowCallbackHandler
    ): Task<*> {
        whenever(mockWorkflowExecutor.executeWorkflowMethod(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(result))
        whenever(mockInjector.getInstance(WorkflowCallbackHandler::class.java))
            .thenReturn(callbackHandler)
        val workflowInstance =
            TestUtils.getWorkflowInstance().toBuilder()
                .callbackHandler(WorkflowCallbackHandler::class.java)
                .build()
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        whenever(mockWorkflowStore.getTimers(eq(workflowInstance.workflowId)))
            .thenReturn(List.empty())
        whenever(mockWorkflowStore.updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any()))
            .thenReturn(Tuple3(workflowInstance, List.empty(), List.empty()))
        return TestUtils.getTestTask(workflowInstance)
    }

    /** Stubs the store so `handle` takes the timeout branch, and returns the task. */
    private fun givenTimedOutWorkflow(callbackHandler: WorkflowCallbackHandler): Task<*> {
        whenever(mockInjector.getInstance(WorkflowCallbackHandler::class.java))
            .thenReturn(callbackHandler)
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH.plusSeconds(1))
        val workflowInstance =
            TestUtils.getWorkflowInstance().toBuilder()
                .callbackHandler(WorkflowCallbackHandler::class.java)
                .timeoutTime(Instant.EPOCH)
                .build()
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId)))
            .thenReturn(Option.of(workflowInstance))
        whenever(mockWorkflowStore.updateWorkflow(any())).thenReturn(workflowInstance)
        return TestUtils.getTestTask(workflowInstance)
    }

    private fun completedResult(): WorkflowExecutor.ExecutionResult =
        WorkflowExecutor.ExecutionResult.builder()
            .result(Either.right("hello world"))
            .newStatus(WorkflowInstance.Status.COMPLETED)
            .build()

    private fun erroredResult(error: SkipperError): WorkflowExecutor.ExecutionResult =
        WorkflowExecutor.ExecutionResult.builder()
            .result(Either.left(error))
            .newStatus(WorkflowInstance.Status.ERROR)
            .build()

    private fun retriesExhaustedResult(error: SkipperError): WorkflowExecutor.ExecutionResult =
        WorkflowExecutor.ExecutionResult.builder()
            .result(Either.left(error))
            .newStatus(WorkflowInstance.Status.RETRIES_EXHAUSTED)
            .build()

    private fun waitingResult(waitDuration: Duration): WorkflowExecutor.ExecutionResult =
        WorkflowExecutor.ExecutionResult.builder()
            .newStatus(WorkflowInstance.Status.WAITING)
            .waitDuration(waitDuration)
            .build()

    private fun transientErrorResult(
        error: SkipperError,
        retryDelay: Duration
    ): WorkflowExecutor.ExecutionResult =
        WorkflowExecutor.ExecutionResult.builder()
            .result(Either.left(error))
            .newStatus(WorkflowInstance.Status.TRANSIENT_ERROR)
            .retryDelay(retryDelay)
            .build()

    /** The status the handler asked the store to persist after executing the workflow. */
    private fun persistedStatus(): WorkflowInstance.Status {
        val captor = argumentCaptor<WorkflowUpdateRequest>()
        verify(mockWorkflowStore, times(1))
            .updateWorkflowAndStoreCheckpointsAndTimers(captor.capture(), any(), any())
        return captor.firstValue.newStatus
    }

    /** The status the handler asked the store to persist on the timeout path. */
    private fun persistedTimeoutStatus(): WorkflowInstance.Status {
        val captor = argumentCaptor<WorkflowUpdateRequest>()
        verify(mockWorkflowStore, times(1)).updateWorkflow(captor.capture())
        return captor.firstValue.newStatus
    }

    /** The lifecycle events the handler published, in order. */
    private fun publishedEventTypes(): kotlin.collections.List<Event.Type> {
        val captor = argumentCaptor<Event>()
        verify(mockEventPublisher, atLeastOnce()).publishEvent(captor.capture())
        return captor.allValues.map { it.type }
    }

    private fun resultOf(task: Task<*>): CompletableFuture<*> = (task.payload as WorkflowInstance).result

    /** The unwrapped exception the caller-facing result future completed with, if any. */
    private fun causeOfResult(task: Task<*>): Throwable? {
        val error =
            assertThrows(CompletionException::class.java) { resultOf(task).join() }
        return error.cause
    }

    private fun assertInstalledContextIsWorkflowsOwn(
        middleware: RecordingRequestContextMiddleware,
        task: Task<*>
    ) {
        val workflowInstance = task.payload as WorkflowInstance
        val expected =
            RawActionInvocation(
                workflowInstance.workflowId,
                workflowInstance.requestContext,
                workflowInstance.extraRequestData,
            )
        assertEquals(listOf(expected), middleware.beforeInvocations)
        assertEquals(listOf(expected), middleware.afterInvocations)
    }

    /**
     * A context-install failure is counted on its own counter so an identity-infrastructure outage
     * is distinguishable from a host callback bug, and on the pre-existing callback counter because
     * the callback really was not delivered.
     */
    private fun assertContextFailureCounted(
        capturedMetrics: CapturingMetrics,
        source: String
    ) {
        val contextErrors = capturedMetrics.incrementsOf("callbackContextErrors")
        assertEquals(1, contextErrors.size)
        assertEquals(
            mapOf("source" to source, "error" to "NonRetryableError"),
            contextErrors[0].tags
        )
        val handlerErrors = capturedMetrics.incrementsOf("callbackHandlerErrors")
        assertEquals(1, handlerErrors.size)
        assertEquals(
            mapOf("source" to source, "handler" to "WorkflowCallbackHandler"),
            handlerErrors[0].tags
        )
    }

    @Test
    fun testHandleSettlesAsCancelledWhenStoreIsCancelledDuringExecution() {
        // Execution ended TRANSIENT_ERROR (worker interrupted after cancelWorkflow removed its task)
        // while the store holds CANCELLED: nothing written or rescheduled, checkpoint kept, caller
        // gets CancelledWorkflow.
        class TestActions : Actions() {
            @Execute
            fun testAction(input: String): String = "result"

            @Compensate(forExecute = "testAction")
            fun compensateTestAction(input: String) {
                // compensation logic
            }
        }

        val workflowInstance = TestUtils.getWorkflowInstance()
        val checkpoint =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId(workflowInstance.workflowId)
                        .actionClass(TestActions::class.java)
                        .actionMethod("testAction")
                        .iteration(0)
                        .build()
                )
                .executionStartTime(Instant.EPOCH)
                .executionEndTime(Instant.EPOCH)
                .result(Either.right("success"))
                .isTransient(false)
                .resultIsAsync(false)
                .build()
        val result =
            WorkflowExecutor.ExecutionResult.builder()
                .result(Either.left(RetryableError("worker interrupted")))
                .newStatus(WorkflowInstance.Status.TRANSIENT_ERROR)
                .retryDelay(Duration.ofSeconds(1))
                .build()
        whenever(mockWorkflowExecutor.executeWorkflowMethod(any(), any(), any())).thenAnswer { invocation ->
            invocation.getArgument<ExecutionContext>(2).addDirtyCheckpoint(checkpoint)
            CompletableFuture.completedFuture(result)
        }
        val cancelledInStore =
            workflowInstance.toBuilder()
                .status(WorkflowInstance.Status.CANCELLED)
                .result(
                    CompletableFuture<Any?>().apply {
                        completeExceptionally(CancelledWorkflow("cancelled by test"))
                    }
                )
                .build()
        // The store holds RUNNING when the execution starts (the payload refresh) and CANCELLED by
        // the time the execution ends (the settle check).
        val getWorkflowInvocations = AtomicInteger(0)
        whenever(mockWorkflowStore.getWorkflow(eq(workflowInstance.workflowId))).thenAnswer {
            if (getWorkflowInvocations.incrementAndGet() == 1) {
                Option.of(workflowInstance)
            } else {
                Option.of(cancelledInStore)
            }
        }
        whenever(mockWorkflowStore.storeActionCheckpoints(eq(workflowInstance.workflowId), any()))
            .thenReturn(List.of(checkpoint))
        whenever(mockWorkflowStore.getTimers(eq(workflowInstance.workflowId)))
            .thenReturn(List.empty())

        val task: Task<*> = TestUtils.getTestTask(workflowInstance)
        val handleResult = taskHandler.handle(task, executor).join()

        assertTrue(handleResult.isEmpty)
        verify(mockWorkflowStore, never()).updateWorkflowAndStoreCheckpointsAndTimers(any(), any(), any())
        verify(mockWorkflowStore, never()).updateWorkflow(any())
        verify(mockWorkflowStore, times(1))
            .storeActionCheckpoints(eq(workflowInstance.workflowId), eq(List.of(checkpoint)))
        verify(mockScheduler, times(0)).schedule<Any>(any())
        val callerFuture = (task.payload as WorkflowInstance).result
        assertTrue(callerFuture.isCompletedExceptionally)
        val thrown = assertThrows(CompletionException::class.java) { callerFuture.join() }
        assertTrue(thrown.cause is CancelledWorkflow)
    }
}
