package com.airbnb.skipper.internal

import com.airbnb.skipper.CancelledWorkflow
import com.airbnb.skipper.Event
import com.airbnb.skipper.EventPublisher
import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.NoOpMetrics
import com.airbnb.skipper.ResultUnavailable
import com.airbnb.skipper.SignalMethod
import com.airbnb.skipper.SkipperError
import com.airbnb.skipper.SkipperInjector
import com.airbnb.skipper.TerminalWorkflowError
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowCallbackHandler
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.api.WorkflowInstanceView
import com.airbnb.skipper.internal.TestUtils.EXTRA_REQUEST_DATA
import com.airbnb.skipper.internal.TestUtils.REQUEST_CONTEXT
import com.airbnb.skipper.internal.TestUtils.getTestTask
import com.airbnb.skipper.internal.TestUtils.getWorkflowInstance
import com.airbnb.skipper.internal.api.PersistedSignal
import com.airbnb.skipper.internal.api.RunRequest
import com.airbnb.skipper.internal.scheduler.ScheduleRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.SchedulerExecutionQueue
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.storage.EntityAlreadyExists
import com.airbnb.skipper.internal.storage.WorkflowCreationRequest
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.internal.storage.WorkflowUpdateRequest
import com.airbnb.skipper.testutils.TestRequestContext
import com.airbnb.skipper.util.ExtraRequestData
import io.vavr.Tuple2
import io.vavr.collection.List
import io.vavr.control.Either
import io.vavr.control.Option
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SkipperEngineTest {
    private lateinit var mockWorkflowStore: WorkflowStore
    private lateinit var mockScheduler: SchedulerExecutionQueue
    private lateinit var skipperEngine: SkipperEngine
    private val executorService: ExecutorService = Executors.newFixedThreadPool(2)
    private lateinit var mockClock: Clock
    private lateinit var mockWorkflowExecutor: WorkflowExecutor
    private lateinit var mockPersistentScheduler: Scheduler
    private lateinit var mockFeatureGate: FeatureGate
    private lateinit var mockEventPublisher: EventPublisher
    private val mockMetrics: Metrics = NoOpMetrics.INSTANCE
    private lateinit var mockCallbackHandlerInjector: SkipperInjector

    @BeforeEach
    fun setUp() {
        mockWorkflowStore = mock()
        mockScheduler = mock()
        mockClock = mock()
        mockWorkflowExecutor = mock()
        mockPersistentScheduler = mock()
        mockFeatureGate = mock()
        mockEventPublisher = mock()
        mockCallbackHandlerInjector = mock()
        skipperEngine =
            SkipperEngine(
                mockWorkflowStore,
                mockScheduler,
                mockWorkflowExecutor,
                mockPersistentScheduler,
                executorService,
                mockClock,
                mockFeatureGate,
                mockEventPublisher,
                mockMetrics,
                mockCallbackHandlerInjector,
            )
    }

    @Test
    @Throws(Exception::class)
    fun testStartWorkflowWhenWorkflowDoesNotExist() {
        whenever(mockWorkflowStore.createWorkflow(any())).thenReturn(TestUtils.getWorkflowInstance())
        val task = getTestTask(getWorkflowInstance())
        whenever(mockScheduler.schedule(any<ScheduleRequest<WorkflowInstance>>())).thenReturn(task)
        whenever(mockWorkflowStore.createWorkflow(any())).thenReturn(TestUtils.getWorkflowInstance())
        val result =
            skipperEngine.startWorkflow(
                RunRequest.builder()
                    .workflowId("test-workflow-id")
                    .workflowClass(Workflow::class.java)
                    .input("test")
                    .workflowMethod("test-method")
                    .requestContext(REQUEST_CONTEXT)
                    .extraRequestData(EXTRA_REQUEST_DATA)
                    .build(),
            )
        assertFalse(result.result.isDone)
        verify(mockWorkflowStore)
            .createWorkflow(
                WorkflowCreationRequest.builder()
                    .workflowId("test-workflow-id")
                    .workflowClass(Workflow::class.java)
                    .input("test")
                    .workflowMethod("test-method")
                    .requestContext(REQUEST_CONTEXT)
                    .extraRequestData(EXTRA_REQUEST_DATA)
                    .build(),
            )
        assertFalse(result.result.isDone)
    }

    @Test
    fun testStartWorkflowWhenWorkflowDoesntExistAndRunAsync() {
        whenever(mockWorkflowStore.createWorkflow(any())).thenReturn(TestUtils.getWorkflowInstance())
        val task = getTestTask(getWorkflowInstance())
        whenever(mockScheduler.schedule(any<ScheduleRequest<WorkflowInstance>>())).thenReturn(task)
        whenever(mockWorkflowStore.createWorkflow(any())).thenReturn(TestUtils.getWorkflowInstance())
        val result =
            skipperEngine.startWorkflow(
                RunRequest.builder()
                    .workflowId("test-workflow-id")
                    .workflowClass(Workflow::class.java)
                    .input("test")
                    .workflowMethod("test-method")
                    .requestContext(REQUEST_CONTEXT)
                    .extraRequestData(EXTRA_REQUEST_DATA)
                    .runAsync(true)
                    .build(),
            )
        assertTrue(result.result.isDone)
        verify(mockWorkflowStore)
            .createWorkflow(
                WorkflowCreationRequest.builder()
                    .workflowId("test-workflow-id")
                    .workflowClass(Workflow::class.java)
                    .input("test")
                    .workflowMethod("test-method")
                    .requestContext(REQUEST_CONTEXT)
                    .extraRequestData(EXTRA_REQUEST_DATA)
                    .build(),
            )
        assertTrue(result.result.isCompletedExceptionally)
        val error = assertThrows(CompletionException::class.java) { result.result.join() }
        assertTrue(error.cause is ResultUnavailable)
    }

    @Test
    @Throws(Exception::class)
    fun testStartWorkflowWhenWorkflowExists() {
        whenever(mockFeatureGate.isEnabled(FeatureGate.Keys.CREATE_EXISTING_WORKFLOW_IS_NOOP))
            .thenReturn(false)
        whenever(mockWorkflowStore.createWorkflow(any())).thenThrow(EntityAlreadyExists())
        whenever(mockWorkflowStore.getWorkflow(any<String>()))
            .thenReturn(Option.of(TestUtils.getWorkflowInstance()))
        val task = getTestTask(getWorkflowInstance())
        whenever(mockScheduler.schedule(any<ScheduleRequest<WorkflowInstance>>())).thenReturn(task)
        val result =
            skipperEngine.startWorkflow(
                RunRequest.builder()
                    .workflowId("test-workflow-id")
                    .workflowClass(Workflow::class.java)
                    .input("test")
                    .workflowMethod("test-method")
                    .requestContext(REQUEST_CONTEXT)
                    .extraRequestData(ExtraRequestData())
                    .build(),
            )
        assertFalse(result.result.isDone)
        verify(mockScheduler, times(1)).schedule<Any>(any())
    }

    @Test
    @Throws(Exception::class)
    fun testStartWorkflowWhenWorkflowExistsAndCreationIsNoop() {
        whenever(mockFeatureGate.isEnabled(FeatureGate.Keys.CREATE_EXISTING_WORKFLOW_IS_NOOP))
            .thenReturn(true)
        whenever(mockWorkflowStore.createWorkflow(any())).thenThrow(EntityAlreadyExists())
        whenever(mockWorkflowStore.getWorkflow(any<String>()))
            .thenReturn(Option.of(TestUtils.getWorkflowInstance()))
        val task = getTestTask(getWorkflowInstance())
        whenever(mockScheduler.schedule(any<ScheduleRequest<WorkflowInstance>>())).thenReturn(task)
        val result =
            skipperEngine.startWorkflow(
                RunRequest.builder()
                    .workflowId("test-workflow-id")
                    .workflowClass(Workflow::class.java)
                    .input("test")
                    .workflowMethod("test-method")
                    .requestContext(REQUEST_CONTEXT)
                    .extraRequestData(ExtraRequestData())
                    .build(),
            )
        assertFalse(result.result.isDone)
        verify(mockScheduler, times(0)).schedule<Any>(any())
    }

    @Test
    fun testStartWorkflowWhenWorkflowExistsAndIsCompleted() {
        val instance =
            TestUtils.getWorkflowInstance().toBuilder()
                .status(WorkflowInstance.Status.COMPLETED)
                .result(CompletableFuture.completedFuture("result"))
                .build()
        whenever(mockWorkflowStore.createWorkflow(any())).thenThrow(EntityAlreadyExists())
        whenever(mockWorkflowStore.getWorkflow(any<String>())).thenReturn(Option.of(instance))
        val result =
            skipperEngine.startWorkflow(
                RunRequest.builder()
                    .workflowId("test-workflow-id")
                    .workflowClass(Workflow::class.java)
                    .input("test")
                    .workflowMethod("test-method")
                    .requestContext(REQUEST_CONTEXT)
                    .extraRequestData(ExtraRequestData())
                    .build(),
            )
        assertTrue(result.result.isDone)
        verify(mockScheduler, times(0)).schedule<Any>(any())
    }

    @ParameterizedTest
    @EnumSource(
        value = WorkflowInstance.Status::class,
        mode = EnumSource.Mode.INCLUDE,
        names = ["COMPENSATION_IN_PROGRESS", "COMPENSATION_ERROR"],
    )
    fun testStartWorkflowWhenWorkflowExistsAndIsInCompensationState(status: WorkflowInstance.Status) {
        val instance =
            TestUtils.getWorkflowInstance().toBuilder()
                .status(status)
                .result(CompletableFuture.completedFuture("compensation result"))
                .build()
        whenever(mockWorkflowStore.createWorkflow(any())).thenThrow(EntityAlreadyExists())
        whenever(mockWorkflowStore.getWorkflow(any<String>())).thenReturn(Option.of(instance))
        val result =
            skipperEngine.startWorkflow(
                RunRequest.builder()
                    .workflowId("test-workflow-id")
                    .workflowClass(Workflow::class.java)
                    .input("test")
                    .workflowMethod("test-method")
                    .requestContext(REQUEST_CONTEXT)
                    .extraRequestData(ExtraRequestData())
                    .build(),
            )
        assertTrue(result.result.isDone)
        verify(mockScheduler, times(0)).schedule<Any>(any())
    }

    @Test
    fun testStartWorkflowWhenWorkflowExistsAndDuplicateIsNotAllowed() {
        whenever(mockWorkflowStore.createWorkflow(any())).thenThrow(EntityAlreadyExists())
        whenever(mockWorkflowStore.getWorkflow(any<String>()))
            .thenReturn(Option.of(TestUtils.getWorkflowInstance()))
        val task = getTestTask(getWorkflowInstance())
        whenever(mockScheduler.schedule(any<ScheduleRequest<WorkflowInstance>>())).thenReturn(task)
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                skipperEngine.startWorkflow(
                    RunRequest.builder()
                        .workflowId("test-workflow-id")
                        .workflowClass(Workflow::class.java)
                        .input("test")
                        .workflowMethod("test-method")
                        .requestContext(REQUEST_CONTEXT)
                        .extraRequestData(ExtraRequestData())
                        .failOnDuplicate(true)
                        .build(),
                )
            }
        assertTrue(error.message!!.contains("workflow with id %s already exists"))
    }

    @Test
    fun testClone() {
        whenever(mockWorkflowStore.getWorkflow(any<String>()))
            .thenReturn(Option.of(TestUtils.getWorkflowInstance()))
        val task = getTestTask(getWorkflowInstance())
        whenever(mockScheduler.schedule(any<ScheduleRequest<WorkflowInstance>>())).thenReturn(task)
        whenever(mockWorkflowStore.createWorkflow(any())).thenReturn(TestUtils.getWorkflowInstance())
        skipperEngine.cloneWorkflowInstance("test-workflow-id", "new-workflow-id", REQUEST_CONTEXT)
        val captor = argumentCaptor<WorkflowCreationRequest>()
        verify(mockWorkflowStore, times(1)).createWorkflow(captor.capture())
        assertEquals("new-workflow-id", captor.firstValue.workflowId)
        verify(mockScheduler, times(1)).schedule<Any>(any())
    }

    @Test
    fun testCloneWhenWorkflowDoesNotExist() {
        whenever(mockWorkflowStore.getWorkflow(any<String>())).thenReturn(Option.none())
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                skipperEngine.cloneWorkflowInstance(
                    "test-workflow-id",
                    "new-workflow-id",
                    REQUEST_CONTEXT,
                )
            }
        assertTrue(error.message!!.contains("workflow with id %s does not exist"))
    }

    @Test
    @Throws(Exception::class)
    fun testGetWorkflow() {
        val instance = getWorkflowInstance()
        whenever(mockWorkflowStore.getWorkflow(any<String>())).thenReturn(Option.of(instance))
        val result = skipperEngine.getWorkflow("test-workflow-id")
        assertTrue(result.isDefined)
        assertEquals(instance, result.get())
    }

    @Test
    fun testSendSignal_nonExistentWorkflow() {
        whenever(mockWorkflowStore.getWorkflow(any<String>())).thenReturn(Option.none())
        assertThrows(IllegalArgumentException::class.java) {
            skipperEngine.sendSignal(
                RunRequest.builder()
                    .workflowId("test-workflow-id")
                    .workflowClass(Workflow::class.java)
                    .input("test")
                    .workflowMethod("test-method")
                    .requestContext(REQUEST_CONTEXT)
                    .extraRequestData(ExtraRequestData())
                    .build(),
            )
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = WorkflowInstance.Status::class,
        mode = EnumSource.Mode.INCLUDE,
        names = ["COMPLETED", "ERROR", "TIMEOUT", "CANCELLED"],
    )
    fun testSendSignal_terminalWorkflow(status: WorkflowInstance.Status) {
        whenever(mockWorkflowStore.getWorkflow(any<String>()))
            .thenReturn(Option.of(TestUtils.getWorkflowInstance().toBuilder().status(status).build()))
        assertThrows(TerminalWorkflowError::class.java) {
            skipperEngine.sendSignal(
                RunRequest.builder()
                    .workflowId("test-workflow-id")
                    .workflowClass(Workflow::class.java)
                    .input("test")
                    .workflowMethod("test-method")
                    .requestContext(REQUEST_CONTEXT)
                    .extraRequestData(ExtraRequestData())
                    .build(),
            )
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = WorkflowInstance.Status::class,
        mode = EnumSource.Mode.INCLUDE,
        names = ["COMPENSATION_IN_PROGRESS", "COMPENSATION_ERROR"],
    )
    fun testSendSignal_compensationWorkflow(status: WorkflowInstance.Status) {
        whenever(mockWorkflowStore.getWorkflow(any<String>()))
            .thenReturn(Option.of(TestUtils.getWorkflowInstance().toBuilder().status(status).build()))
        assertThrows(TerminalWorkflowError::class.java) {
            skipperEngine.sendSignal(
                RunRequest.builder()
                    .workflowId("test-workflow-id")
                    .workflowClass(Workflow::class.java)
                    .input("test")
                    .workflowMethod("test-method")
                    .requestContext(REQUEST_CONTEXT)
                    .extraRequestData(ExtraRequestData())
                    .build(),
            )
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = WorkflowInstance.Status::class,
        mode = EnumSource.Mode.EXCLUDE,
        names = [
            "COMPLETED",
            "ERROR",
            "TIMEOUT",
            "CANCELLED",
            "COMPENSATION_COMPLETED",
            "COMPENSATION_IN_PROGRESS",
            "COMPENSATION_ERROR",
        ],
    )
    fun testSendSignal_pendingWorkflow(status: WorkflowInstance.Status) {
        val gateValues = List.of(true, false)
        for (gateValue in gateValues) {
            whenever(mockFeatureGate.isEnabled(FeatureGate.Keys.FORCE_SIGNAL_WORKFLOW_EXEC_IN_SCHEDULER))
                .thenReturn(gateValue)
            // The bump-on-honoured-lease gate must be forwarded to the scheduler on the request.
            whenever(mockFeatureGate.isEnabled(FeatureGate.Keys.BUMP_TASK_VERSION_ON_HONORED_LEASE))
                .thenReturn(gateValue)

            val originalWorkflowInstance =
                getWorkflowInstance().toBuilder().status(status).build()
            whenever(mockWorkflowStore.getWorkflow(any<String>()))
                .thenReturn(Option.of(originalWorkflowInstance))
            whenever(mockWorkflowStore.getActionCheckpoints(any<String>()))
                .thenReturn(io.vavr.collection.List.empty())
            whenever(mockWorkflowExecutor.executeSignalMethod(any(), any(), any(), any(), any()))
                .thenReturn(
                    CompletableFuture.completedFuture(
                        WorkflowExecutor.ExecutionResult.builder()
                            .result(Either.right("result"))
                            .newState(io.vavr.collection.HashMap.empty())
                            .build(),
                    ),
                )
            val updatedWorkflowInstance =
                getWorkflowInstance().toBuilder().status(WorkflowInstance.Status.COMPLETED).build()
            whenever(mockWorkflowStore.updateWorkflow(any())).thenReturn(updatedWorkflowInstance)
            whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
            val newRequestContext = TestRequestContext.builder().userId("1").build()
            val signalResult =
                skipperEngine.sendSignal(
                    RunRequest.builder()
                        .workflowId("test-workflow-id")
                        .workflowClass(Workflow::class.java)
                        .input("test")
                        .workflowMethod("test-method")
                        .requestContext(newRequestContext)
                        .extraRequestData(ExtraRequestData())
                        .build(),
                )

            assertSame(updatedWorkflowInstance, signalResult.getWorkflowInstance())
            assertEquals(
                Either.right<SkipperError, Any?>("result"),
                signalResult.getSignalResponse(),
            )
            verify(mockWorkflowExecutor)
                .executeSignalMethod(
                    eq(originalWorkflowInstance.toBuilder().requestContext(newRequestContext).build()),
                    eq(executorService),
                    any(),
                    eq("test-method"),
                    eq("test"),
                )
            verify(mockScheduler)
                .schedule(
                    ScheduleRequest.builder<Any>()
                        .id("test-workflow-id")
                        .dedupToken("test-workflow-id")
                        .payload(
                            updatedWorkflowInstance.toBuilder().requestContext(REQUEST_CONTEXT).build(),
                        )
                        .type(Task.Type.WORKFLOW)
                        .honorActiveLeaseWhenOverwriting(true)
                        .bumpVersionWhenHonoringLease(gateValue)
                        .inMemoryExecutionEnabled(!gateValue)
                        .build(),
                )
        }
    }

    @Test
    fun testRequestContextFallbackToOriginalContextWhenAbsent() {
        val originalWorkflowInstance = getWorkflowInstance()
        whenever(mockWorkflowStore.getWorkflow(any<String>()))
            .thenReturn(Option.of(originalWorkflowInstance))
        whenever(mockWorkflowStore.getActionCheckpoints(any<String>()))
            .thenReturn(io.vavr.collection.List.empty())
        whenever(mockWorkflowExecutor.executeSignalMethod(any(), any(), any(), any(), any()))
            .thenReturn(
                CompletableFuture.completedFuture(
                    WorkflowExecutor.ExecutionResult.builder()
                        .result(Either.right("result"))
                        .newState(io.vavr.collection.HashMap.empty())
                        .build(),
                ),
            )
        val updatedWorkflowInstance =
            getWorkflowInstance().toBuilder().status(WorkflowInstance.Status.COMPLETED).build()
        whenever(mockWorkflowStore.updateWorkflow(any())).thenReturn(updatedWorkflowInstance)
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        // When the signal carries a null request context, the engine should keep the workflow's
        // stored context unchanged.
        val signalResult =
            skipperEngine.sendSignal(
                RunRequest.builder()
                    .workflowId("test-workflow-id")
                    .workflowClass(Workflow::class.java)
                    .input("test")
                    .workflowMethod("test-method")
                    .requestContext(null)
                    .extraRequestData(ExtraRequestData())
                    .build(),
            )

        assertSame(updatedWorkflowInstance, signalResult.getWorkflowInstance())
        assertEquals(Either.right<SkipperError, Any?>("result"), signalResult.getSignalResponse())
        verify(mockWorkflowExecutor)
            .executeSignalMethod(
                eq(originalWorkflowInstance),
                eq(executorService),
                any(),
                eq("test-method"),
                eq("test"),
            )
        verify(mockScheduler)
            .schedule(
                ScheduleRequest.builder<Any>()
                    .id("test-workflow-id")
                    .dedupToken("test-workflow-id")
                    .payload(updatedWorkflowInstance)
                    .type(Task.Type.WORKFLOW)
                    .honorActiveLeaseWhenOverwriting(true)
                    .inMemoryExecutionEnabled(true)
                    .build(),
            )
    }

    @ParameterizedTest
    @EnumSource(
        value = WorkflowInstance.Status::class,
        mode = EnumSource.Mode.INCLUDE,
        names = ["COMPENSATION_IN_PROGRESS", "COMPENSATION_ERROR"],
    )
    fun testCancelWorkflow_compensationWorkflow(status: WorkflowInstance.Status) {
        whenever(mockWorkflowStore.getWorkflow(any<String>()))
            .thenReturn(Option.of(TestUtils.getWorkflowInstance().toBuilder().status(status).build()))
        assertThrows(IllegalArgumentException::class.java) {
            skipperEngine.cancelWorkflow("test-workflow-id", "test cancellation reason")
        }
    }

    @Test
    fun testCancelWorkflow_nonExistentWorkflow() {
        whenever(mockWorkflowStore.getWorkflow(any<String>())).thenReturn(Option.none())
        assertThrows(IllegalArgumentException::class.java) {
            skipperEngine.cancelWorkflow("test-workflow-id", "test cancellation reason")
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = WorkflowInstance.Status::class,
        mode = EnumSource.Mode.INCLUDE,
        names = ["COMPLETED", "ERROR", "TIMEOUT", "CANCELLED"],
    )
    fun testCancelWorkflow_terminalWorkflow(status: WorkflowInstance.Status) {
        whenever(mockWorkflowStore.getWorkflow(any<String>()))
            .thenReturn(Option.of(TestUtils.getWorkflowInstance().toBuilder().status(status).build()))
        assertThrows(IllegalArgumentException::class.java) {
            skipperEngine.cancelWorkflow("test-workflow-id", "test cancellation reason")
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun testCancelWorkflow_success() {
        val originalInstance = TestUtils.getWorkflowInstance()
        val canceledInstance =
            originalInstance.toBuilder().status(WorkflowInstance.Status.CANCELLED).build()
        val testTask = getTestTask(originalInstance)

        whenever(mockWorkflowStore.getWorkflow("test-workflow-id")).thenReturn(Option.of(originalInstance))
        whenever(mockWorkflowStore.updateWorkflow(any<WorkflowUpdateRequest>()))
            .thenReturn(canceledInstance)
        whenever(mockPersistentScheduler.getTask<WorkflowInstance>(any<String>()))
            .thenReturn(Option.of(testTask))

        val result = skipperEngine.cancelWorkflow("test-workflow-id", "test reason")

        assertEquals(WorkflowInstance.Status.CANCELLED, result.status)
        verify(mockWorkflowStore)
            .updateWorkflow(
                argThat {
                    this.newStatus == WorkflowInstance.Status.CANCELLED &&
                        this.workflowInstance == originalInstance &&
                        this.result!!.isLeft &&
                        this.result!!.left is CancelledWorkflow &&
                        this.result!!.left.message == "test reason"
                },
            )
        verify(mockPersistentScheduler).remove<WorkflowInstance>(testTask)
        verify(mockEventPublisher)
            .publishEvent(Event.create(Event.Type.WORKFLOW_CANCELLED, "test-workflow-id"))
    }

    @Test
    fun testCancelWorkflow_noScheduledTask() {
        val originalInstance = TestUtils.getWorkflowInstance()
        val canceledInstance =
            originalInstance.toBuilder().status(WorkflowInstance.Status.CANCELLED).build()

        whenever(mockWorkflowStore.getWorkflow("test-workflow-id")).thenReturn(Option.of(originalInstance))
        whenever(mockWorkflowStore.updateWorkflow(any<WorkflowUpdateRequest>()))
            .thenReturn(canceledInstance)
        whenever(mockPersistentScheduler.getTask<WorkflowInstance>("test-workflow-id")).thenReturn(Option.none())

        val result = skipperEngine.cancelWorkflow("test-workflow-id", "test reason")

        assertEquals(WorkflowInstance.Status.CANCELLED, result.status)
        verify(mockWorkflowStore)
            .updateWorkflow(
                argThat {
                    this.result!!.isLeft &&
                        this.result!!.left is CancelledWorkflow &&
                        this.result!!.left.message == "test reason"
                },
            )
        verify(mockPersistentScheduler, never()).remove<Any>(any())
        verify(mockEventPublisher)
            .publishEvent(Event.create(Event.Type.WORKFLOW_CANCELLED, "test-workflow-id"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun testCancelWorkflow_invokesCallbackHandler() {
        val originalInstance =
            TestUtils.getWorkflowInstance().toBuilder()
                .callbackHandler(TestCallbackHandler::class.java)
                .build()
        val canceledInstance =
            originalInstance.toBuilder().status(WorkflowInstance.Status.CANCELLED).build()
        val mockCallbackHandler = mock<WorkflowCallbackHandler>()

        whenever(mockWorkflowStore.getWorkflow("test-workflow-id")).thenReturn(Option.of(originalInstance))
        whenever(mockWorkflowStore.updateWorkflow(any<WorkflowUpdateRequest>()))
            .thenReturn(canceledInstance)
        whenever(mockPersistentScheduler.getTask<WorkflowInstance>("test-workflow-id")).thenReturn(Option.none())
        whenever(mockWorkflowStore.getActionCheckpoints("test-workflow-id"))
            .thenReturn(io.vavr.collection.List.empty())
        whenever(mockCallbackHandlerInjector.getInstance(any<Class<WorkflowCallbackHandler>>()))
            .thenReturn(mockCallbackHandler)

        skipperEngine.cancelWorkflow("test-workflow-id", "test reason")

        verify(mockCallbackHandler).onCancelled(any<WorkflowInstanceView>(), eq("test reason"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun testCancelWorkflow_callbackHandlerError_doesNotPreventCancellation() {
        val originalInstance =
            TestUtils.getWorkflowInstance().toBuilder()
                .callbackHandler(TestCallbackHandler::class.java)
                .build()
        val canceledInstance =
            originalInstance.toBuilder().status(WorkflowInstance.Status.CANCELLED).build()
        val mockCallbackHandler = mock<WorkflowCallbackHandler>()

        whenever(mockWorkflowStore.getWorkflow("test-workflow-id")).thenReturn(Option.of(originalInstance))
        whenever(mockWorkflowStore.updateWorkflow(any<WorkflowUpdateRequest>()))
            .thenReturn(canceledInstance)
        whenever(mockPersistentScheduler.getTask<WorkflowInstance>("test-workflow-id")).thenReturn(Option.none())
        whenever(mockWorkflowStore.getActionCheckpoints("test-workflow-id"))
            .thenReturn(io.vavr.collection.List.empty())
        whenever(mockCallbackHandlerInjector.getInstance(any<Class<WorkflowCallbackHandler>>()))
            .thenReturn(mockCallbackHandler)
        doThrow(RuntimeException("Callback handler error"))
            .whenever(mockCallbackHandler)
            .onCancelled(any<WorkflowInstanceView>(), any<String>())

        val result = skipperEngine.cancelWorkflow("test-workflow-id", "test reason")

        assertEquals(WorkflowInstance.Status.CANCELLED, result.status)
        verify(mockEventPublisher)
            .publishEvent(Event.create(Event.Type.WORKFLOW_CANCELLED, "test-workflow-id"))
    }

    class TestCallbackHandler : WorkflowCallbackHandler {
        override fun onSuccess(workflowInstance: WorkflowInstanceView) = Unit

        override fun onNonRetryableError(
            workflowInstance: WorkflowInstanceView,
            error: Throwable
        ) = Unit

        override fun onWorkflowInWaitingStatus(workflowInstance: WorkflowInstanceView) = Unit

        override fun onWorkflowTimeout(workflowInstance: WorkflowInstanceView) = Unit
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Persisted signals (@SignalMethod(persist = true))
    // ──────────────────────────────────────────────────────────────────────────

    /** Workflow whose signal methods exercise the persist opt-in. */
    class PersistableSignalWorkflow : Workflow() {
        @WorkflowMethod
        fun run() = Unit

        @SignalMethod(persist = true)
        fun persistedSignal(value: String) = Unit

        @SignalMethod
        fun plainSignal(value: String) = Unit
    }

    /**
     * Stubs the collaborators needed for a signal to execute successfully against [instance].
     */
    private fun stubSuccessfulSignalExecution(instance: WorkflowInstance) {
        whenever(mockWorkflowStore.getWorkflow(any<String>())).thenReturn(Option.of(instance))
        whenever(mockWorkflowStore.getActionCheckpoints(any<String>()))
            .thenReturn(io.vavr.collection.List.empty())
        whenever(mockWorkflowExecutor.executeSignalMethod(any(), any(), any(), any(), any()))
            .thenReturn(
                CompletableFuture.completedFuture(
                    WorkflowExecutor.ExecutionResult.builder()
                        .result(Either.right("signal-result"))
                        .newState(io.vavr.collection.HashMap.empty())
                        .build(),
                ),
            )
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
    }

    private fun signalRequest(workflowMethod: String): RunRequest =
        RunRequest.builder()
            .workflowId("test-workflow-id")
            .workflowClass(PersistableSignalWorkflow::class.java)
            .input("the-input")
            .workflowMethod(workflowMethod)
            .requestContext(REQUEST_CONTEXT)
            .extraRequestData(ExtraRequestData())
            .build()

    @Test
    fun testSendSignal_persistedSignalSuccess_persistsPendingThenMarksExecuted() {
        val instance =
            getWorkflowInstance().toBuilder()
                .workflowClass(PersistableSignalWorkflow::class.java)
                .status(WorkflowInstance.Status.WAITING)
                .build()
        stubSuccessfulSignalExecution(instance)
        val persisted =
            PersistedSignal(
                "test-workflow-id",
                "persistedSignal",
                PersistedSignal.Status.PENDING,
                null,
                null,
                null,
                42L,
            )
        whenever(mockWorkflowStore.persistSignal(any())).thenReturn(persisted)
        val updatedInstance =
            instance.toBuilder().status(WorkflowInstance.Status.RUNNING).build()
        whenever(mockWorkflowStore.updateWorkflowAndMarkSignal(any(), eq(42L), any()))
            .thenReturn(Tuple2(updatedInstance, persisted))

        val result = skipperEngine.sendSignal(signalRequest("persistedSignal"))

        assertSame(updatedInstance, result.getWorkflowInstance())
        // A PENDING row is persisted BEFORE the signal executes...
        verify(mockWorkflowStore)
            .persistSignal(
                argThat {
                    workflowId == "test-workflow-id" &&
                        signalMethod == "persistedSignal" &&
                        input == "the-input" &&
                        status == PersistedSignal.Status.PENDING
                },
            )
        // ...then the workflow update and EXECUTED mark commit together.
        verify(mockWorkflowStore)
            .updateWorkflowAndMarkSignal(any(), eq(42L), eq(PersistedSignal.Status.EXECUTED))
        // The non-persisted update path must NOT be used for a persisted signal.
        verify(mockWorkflowStore, never()).updateWorkflow(any())
    }

    @Test
    fun testSendSignal_persistedSignalFails_marksFailedAndRethrows() {
        val instance =
            getWorkflowInstance().toBuilder()
                .workflowClass(PersistableSignalWorkflow::class.java)
                .status(WorkflowInstance.Status.WAITING)
                .build()
        stubSuccessfulSignalExecution(instance)
        val persisted =
            PersistedSignal(
                "test-workflow-id",
                "persistedSignal",
                PersistedSignal.Status.PENDING,
                null,
                null,
                null,
                42L,
            )
        whenever(mockWorkflowStore.persistSignal(any())).thenReturn(persisted)
        val failure = RuntimeException("update failed")
        whenever(mockWorkflowStore.updateWorkflowAndMarkSignal(any(), eq(42L), any())).thenThrow(failure)

        val thrown =
            assertThrows(RuntimeException::class.java) {
                skipperEngine.sendSignal(signalRequest("persistedSignal"))
            }

        assertSame(failure, thrown)
        // The persisted row is marked FAILED with the error (now a stack-trace string, produced by the
        // engine via Throwables.getStackTraceAsString) so an operator can replay it.
        val errorCaptor = argumentCaptor<String>()
        verify(mockWorkflowStore)
            .updateSignalStatus(
                eq("test-workflow-id"),
                eq(42L),
                eq(PersistedSignal.Status.FAILED),
                errorCaptor.capture(),
            )
        assertTrue(errorCaptor.firstValue.contains("update failed"))
    }

    @Test
    fun testSendSignal_persistDisabledByFeatureGate_writesNoRows() {
        whenever(mockFeatureGate.isEnabled(FeatureGate.Keys.DISABLE_SIGNAL_PERSISTENCE)).thenReturn(true)
        val instance =
            getWorkflowInstance().toBuilder()
                .workflowClass(PersistableSignalWorkflow::class.java)
                .status(WorkflowInstance.Status.WAITING)
                .build()
        stubSuccessfulSignalExecution(instance)
        whenever(mockWorkflowStore.updateWorkflow(any())).thenReturn(instance)

        skipperEngine.sendSignal(signalRequest("persistedSignal"))

        verify(mockWorkflowStore, never()).persistSignal(any())
        verify(mockWorkflowStore, never())
            .updateWorkflowAndMarkSignal(any(), any<Long>(), any())
        // Falls back to the legacy plain update.
        verify(mockWorkflowStore).updateWorkflow(any())
    }

    @Test
    fun testSendSignal_nonPersistedSignal_writesNoRows() {
        val instance =
            getWorkflowInstance().toBuilder()
                .workflowClass(PersistableSignalWorkflow::class.java)
                .status(WorkflowInstance.Status.WAITING)
                .build()
        stubSuccessfulSignalExecution(instance)
        whenever(mockWorkflowStore.updateWorkflow(any())).thenReturn(instance)

        skipperEngine.sendSignal(signalRequest("plainSignal"))

        verify(mockWorkflowStore, never()).persistSignal(any())
        verify(mockWorkflowStore, never())
            .updateWorkflowAndMarkSignal(any(), any<Long>(), any())
        verify(mockWorkflowStore).updateWorkflow(any())
    }

    @Test
    fun testReplaySignal_reusesExistingRowAndMarksExecuted() {
        val instance =
            getWorkflowInstance().toBuilder()
                .workflowClass(PersistableSignalWorkflow::class.java)
                .status(WorkflowInstance.Status.WAITING)
                .build()
        stubSuccessfulSignalExecution(instance)
        val failedSignal =
            PersistedSignal(
                "test-workflow-id",
                "persistedSignal",
                PersistedSignal.Status.FAILED,
                "original-input",
                REQUEST_CONTEXT,
                null,
                7L,
            )
        whenever(mockWorkflowStore.getPersistedSignal("test-workflow-id", 7L))
            .thenReturn(Option.of(failedSignal))
        val updatedInstance =
            instance.toBuilder().status(WorkflowInstance.Status.RUNNING).build()
        whenever(mockWorkflowStore.updateWorkflowAndMarkSignal(any(), eq(7L), any()))
            .thenReturn(Tuple2(updatedInstance, failedSignal))

        val result = skipperEngine.replaySignal("test-workflow-id", 7L)

        assertSame(updatedInstance, result.getWorkflowInstance())
        // Replay reconstructs the original invocation from the stored record...
        verify(mockWorkflowExecutor)
            .executeSignalMethod(any(), any(), any(), eq("persistedSignal"), eq("original-input"))
        // ...reuses the SAME row (no new persistSignal) and flips it to EXECUTED.
        verify(mockWorkflowStore, never()).persistSignal(any())
        verify(mockWorkflowStore)
            .updateWorkflowAndMarkSignal(any(), eq(7L), eq(PersistedSignal.Status.EXECUTED))
    }

    @Test
    fun testReplaySignal_nonExistentSignal_throws() {
        val instance =
            getWorkflowInstance().toBuilder().status(WorkflowInstance.Status.WAITING).build()
        whenever(mockWorkflowStore.getWorkflow(any<String>())).thenReturn(Option.of(instance))
        whenever(mockWorkflowStore.getPersistedSignal(any<String>(), any<Long>()))
            .thenReturn(Option.none())

        assertThrows(IllegalArgumentException::class.java) {
            skipperEngine.replaySignal("test-workflow-id", 99L)
        }
    }

    @Test
    fun testScheduleCompensationTask() {
        // Arrange
        val fixedInstant = Instant.parse("2023-01-01T00:00:00Z")
        val testWorkflowId = "test-workflow-123"
        whenever(mockClock.instant()).thenReturn(fixedInstant)

        val workflowInstance =
            TestUtils.getWorkflowInstance().toBuilder().workflowId(testWorkflowId).build()
        val expectedTask = getTestTask(workflowInstance)
        whenever(mockPersistentScheduler.schedule(any<ScheduleRequest<WorkflowInstance>>()))
            .thenReturn(expectedTask)

        // Act
        skipperEngine.scheduleCompensationTask(workflowInstance)

        // Assert
        val scheduleRequestCaptor = argumentCaptor<ScheduleRequest<Any>>()
        verify(mockPersistentScheduler, times(1)).schedule(scheduleRequestCaptor.capture())

        val capturedRequest = scheduleRequestCaptor.firstValue
        assertEquals("$testWorkflowId-compensation", capturedRequest.id)
        assertEquals("$testWorkflowId-compensation", capturedRequest.dedupToken)
        assertEquals(testWorkflowId, capturedRequest.payload)
        assertEquals(Task.Type.COMPENSATION, capturedRequest.type)
        assertEquals(fixedInstant, capturedRequest.runAfter)
        assertFalse(capturedRequest.inMemoryExecutionEnabled)
    }

    @Test
    fun testScheduleCompensationTaskWithDifferentWorkflowId() {
        // Arrange
        val fixedInstant = Instant.parse("2023-01-01T00:00:00Z")
        val differentWorkflowId = "another-workflow-456"
        whenever(mockClock.instant()).thenReturn(fixedInstant)

        val workflowInstance =
            TestUtils.getWorkflowInstance().toBuilder().workflowId(differentWorkflowId).build()
        val expectedTask = getTestTask(workflowInstance)
        whenever(mockPersistentScheduler.schedule(any<ScheduleRequest<WorkflowInstance>>()))
            .thenReturn(expectedTask)

        // Act
        skipperEngine.scheduleCompensationTask(workflowInstance)

        // Assert
        val scheduleRequestCaptor = argumentCaptor<ScheduleRequest<Any>>()
        verify(mockPersistentScheduler, times(1)).schedule(scheduleRequestCaptor.capture())

        val capturedRequest = scheduleRequestCaptor.firstValue
        assertEquals("$differentWorkflowId-compensation", capturedRequest.id)
        assertEquals("$differentWorkflowId-compensation", capturedRequest.dedupToken)
        assertEquals(differentWorkflowId, capturedRequest.payload)
        assertEquals(Task.Type.COMPENSATION, capturedRequest.type)
        assertEquals(fixedInstant, capturedRequest.runAfter)
    }

    @Test
    fun testScheduleCompensationTaskUsesClockInstant() {
        // Arrange
        val laterInstant = Instant.parse("2023-06-15T12:30:45Z")
        val testWorkflowId = "test-workflow-123"
        whenever(mockClock.instant()).thenReturn(laterInstant)

        val workflowInstance =
            TestUtils.getWorkflowInstance().toBuilder().workflowId(testWorkflowId).build()
        val expectedTask = getTestTask(workflowInstance)
        whenever(mockPersistentScheduler.schedule(any<ScheduleRequest<WorkflowInstance>>()))
            .thenReturn(expectedTask)

        // Act
        skipperEngine.scheduleCompensationTask(workflowInstance)

        // Assert
        val scheduleRequestCaptor = argumentCaptor<ScheduleRequest<Any>>()
        verify(mockPersistentScheduler, times(1)).schedule(scheduleRequestCaptor.capture())

        val capturedRequest = scheduleRequestCaptor.firstValue
        assertEquals(laterInstant, capturedRequest.runAfter)
    }
}
