package com.airbnb.skipper.internal

import com.airbnb.skipper.Actions
import com.airbnb.skipper.ApplicationError
import com.airbnb.skipper.CheckpointMode
import com.airbnb.skipper.Compensate
import com.airbnb.skipper.ContextPropagator
import com.airbnb.skipper.EventPublisher
import com.airbnb.skipper.Execute
import com.airbnb.skipper.ExecutionMetricsCollector
import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.FixedRetryStrategy
import com.airbnb.skipper.InMemoryFeatureGate
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.NoOpMetrics
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.PersistentRetryStrategy
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.WorkflowCancelledException
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.internal.storage.WorkflowStore
import io.opentracing.Scope
import io.opentracing.Span
import io.opentracing.Tracer
import io.vavr.collection.List
import io.vavr.control.Either
import io.vavr.control.Option
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ActionExecutorTest {
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var mockClock: Clock
    private lateinit var mockWorkflowStore: WorkflowStore
    private lateinit var mockEventPublisher: EventPublisher
    private lateinit var mockExecutionMetricsCollector: ExecutionMetricsCollector
    private lateinit var mockTracer: Tracer
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var actionMap: MutableMap<Class<out Actions>, Actions>
    private val workflow = TestUtils.getWorkflowInstance()
    private val errorMapper = ActionErrorMapper(DefaultExceptionClassifier())
    private val checkpointTag =
        CheckpointTag.builder()
            .workflowId(workflow.workflowId)
            .actionClass(DemoActions::class.java)
            .actionMethod("hello")
            .iteration(0)
            .build()
    private val metrics: Metrics = NoOpMetrics.INSTANCE

    @BeforeEach
    fun setup() {
        mockClock = mock<Clock>()
        mockWorkflowStore = mock<WorkflowStore>()
        mockEventPublisher = mock<EventPublisher>()
        mockExecutionMetricsCollector = mock<ExecutionMetricsCollector>()
        mockTracer = mock<Tracer>()

        // Setup default behavior for span creation
        val mockSpan = mock<Span>()
        whenever(mockExecutionMetricsCollector.createActionSpan(any())).thenReturn(mockSpan)
        whenever(mockTracer.activateSpan(any())).thenReturn(mock<Scope>())

        actionMap = HashMap()
        actionMap[DemoActions::class.java] = DemoActions()
        actionExecutor =
            ActionExecutor(
                mockWorkflowStore,
                CheckpointMode.EVENTUAL_CHECKPOINT,
                metrics,
                errorMapper,
                mockEventPublisher,
                mockExecutionMetricsCollector,
                mockTracer,
                ContextPropagator.NOOP,
            null,
            )
    }

    private fun newExecContext(): ExecutionContext =
        ExecutionContext.builder()
            .workflow(TestUtils.getWorkflowInstance())
            .clock(mockClock)
            .executorService(executorService)
            .build()

    @Test
    fun testExecuteHappyPath() {
        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        val result = actionExecutor.executeAction(req)
        assertEquals("Hello Ricardo", result)
        assertEquals(1, executionContext.getActionIteration(DemoActions::class.java, "hello"))
        assertEquals(1, executionContext.dirtyCheckpoints.size())
        val checkpoint = executionContext.dirtyCheckpoints.get(0)
        assertEquals("Hello Ricardo", checkpoint.generateResult())
        assertEquals(Instant.EPOCH, checkpoint.executionStartTime)
        assertEquals(Instant.EPOCH, checkpoint.executionEndTime)
        assertFalse(checkpoint.isTransient)
        assertFalse(checkpoint.isResultIsAsync)
    }

    @Test
    fun testInflightCancellationStopsBeforeAction() {
        // In-flight cancellation: with the feature enabled and the workflow CANCELLED while
        // executing, executeAction stops before starting a not-yet-checkpointed action.
        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val cancelledExecutor =
            ActionExecutor(
                mockWorkflowStore,
                CheckpointMode.EVENTUAL_CHECKPOINT,
                metrics,
                errorMapper,
                mockEventPublisher,
                mockExecutionMetricsCollector,
                mockTracer,
                ContextPropagator.NOOP,
                InMemoryFeatureGate(mapOf(FeatureGate.Keys.INFLIGHT_CANCELLATION_CHECKPOINTS to true)),
            )
        whenever(mockWorkflowStore.getWorkflow(any())).thenReturn(
            Option.of(
                TestUtils.getWorkflowInstance().toBuilder()
                    .status(WorkflowInstance.Status.CANCELLED)
                    .build(),
            ),
        )
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        assertThrows(WorkflowCancelledException::class.java) {
            cancelledExecutor.executeAction(req)
        }
        // The action never ran: no iteration increment and no checkpoint recorded.
        assertEquals(0, executionContext.getActionIteration(DemoActions::class.java, "hello"))
        assertEquals(0, executionContext.dirtyCheckpoints.size())
    }

    @Test
    fun testInflightCancellationDisabledByDefaultRunsAction() {
        // Opt-in guard: with no FeatureGate (today's default) the action runs normally even if the
        // store reports CANCELLED — proving the new behavior is gated and backward compatible.
        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        whenever(mockWorkflowStore.getWorkflow(any())).thenReturn(
            Option.of(
                TestUtils.getWorkflowInstance().toBuilder()
                    .status(WorkflowInstance.Status.CANCELLED)
                    .build(),
            ),
        )
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        // `actionExecutor` (from setup) was built with no FeatureGate, so the feature is disabled.
        val result = actionExecutor.executeAction(req)
        assertEquals("Hello Ricardo", result)
    }

    @Test
    fun testInflightCancellationDisabledByGateRunsAction() {
        // Realistic opt-out path: a real FeatureGate is present but the key is OFF (a non-adopting
        // embedder). The action runs normally even though the store reports CANCELLED — proving the
        // gate, not just the null default, controls the behavior.
        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val gate = mock<FeatureGate>()
        whenever(gate.isEnabled(FeatureGate.Keys.INFLIGHT_CANCELLATION_CHECKPOINTS)).thenReturn(false)
        val gatedOffExecutor =
            ActionExecutor(
                mockWorkflowStore,
                CheckpointMode.EVENTUAL_CHECKPOINT,
                metrics,
                errorMapper,
                mockEventPublisher,
                mockExecutionMetricsCollector,
                mockTracer,
                ContextPropagator.NOOP,
                gate,
            )
        whenever(mockWorkflowStore.getWorkflow(any())).thenReturn(
            Option.of(
                TestUtils.getWorkflowInstance().toBuilder()
                    .status(WorkflowInstance.Status.CANCELLED)
                    .build(),
            ),
        )
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        val result = gatedOffExecutor.executeAction(req)
        assertEquals("Hello Ricardo", result)
    }

    @Test
    fun testCompensableActionEnforcesCheckpointing() {
        // This test verifies that actions with @Compensate methods are always checkpointed,
        // even when configured with NO_CHECKPOINT mode
        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)

        val method =
            DemoActions::class.java.declaredMethods.first {
                it.name == "compensableActionNoCheckpoint"
            }

        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("test-data"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()

        val result = actionExecutor.executeAction(req)
        assertEquals("Processed test-data", result)

        // Verify that despite NO_CHECKPOINT mode, the action was checkpointed because it has
        // compensation
        assertEquals(1, executionContext.dirtyCheckpoints.size())
        val checkpoint = executionContext.dirtyCheckpoints.get(0)
        assertEquals("Processed test-data", checkpoint.generateResult())
        assertEquals(Instant.EPOCH, checkpoint.executionStartTime)
        assertEquals(Instant.EPOCH, checkpoint.executionEndTime)
        assertFalse(checkpoint.isTransient)
        assertFalse(checkpoint.isResultIsAsync)
    }

    @Test
    fun testCompensableActionWithDefaultCheckpointMode() {
        // This test verifies that actions with @Compensate methods use the default checkpoint mode
        // when configured with DEFAULT mode, and if default is NO_CHECKPOINT, it gets overridden
        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)

        val method =
            DemoActions::class.java.declaredMethods.first {
                it.name == "compensableActionDefaultCheckpoint"
            }

        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("test-data"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()

        val result = actionExecutor.executeAction(req)
        assertEquals("Default processed test-data", result)

        // Verify that the action was checkpointed since it has compensation and default mode
        // is EVENTUAL_CHECKPOINT (set in test setup)
        assertEquals(1, executionContext.dirtyCheckpoints.size())
        val checkpoint = executionContext.dirtyCheckpoints.get(0)
        assertEquals("Default processed test-data", checkpoint.generateResult())
        assertEquals(Instant.EPOCH, checkpoint.executionStartTime)
        assertEquals(Instant.EPOCH, checkpoint.executionEndTime)
        assertFalse(checkpoint.isTransient)
        assertFalse(checkpoint.isResultIsAsync)
    }

    @Test
    fun testCompensableActionWithDefaultCheckpointModeWhenDefaultIsNoCheckpoint() {
        // This test verifies that when default checkpoint mode is NO_CHECKPOINT,
        // compensable actions still get checkpointed by overriding to EVENTUAL_CHECKPOINT
        actionExecutor =
            ActionExecutor(
                mockWorkflowStore,
                CheckpointMode.NO_CHECKPOINT,
                metrics,
                errorMapper,
                mockEventPublisher,
                mockExecutionMetricsCollector,
                mockTracer,
                ContextPropagator.NOOP,
            null,
            )

        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)

        val method =
            DemoActions::class.java.declaredMethods.first {
                it.name == "compensableActionDefaultCheckpoint"
            }

        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("test-data"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()

        val result = actionExecutor.executeAction(req)
        assertEquals("Default processed test-data", result)

        // Verify that despite default NO_CHECKPOINT mode, the action was checkpointed
        // because it has compensation
        assertEquals(1, executionContext.dirtyCheckpoints.size())
        val checkpoint = executionContext.dirtyCheckpoints.get(0)
        assertEquals("Default processed test-data", checkpoint.generateResult())
        assertEquals(Instant.EPOCH, checkpoint.executionStartTime)
        assertEquals(Instant.EPOCH, checkpoint.executionEndTime)
        assertFalse(checkpoint.isTransient)
        assertFalse(checkpoint.isResultIsAsync)
    }

    @Test
    fun testExecuteHappyPathWhenCheckpointAreImmediatelyPersisted() {
        actionExecutor =
            ActionExecutor(
                mockWorkflowStore,
                CheckpointMode.IMMEDIATE_CHECKPOINT,
                metrics,
                errorMapper,
                mockEventPublisher,
                mockExecutionMetricsCollector,
                mockTracer,
                ContextPropagator.NOOP,
            null,
            )
        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        val result = actionExecutor.executeAction(req)
        assertEquals("Hello Ricardo", result)
        assertEquals(1, executionContext.getActionIteration(DemoActions::class.java, "hello"))
        assertEquals(0, executionContext.dirtyCheckpoints.size())
        // Inspect the arguments passed to the storeActionCheckpoints method
        val captor = argumentCaptor<List<ActionCheckpoint>>()
        verify(mockWorkflowStore, times(1))
            .storeActionCheckpoints(eq(executionContext.workflow.workflowId), captor.capture())
        assertEquals(1, captor.firstValue.size())
        val checkpoint = captor.firstValue.get(0)
        assertEquals("Hello Ricardo", checkpoint.generateResult())
        assertEquals(Instant.EPOCH, checkpoint.executionStartTime)
        assertEquals(Instant.EPOCH, checkpoint.executionEndTime)
        assertFalse(checkpoint.isTransient)
        assertFalse(checkpoint.isResultIsAsync)
    }

    @Test
    fun testExecuteHappyPathWhenCheckpointAreImmediatelyPersistedAndCheckpointFails() {
        actionExecutor =
            ActionExecutor(
                mockWorkflowStore,
                CheckpointMode.IMMEDIATE_CHECKPOINT,
                metrics,
                errorMapper,
                mockEventPublisher,
                mockExecutionMetricsCollector,
                mockTracer,
                ContextPropagator.NOOP,
            null,
            )
        val executionContext = newExecContext()
        whenever(mockWorkflowStore.storeActionCheckpoints(any(), any()))
            .thenThrow(RuntimeException("Timeout!"))
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        val error = assertThrows(RetryableError::class.java) { actionExecutor.executeAction(req) }
        assertTrue(error.cause!!.message!!.contains("Timeout!"))
        assertTrue(error is RetryableError)
        assertTrue((error as RetryableError).retryStrategy.get().isPersistentRetry)

        // Inspect the arguments passed to the storeActionCheckpoints method
        val captor = argumentCaptor<List<ActionCheckpoint>>()
        verify(mockWorkflowStore, times(1))
            .storeActionCheckpoints(eq(executionContext.workflow.workflowId), any())
    }

    @Test
    fun testExecuteHappyPathWhenCheckpointAreImmediatelyPersistedViaCheckpointModeOverride() {
        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val method =
            DemoActions::class.java.declaredMethods.first {
                it.name == "helloImmediateCheckpoint"
            }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        val result = actionExecutor.executeAction(req)
        assertEquals("Hello Ricardo", result)
        assertEquals(
            1,
            executionContext.getActionIteration(DemoActions::class.java, "helloImmediateCheckpoint"),
        )
        assertEquals(0, executionContext.dirtyCheckpoints.size())
        // Inspect the arguments passed to the storeActionCheckpoints method
        val captor = argumentCaptor<List<ActionCheckpoint>>()
        verify(mockWorkflowStore, times(1))
            .storeActionCheckpoints(eq(executionContext.workflow.workflowId), captor.capture())
        assertEquals(1, captor.firstValue.size())
        val checkpoint = captor.firstValue.get(0)
        assertEquals("Hello Ricardo", checkpoint.generateResult())
        assertEquals(Instant.EPOCH, checkpoint.executionStartTime)
        assertEquals(Instant.EPOCH, checkpoint.executionEndTime)
        assertFalse(checkpoint.isTransient)
        assertFalse(checkpoint.isResultIsAsync)
    }

    @Test
    @Throws(Exception::class)
    fun testExecuteHappyPathWhenAsyncResponse() {
        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val method = DemoActions::class.java.declaredMethods.first { it.name == "helloAsync" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        val result = actionExecutor.executeAction(req)
        @Suppress("UNCHECKED_CAST")
        assertEquals("Hello Ricardo", (result as CompletableFuture<String>).get())
        assertEquals(1, executionContext.getActionIteration(DemoActions::class.java, "helloAsync"))
        assertEquals(1, executionContext.dirtyCheckpoints.size())
        val checkpoint = executionContext.dirtyCheckpoints.get(0)
        @Suppress("UNCHECKED_CAST")
        val checkpointResult = (checkpoint.generateResult() as CompletableFuture<String>).get()
        assertEquals("Hello Ricardo", checkpointResult)
        assertEquals(Instant.EPOCH, checkpoint.executionStartTime)
        assertEquals(Instant.EPOCH, checkpoint.executionEndTime)
        assertFalse(checkpoint.isTransient)
        assertTrue(checkpoint.isResultIsAsync)
    }

    @Test
    fun testExecuteActionWhenCheckpointExistsAndIsSuccess() {
        val executionContext =
            newExecContext().toBuilder()
                .workflow(workflow)
                .actionCheckpoints(
                    List.of(
                        ActionCheckpoint.builder()
                            .checkpointTag(checkpointTag)
                            .executionEndTime(Instant.EPOCH)
                            .executionStartTime(Instant.EPOCH)
                            .result(Either.right("Hello Ricky"))
                            .isTransient(false)
                            .build()
                    )
                )
                .build()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        val result = actionExecutor.executeAction(req)
        assertEquals("Hello Ricky", result)
        assertEquals(1, executionContext.getActionIteration(DemoActions::class.java, "hello"))
        // Executing the same method again should NOT pick the checkpoint and should instead run the
        // action again.
        val result2 = actionExecutor.executeAction(req)
        assertEquals("Hello Ricardo", result2)
        assertEquals(2, executionContext.getActionIteration(DemoActions::class.java, "hello"))
    }

    @Test
    @Throws(Exception::class)
    fun testExecuteActionWhenCheckpointExistsAndIsSuccessAndIsAsync() {
        val executionContext =
            newExecContext().toBuilder()
                .workflow(workflow)
                .actionCheckpoints(
                    List.of(
                        ActionCheckpoint.builder()
                            .checkpointTag(checkpointTag.toBuilder().actionMethod("helloAsync").build())
                            .executionEndTime(Instant.EPOCH)
                            .executionStartTime(Instant.EPOCH)
                            .result(Either.right("Hello Ricky"))
                            .isTransient(false)
                            .resultIsAsync(true)
                            .build()
                    )
                )
                .build()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val method = DemoActions::class.java.declaredMethods.first { it.name == "helloAsync" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        val result = actionExecutor.executeAction(req)
        assertTrue(result is CompletableFuture<*>)
        @Suppress("UNCHECKED_CAST")
        assertEquals("Hello Ricky", (result as CompletableFuture<String>).get())
        assertEquals(1, executionContext.getActionIteration(DemoActions::class.java, "helloAsync"))
        // Executing the same method again should NOT pick the checkpoint and should instead run the
        // action again.
        val result2 = actionExecutor.executeAction(req)
        @Suppress("UNCHECKED_CAST")
        assertEquals("Hello Ricardo", (result2 as CompletableFuture<String>).get())
        assertEquals(2, executionContext.getActionIteration(DemoActions::class.java, "helloAsync"))
    }

    @Test
    fun testGetActionCheckpoint() {
        val executionContext =
            newExecContext().toBuilder()
                .workflow(workflow)
                .actionCheckpoints(
                    List.of(
                        ActionCheckpoint.builder()
                            .checkpointTag(checkpointTag)
                            .executionEndTime(Instant.EPOCH)
                            .executionStartTime(Instant.EPOCH)
                            .result(Either.right("Hello Ricky"))
                            .isTransient(false)
                            .build()
                    )
                )
                .build()
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        val checkpoint = req.getActionCheckpoint()
        assertTrue(checkpoint.isDefined)
        // Transient checkpoints should not be picked.
        val transientCheckpoint =
            ActionCheckpoint.builder()
                .checkpointTag(checkpointTag)
                .executionEndTime(Instant.EPOCH)
                .executionStartTime(Instant.EPOCH)
                .result(Either.left(RuntimeException("Transient error")))
                .isTransient(true)
                .build()
        val transientContext =
            executionContext.toBuilder().actionCheckpoints(List.of(transientCheckpoint)).build()
        val req2 = req.toBuilder().executionContext(transientContext).build()
        val checkpoint2 = req2.getActionCheckpoint()
        assertFalse(checkpoint2.isDefined)
        // A checkpoint with a different method should not be picked.
        val req3 =
            req.toBuilder()
                .originalMethod(
                    DemoActions::class.java.declaredMethods.first { it.name == "invalidArgs" }
                )
                .build()
        val checkpoint3 = req3.getActionCheckpoint()
        assertFalse(checkpoint3.isDefined)
        // A checkpoint with a different class should not be picked.
        val req4 = req.toBuilder().actionObject(mock<Actions>()).build()
        val checkpoint4 = req4.getActionCheckpoint()
        assertFalse(checkpoint4.isDefined)
    }

    @Test
    fun testGetRetryCountCountsPositionalTransientCheckpoints() {
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        val transientCheckpoint = { ->
            ActionCheckpoint.builder()
                .checkpointTag(checkpointTag)
                .executionStartTime(Instant.EPOCH)
                .executionEndTime(Instant.EPOCH)
                .result(Either.left(RuntimeException("transient")))
                .isTransient(true)
                .build()
        }
        val executionContext =
            newExecContext().toBuilder()
                .actionCheckpoints(List.of(transientCheckpoint(), transientCheckpoint()))
                .build()
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 5))
                .build()
        assertEquals(2, req.getRetryCount())
    }

    // Regression for named-checkpoint retry accounting: a generic engine that routes every step
    // through one action distinguished only by named(...) stores its transient (failed) checkpoints
    // under a named tag. getRetryCount() must count those, otherwise a bounded RetryStrategy never
    // exhausts (the count stays 0 forever). Before the fix this returned 0.
    @Test
    fun testGetRetryCountCountsNamedTransientCheckpoints() {
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        val checkpointName = "my-step"
        val namedTag =
            CheckpointTag.builder()
                .workflowId(workflow.workflowId)
                .actionClass(DemoActions::class.java)
                .actionMethod("hello")
                .iteration(0)
                .checkpointName(checkpointName)
                .build()
        val transientCheckpoint = { ->
            ActionCheckpoint.builder()
                .checkpointTag(namedTag)
                .executionStartTime(Instant.EPOCH)
                .executionEndTime(Instant.EPOCH)
                .result(Either.left(RuntimeException("transient")))
                .isTransient(true)
                .build()
        }
        val executionContext =
            newExecContext().toBuilder()
                .actionCheckpoints(List.of(transientCheckpoint(), transientCheckpoint()))
                .build()
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 5))
                .checkpointName(checkpointName)
                .build()
        assertEquals(2, req.getRetryCount())
    }

    @Test
    fun testExecuteActionWhenCheckpointExistsAndIsFailure() {
        val workflow = TestUtils.getWorkflowInstance()
        val executionContext =
            newExecContext().toBuilder()
                .workflow(workflow)
                .actionCheckpoints(
                    List.of(
                        ActionCheckpoint.builder()
                            .checkpointTag(checkpointTag)
                            .executionEndTime(Instant.EPOCH)
                            .executionStartTime(Instant.EPOCH)
                            .result(Either.left(RuntimeException("error!")))
                            .isTransient(false)
                            .build()
                    )
                )
                .build()
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        val err = assertThrows(RuntimeException::class.java) { actionExecutor.executeAction(req) }
        assertEquals("error!", err.message)
        assertEquals(1, executionContext.getActionIteration(DemoActions::class.java, "hello"))
    }

    @Test
    fun testExecuteActionWhenCheckpointExistsAndIsFailedCompletableFuture() {
        val workflow = TestUtils.getWorkflowInstance()
        val executionContext =
            newExecContext().toBuilder()
                .workflow(workflow)
                .actionCheckpoints(
                    List.of(
                        ActionCheckpoint.builder()
                            .checkpointTag(checkpointTag)
                            .executionEndTime(Instant.EPOCH)
                            .executionStartTime(Instant.EPOCH)
                            .result(Either.left(RuntimeException("error!")))
                            .isTransient(false)
                            .resultIsAsync(true)
                            .build()
                    )
                )
                .build()
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        val result = actionExecutor.executeAction(req)
        assertTrue(result is CompletableFuture<*>)
        val err =
            assertThrows(CompletionException::class.java) { (result as CompletableFuture<*>).join() }
        assertEquals("error!", err.cause!!.message)
        assertEquals(1, executionContext.getActionIteration(DemoActions::class.java, "hello"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["methodThatThrows", "methodThatThrowsWrapped"])
    fun testExecuteActionWhenActionThrowsUnexpectedError_ErrorIsConvertedToNonRetryable(methodName: String) {
        val expectedErrorMessage = "error!"
        val expectedErrorClass = IllegalArgumentException::class.java
        val workflow = TestUtils.getWorkflowInstance()
        val executionContext =
            newExecContext().toBuilder().workflow(workflow).actionCheckpoints(List.empty()).build()
        val method = DemoActions::class.java.declaredMethods.first { it.name == methodName }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val err = assertThrows(NonRetryableError::class.java) { actionExecutor.executeAction(req) }
        assertEquals(expectedErrorClass.name, err.cause!!.type)
        assertEquals(expectedErrorMessage, err.cause!!.message)
        // Verify that a dirty checkpoint was correctly added
        assertEquals(1, executionContext.getActionIteration(DemoActions::class.java, methodName))
        val checkpoint = executionContext.dirtyCheckpoints.get(0)
        val checkpointError =
            assertThrows(NonRetryableError::class.java) { checkpoint.generateResult() }
        assertEquals(IllegalArgumentException::class.java.name, checkpointError.cause!!.type)
        assertFalse(checkpoint.isResultIsAsync)
        assertFalse(checkpoint.isTransient)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["methodThatThrowsUnexpectedAsync", "methodThatThrowsWrappedUnexpectedAsync"]
    )
    fun testExecuteActionWhenActionThrowsAsyncUnexpectedError_ErrorIsConvertedToNonRetryable(methodName: String) {
        val workflow = TestUtils.getWorkflowInstance()
        val executionContext =
            newExecContext().toBuilder().workflow(workflow).actionCheckpoints(List.empty()).build()
        val method = DemoActions::class.java.declaredMethods.first { it.name == methodName }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        @Suppress("UNCHECKED_CAST")
        val future = actionExecutor.executeAction(req) as CompletableFuture<Any>
        val err = assertThrows(CompletionException::class.java) { future.join() }
        assertTrue(err.cause is NonRetryableError)
        val cause = err.cause as NonRetryableError
        assertEquals(IllegalStateException::class.java.name, cause.cause!!.type)
        assertEquals("error!", cause.cause!!.message)
        // Verify that a dirty checkpoint was correctly added
        assertEquals(1, executionContext.getActionIteration(DemoActions::class.java, methodName))
        val checkpoint = executionContext.dirtyCheckpoints.get(0)
        var checkpointError: Throwable =
            assertThrows(CompletionException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (checkpoint.generateResult() as CompletableFuture<String>).join()
            }
        checkpointError = checkpointError.cause!!
        assertEquals(
            IllegalStateException::class.java.name,
            (checkpointError.cause as ApplicationError).type,
        )
        assertTrue(checkpoint.isResultIsAsync)
        assertFalse(checkpoint.isTransient)
    }

    @Test
    fun testExecuteActionWhenActionThrowsRetryableError() {
        val methodName = "methodThatThrowsRetryable"
        val workflow = TestUtils.getWorkflowInstance()
        val executionContext =
            newExecContext().toBuilder().workflow(workflow).actionCheckpoints(List.empty()).build()
        val method = DemoActions::class.java.declaredMethods.first { it.name == methodName }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val err = assertThrows(RetryableError::class.java) { actionExecutor.executeAction(req) }
        assertTrue(err.message!!.contains("error!"))
        // Verify that the retry strategy was correctly set
        assertEquals(FixedRetryStrategy(Duration.ofSeconds(1), 1), err.retryStrategy.get())
        // Verify that a dirty checkpoint was added
        assertEquals(0, executionContext.getActionIteration(DemoActions::class.java, methodName))
        val checkpoint = executionContext.dirtyCheckpoints.get(0)
        val checkpointError =
            assertThrows(RetryableError::class.java) { checkpoint.generateResult() }
        assertTrue(checkpointError.message!!.contains("error!"))
        assertFalse(checkpoint.isResultIsAsync)
        assertTrue(checkpoint.isTransient)
    }

    @Test
    fun testExecuteActionWhenActionThrowsRetryableErrorWithExplicitDelay() {
        val workflow = TestUtils.getWorkflowInstance()
        val executionContext =
            newExecContext().toBuilder().workflow(workflow).actionCheckpoints(List.empty()).build()
        val method =
            DemoActions::class.java.declaredMethods
                .first { it.name == "methodThatThrowsRetryableWithExplicitDelay" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val err = assertThrows(RetryableError::class.java) { actionExecutor.executeAction(req) }
        // The action's explicit delay wins over the configured strategy's delay...
        assertEquals(Duration.ofSeconds(42), err.getNextRetryDelay())
        // ...but the configured strategy is still attached, so exhaustion tracking is unaffected.
        assertEquals(FixedRetryStrategy(Duration.ofSeconds(1), 1), err.retryStrategy.get())
    }

    @Test
    fun testExecuteActionWhenActionThrowsRetryableErrorWithExplicitDelayAndRetriesExhausted() {
        val workflow = TestUtils.getWorkflowInstance()
        val executionContext =
            newExecContext().toBuilder().workflow(workflow).actionCheckpoints(List.empty()).build()
        val method =
            DemoActions::class.java.declaredMethods
                .first { it.name == "methodThatThrowsRetryableWithExplicitDelay" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 0))
                .build()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        // maxRetries=0 on the configured strategy still exhausts the action, even though the thrown
        // error carried its own explicit delay.
        val err = assertThrows(NonRetryableError::class.java) { actionExecutor.executeAction(req) }
        assertTrue(err.message!!.contains("has exhausted all retry attempts"))
    }

    @Test
    fun testExecuteActionWhenActionThrowsAsyncRetryableError() {
        val workflow = TestUtils.getWorkflowInstance()
        val executionContext =
            newExecContext().toBuilder().workflow(workflow).actionCheckpoints(List.empty()).build()
        val method =
            DemoActions::class.java.declaredMethods.first { it.name == "methodThatThrowsAsync" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        @Suppress("UNCHECKED_CAST")
        val result = actionExecutor.executeAction(req) as CompletableFuture<String>
        var err: Throwable = assertThrows(CompletionException::class.java) { result.join() }
        assertTrue(err.cause is RetryableError)
        err = err.cause!!
        assertEquals(IllegalStateException::class.java.name, (err.cause as ApplicationError).type)
        assertEquals("error!", err.cause!!.message)
        // Verify that a dirty read was correctly added
        assertEquals(
            0,
            executionContext.getActionIteration(DemoActions::class.java, "methodThatThrows"),
        )
        val checkpoint = executionContext.dirtyCheckpoints.get(0)
        var checkpointError: Throwable =
            assertThrows(CompletionException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (checkpoint.generateResult() as CompletableFuture<String>).join()
            }
        checkpointError = checkpointError.cause!!
        assertEquals(
            IllegalStateException::class.java.name,
            (checkpointError.cause as ApplicationError).type,
        )
        assertTrue(checkpoint.isResultIsAsync)
        assertTrue(checkpoint.isTransient)
    }

    @Test
    fun testExecuteActionWhenAllRetryAttemptsExhaustedThrowsNonRetryableError() {
        val workflow = TestUtils.getWorkflowInstance()
        val executionContext =
            newExecContext().toBuilder().workflow(workflow).actionCheckpoints(List.empty()).build()
        val method =
            DemoActions::class.java.declaredMethods.first { it.name == "methodThatThrowsRetryable" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 0))
                .build()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val err = assertThrows(NonRetryableError::class.java) { actionExecutor.executeAction(req) }
        assertTrue(err.message!!.contains("has exhausted all retry attempts"))
        assertEquals(RetryableError::class.java.name, err.cause!!.type)
        assertEquals("error!", err.cause!!.message)
        // Verify that a dirty read was correctly added
        assertEquals(
            1,
            executionContext.getActionIteration(DemoActions::class.java, "methodThatThrowsRetryable"),
        )
        val checkpoint = executionContext.dirtyCheckpoints.get(0)
        assertThrows(NonRetryableError::class.java) { checkpoint.generateResult() }
        assertFalse(checkpoint.isResultIsAsync)
        assertFalse(checkpoint.isTransient)
    }

    @Test
    fun testExecuteActionWhenAllRetryAttemptsExhaustedAndPersistentStrategy() {
        val workflow = TestUtils.getWorkflowInstance()
        val executionContext =
            newExecContext().toBuilder().workflow(workflow).actionCheckpoints(List.empty()).build()
        val method =
            DemoActions::class.java.declaredMethods.first { it.name == "methodThatThrowsRetryable" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .executionContext(executionContext)
                .retryStrategy(
                    PersistentRetryStrategy.of(FixedRetryStrategy(Duration.ofSeconds(1), 0))
                )
                .build()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val err =
            assertThrows(PersistentRetryableError::class.java) { actionExecutor.executeAction(req) }
        assertTrue(err.message!!.contains("error!"))
        assertEquals(RetryableError::class.java.name, err.cause!!.type)
        assertEquals("error!", err.cause!!.message)
        // Verify that no dirty read was added
        assertEquals(
            0,
            executionContext.getActionIteration(DemoActions::class.java, "methodThatThrowsRetryable"),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["methodThatThrowsNonRetryable", "methodThatThrowsIdlNonRetryable"])
    fun testExecuteActionWhenActionThrowsNonRetryableError(methodName: String) {
        val workflow = TestUtils.getWorkflowInstance()
        val executionContext =
            newExecContext().toBuilder().workflow(workflow).actionCheckpoints(List.empty()).build()
        val method = DemoActions::class.java.declaredMethods.first { it.name == methodName }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val err = assertThrows(NonRetryableError::class.java) { actionExecutor.executeAction(req) }
        assertNotNull(err)
        assertTrue(err.message!!.contains("error!"))
    }

    @Test
    fun testExecuteActionWhenInvalidArgIsPassed() {
        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>(1))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        val err = assertThrows(NonRetryableError::class.java) { actionExecutor.executeAction(req) }
        assertTrue(err.message!!.contains("action method hello was passed an invalid argument"))
    }

    @Test
    fun testExecuteWhenActionMethodIsNotAccesibleStillWorks() {
        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val method = DemoActions::class.java.declaredMethods.first { it.name == "privateMethod" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        actionExecutor.executeAction(req)
    }

    @Test
    @Throws(Exception::class)
    fun testActionErrorGetsClassifiedCorrectly() {
        val workflow = TestUtils.getWorkflowInstance()
        val executionContext =
            newExecContext().toBuilder().workflow(workflow).actionCheckpoints(List.empty()).build()
        val method =
            DemoActions::class.java.getDeclaredMethod(
                "methodThatThrowsExceptionThatShouldBeMappedToRetryable"
            )
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val err = assertThrows(RetryableError::class.java) { actionExecutor.executeAction(req) }
        assertTrue(err.message!!.contains("error!"))
    }

    @Test
    fun testGetActionCheckpointForNonCompensationActionReturnsCheckpoint() {
        // Arrange
        val successfulCheckpoint =
            ActionCheckpoint.builder()
                .checkpointTag(checkpointTag)
                .result(Either.right("success"))
                .executionStartTime(Instant.EPOCH)
                .executionEndTime(Instant.EPOCH)
                .isTransient(false)
                .resultIsAsync(false)
                .build()
        val executionContext =
            newExecContext().toBuilder().actionCheckpoints(List.of(successfulCheckpoint)).build()

        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }

        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .isCompensatingAction(false) // Regular action, not compensating
                .build()

        // Act
        val result = req.getActionCheckpoint()

        // Assert
        assertTrue(result.isDefined)
        assertEquals("success", result.get().generateResult())
    }

    @Test
    fun testGetActionCheckpointForCompensationActionWithSuccessfulCheckpointReturnsCheckpoint() {
        // Arrange
        val successfulCheckpoint =
            ActionCheckpoint.builder()
                .checkpointTag(checkpointTag)
                .result(Either.right("success"))
                .executionStartTime(Instant.EPOCH)
                .executionEndTime(Instant.EPOCH)
                .isTransient(false)
                .resultIsAsync(false)
                .build()
        val executionContext =
            newExecContext().toBuilder().actionCheckpoints(List.of(successfulCheckpoint)).build()

        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }

        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .isCompensatingAction(true) // Compensating action
                .build()

        // Act
        val result = req.getActionCheckpoint()

        // Assert
        assertTrue(result.isDefined)
        assertEquals("success", result.get().generateResult())
    }

    @Test
    fun testGetActionCheckpointForCompensationActionWithTransientErrorReturnsNone() {
        // Arrange - Transient checkpoints should not be returned even for compensating actions
        // because ExecutionContext.getActionCheckpoint() filters them out
        val transientErrorCheckpoint =
            ActionCheckpoint.builder()
                .checkpointTag(checkpointTag)
                .result(Either.left(RetryableError("transient error")))
                .executionStartTime(Instant.EPOCH)
                .executionEndTime(Instant.EPOCH)
                .isTransient(true) // Transient error
                .resultIsAsync(false)
                .build()
        val executionContext =
            newExecContext().toBuilder()
                .actionCheckpoints(List.of(transientErrorCheckpoint))
                .build()

        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }

        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .isCompensatingAction(true) // Compensating action
                .build()

        // Act
        val result = req.getActionCheckpoint()

        // Assert - Transient checkpoints are filtered out by ExecutionContext.getActionCheckpoint()
        assertFalse(result.isDefined)
    }

    @Test
    fun testGetActionCheckpointForCompensationActionWithNonTransientErrorReturnsNone() {
        // Arrange
        val nonTransientErrorCheckpoint =
            ActionCheckpoint.builder()
                .checkpointTag(checkpointTag)
                .result(Either.left(NonRetryableError("non-retryable error")))
                .executionStartTime(Instant.EPOCH)
                .executionEndTime(Instant.EPOCH)
                .isTransient(false) // Non-transient error
                .resultIsAsync(false)
                .build()
        val executionContext =
            newExecContext().toBuilder()
                .actionCheckpoints(List.of(nonTransientErrorCheckpoint))
                .build()

        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }

        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .isCompensatingAction(true) // Compensating action
                .build()

        // Act
        val result = req.getActionCheckpoint()

        // Assert
        assertFalse(result.isDefined)
    }

    @Test
    fun testGetActionCheckpointForCompensationActionWithNoCheckpointReturnsNone() {
        // Arrange
        val executionContext = newExecContext()
        // No checkpoints added to execution context

        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }

        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .isCompensatingAction(true) // Compensating action
                .build()

        // Act
        val result = req.getActionCheckpoint()

        // Assert
        assertFalse(result.isDefined)
    }

    @Test
    fun testCompensateActionWithZeroParametersDoesNotThrowNPE() {
        // This test verifies that a compensable execute action with zero parameters
        // doesn't throw NPE when getFirstArgOrNull() is called
        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)

        val method =
            DemoActions::class.java.declaredMethods.first { it.name == "executeWithNoParams" }

        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(null) // Zero parameters means arg is null
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .isCompensatingAction(false)
                .build()

        // This should not throw NPE
        val result = actionExecutor.executeAction(req)
        assertEquals("No params executed", result)

        // Verify checkpoint was created with null input (no parameters to store)
        assertEquals(1, executionContext.dirtyCheckpoints.size())
        val checkpoint = executionContext.dirtyCheckpoints.get(0)
        assertNull(checkpoint.input) // Input should be null for zero-param action
    }

    private class DemoActions : Actions() {
        @Execute
        fun hello(name: String): String = "Hello $name"

        @Execute(checkpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT)
        fun helloImmediateCheckpoint(name: String): String = "Hello $name"

        @Execute
        fun helloAsync(name: String): CompletableFuture<String> = CompletableFuture.completedFuture("Hello $name")

        @Execute
        fun invalidArgs(
            one: String,
            two: String
        ) = Unit

        fun notActionMethod(one: String) = Unit

        @Execute
        fun methodThatThrows() {
            throw IllegalArgumentException("error!")
        }

        @Execute(checkpointMode = CheckpointMode.NO_CHECKPOINT)
        fun compensableActionNoCheckpoint(data: String): String = "Processed $data"

        @Compensate(forExecute = "compensableActionNoCheckpoint")
        fun compensateProcessing(data: String) {
            // Compensation logic would go here
        }

        @Execute(checkpointMode = CheckpointMode.DEFAULT)
        fun compensableActionDefaultCheckpoint(data: String): String = "Default processed $data"

        @Compensate(forExecute = "compensableActionDefaultCheckpoint")
        fun compensateDefaultProcessing(data: String) {
            // Compensation logic would go here
        }

        @Execute
        fun executeWithNoParams(): String = "No params executed"

        @Compensate(forExecute = "executeWithNoParams")
        fun compensateWithNoParams() {
            // Compensation logic for zero-parameter action
        }

        @Execute
        fun methodThatThrowsWrapped() {
            val cause: Throwable = IllegalArgumentException("error!")
            throw CompletionException(cause)
        }

        @Execute
        fun methodThatThrowsIdlNonRetryable() {
            throw TestServiceException("idl error!")
        }

        @Execute
        fun methodThatThrowsRetryable() {
            throw RetryableError("error!")
        }

        @Execute
        fun methodThatThrowsRetryableWithExplicitDelay() {
            throw RetryableError("error!", null, Duration.ofSeconds(42))
        }

        @Execute
        fun methodThatThrowsAsync(): CompletableFuture<String> {
            val future = CompletableFuture<String>()
            future.completeExceptionally(
                RetryableError("error!", IllegalStateException("error!"))
            )
            return future
        }

        @Execute
        fun methodThatThrowsUnexpectedAsync(): CompletableFuture<String> {
            val future = CompletableFuture<String>()
            future.completeExceptionally(IllegalStateException("error!"))
            return future
        }

        @Execute
        fun methodThatThrowsWrappedUnexpectedAsync(): CompletableFuture<String> {
            val future = CompletableFuture<String>()
            future.completeExceptionally(CompletionException(IllegalStateException("error!")))
            return future
        }

        @Execute
        fun methodThatThrowsNonRetryable() {
            throw NonRetryableError("non retryable error!")
        }

        @Execute
        private fun privateMethod() = Unit

        @Execute
        private fun methodThatThrowsExceptionThatShouldBeMappedToRetryable() {
            throw CompletionException(
                java.util.concurrent.TimeoutException("error!")
            )
        }

        val customClassifier: ExceptionClassifier =
            object : ExceptionClassifier {
                override fun isRetryable(throwable: Throwable): Boolean {
                    // Treat IllegalStateException as non-retryable
                    return throwable !is IllegalStateException
                }
            }

        @Execute(exceptionClassifier = "customClassifier")
        fun methodWithCustomClassifier() {
            throw IllegalStateException("This should be non-retryable with custom classifier")
        }

        @Execute(exceptionClassifier = "customClassifier")
        fun methodWithCustomClassifierThatTreatsAsRetryable() {
            throw RuntimeException("This should be retryable with custom classifier")
        }

        @Execute(exceptionClassifier = "customClassifier")
        fun methodWithCustomClassifierAsync(): CompletableFuture<String> {
            val future = CompletableFuture<String>()
            future.completeExceptionally(IllegalStateException("Async should be non-retryable"))
            return future
        }

        @Execute
        fun methodWithoutCustomClassifier() {
            throw IllegalStateException("Should use global classifier")
        }

        /**
         * OSS-safe stand-in for a service-layer runtime exception. Used by
         * [methodThatThrowsIdlNonRetryable] to verify that an arbitrary unchecked exception thrown
         * by an action is classified as non-retryable by the default classifier.
         */
        private class TestServiceException(
            message: String
        ) : RuntimeException(message)
    }

    @Test
    @Throws(Exception::class)
    fun testActionWithCustomExceptionClassifierTreatExceptionAsNonRetryable() {
        val testActions = DemoActions()
        val method = testActions.javaClass.getMethod("methodWithCustomClassifier")

        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val request =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(testActions)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(null)
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .exceptionClassifier(testActions.customClassifier)
                .build()

        val error =
            assertThrows(NonRetryableError::class.java) { actionExecutor.executeAction(request) }
        assertTrue(
            error.message!!
                .contains(
                    "threw an unexpected exception: This should be non-retryable with custom" +
                        " classifier"
                )
        )
    }

    @Test
    @Throws(Exception::class)
    fun testActionWithCustomExceptionClassifierTreatExceptionAsRetryable() {
        val testActions = DemoActions()
        val method =
            testActions.javaClass.getMethod("methodWithCustomClassifierThatTreatsAsRetryable")

        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val request =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(testActions)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(null)
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .exceptionClassifier(testActions.customClassifier)
                .build()

        val error =
            assertThrows(RetryableError::class.java) { actionExecutor.executeAction(request) }
        assertTrue(error.message!!.contains("This should be retryable with custom classifier"))
    }

    @Test
    @Throws(Exception::class)
    fun testActionWithCustomExceptionClassifierAsync() {
        val testActions = DemoActions()
        val method = testActions.javaClass.getMethod("methodWithCustomClassifierAsync")

        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val request =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(testActions)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(null)
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .exceptionClassifier(testActions.customClassifier)
                .build()

        val future = actionExecutor.executeAction(request) as CompletableFuture<*>
        val error = assertThrows(CompletionException::class.java) { future.join() }
        assertTrue(error.cause is NonRetryableError)
        assertTrue(error.cause!!.message!!.contains("Async should be non-retryable"))
    }

    @Test
    @Throws(Exception::class)
    fun testActionWithoutCustomClassifierUsesGlobalClassifier() {
        val testActions = DemoActions()
        val method = testActions.javaClass.getMethod("methodWithoutCustomClassifier")

        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val request =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(testActions)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(null)
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .exceptionClassifier(null) // No custom classifier - should use global
                .build()

        // The global DefaultExceptionClassifier treats IllegalStateException as non-retryable
        val error =
            assertThrows(NonRetryableError::class.java) { actionExecutor.executeAction(request) }
        assertTrue(error.message!!.contains("Should use global classifier"))
    }

    // --- Named checkpoint execution tests ---

    @Test
    fun testExecuteNamedAction_freshExecutionDoesNotIncrementIteration() {
        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .checkpointName("greet-ricardo")
                .build()
        val result = actionExecutor.executeAction(req)
        assertEquals("Hello Ricardo", result)
        // Named checkpoints MUST NOT bump the positional counter — they use name as identity.
        assertEquals(0, executionContext.getActionIteration(DemoActions::class.java, "hello"))
        assertTrue(executionContext.isNamedCheckpointConsumed("greet-ricardo"))
        assertEquals(1, executionContext.dirtyCheckpoints.size())
        val checkpoint = executionContext.dirtyCheckpoints.get(0)
        assertEquals("greet-ricardo", checkpoint.checkpointTag.checkpointName)
    }

    @Test
    fun testReplaySucceeds_afterNewActionInsertedBetweenNamedCheckpoints() {
        // Simulate a workflow edit where a new action is inserted BEFORE a named checkpoint.
        // Under positional matching the iteration count would shift and break replay; under
        // named matching, the stored checkpoint still matches by name and replay succeeds.
        val storedNamedCheckpoint =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId(workflow.workflowId)
                        .actionClass(DemoActions::class.java)
                        .actionMethod("hello")
                        .iteration(0)
                        .checkpointName("greet-step")
                        .build()
                )
                .executionEndTime(Instant.EPOCH)
                .executionStartTime(Instant.EPOCH)
                .result(Either.right("Hello Original"))
                .isTransient(false)
                .build()
        // Pretend an earlier action has already run and bumped the iteration counter (new insertion
        // before the named checkpoint).
        val executionContext =
            newExecContext().toBuilder()
                .workflow(workflow)
                .actionCheckpoints(List.of(storedNamedCheckpoint))
                .build()
        executionContext.incrementActionIteration(DemoActions::class.java, "hello")

        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("New"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .checkpointName("greet-step")
                .build()
        val result = actionExecutor.executeAction(req)
        // Replay returns the stored value even though the positional iteration has shifted.
        assertEquals("Hello Original", result)
        assertTrue(executionContext.isNamedCheckpointConsumed("greet-step"))
    }

    @Test
    fun testReplaySucceeds_afterReorderingOfSameClassMethodCalls() {
        // Two checkpoints for the same (class, method), distinguished only by name. The workflow
        // author has swapped their order in code; positional iteration would misalign, but names
        // preserve correct result mapping.
        val stepA =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId(workflow.workflowId)
                        .actionClass(DemoActions::class.java)
                        .actionMethod("hello")
                        .iteration(0)
                        .checkpointName("step-a")
                        .build()
                )
                .executionEndTime(Instant.EPOCH)
                .executionStartTime(Instant.EPOCH)
                .result(Either.right("A-result"))
                .isTransient(false)
                .build()
        val stepB =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId(workflow.workflowId)
                        .actionClass(DemoActions::class.java)
                        .actionMethod("hello")
                        .iteration(0)
                        .checkpointName("step-b")
                        .build()
                )
                .executionEndTime(Instant.EPOCH)
                .executionStartTime(Instant.EPOCH)
                .result(Either.right("B-result"))
                .isTransient(false)
                .build()
        val executionContext =
            newExecContext().toBuilder()
                .workflow(workflow)
                .actionCheckpoints(List.of(stepA, stepB))
                .build()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        // Invoke in reversed order vs. the stored order — names guarantee correct binding.
        val reqB =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("ignored"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .checkpointName("step-b")
                .build()
        assertEquals("B-result", actionExecutor.executeAction(reqB))
        val reqA = reqB.toBuilder().checkpointName("step-a").build()
        assertEquals("A-result", actionExecutor.executeAction(reqA))
    }

    @Test
    fun testReplaySucceeds_withRemovedNamedCheckpointIgnored() {
        // A previously persisted named checkpoint ("removed-step") is no longer called by the current
        // workflow code. Replay of the remaining named checkpoint ("kept-step") still succeeds;
        // the orphaned stored checkpoint is harmlessly ignored.
        val removed =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId(workflow.workflowId)
                        .actionClass(DemoActions::class.java)
                        .actionMethod("hello")
                        .iteration(0)
                        .checkpointName("removed-step")
                        .build()
                )
                .executionEndTime(Instant.EPOCH)
                .executionStartTime(Instant.EPOCH)
                .result(Either.right("old"))
                .isTransient(false)
                .build()
        val kept =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId(workflow.workflowId)
                        .actionClass(DemoActions::class.java)
                        .actionMethod("hello")
                        .iteration(0)
                        .checkpointName("kept-step")
                        .build()
                )
                .executionEndTime(Instant.EPOCH)
                .executionStartTime(Instant.EPOCH)
                .result(Either.right("kept"))
                .isTransient(false)
                .build()
        val executionContext =
            newExecContext().toBuilder()
                .workflow(workflow)
                .actionCheckpoints(List.of(removed, kept))
                .build()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("whatever"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .checkpointName("kept-step")
                .build()
        assertEquals("kept", actionExecutor.executeAction(req))
        assertTrue(executionContext.isNamedCheckpointConsumed("kept-step"))
        assertFalse(executionContext.isNamedCheckpointConsumed("removed-step"))
    }

    @Test
    fun testDuplicateCheckpointName_failsFast() {
        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .checkpointName("dup-name")
                .build()
        assertEquals("Hello Ricardo", actionExecutor.executeAction(req))
        // Second invocation with the same name on the same context must fail fast.
        val dup =
            assertThrows(IllegalStateException::class.java) { actionExecutor.executeAction(req) }
        assertTrue(dup.message!!.contains("Duplicate checkpoint name 'dup-name'"))
    }

    @Test
    fun testUnnamedAction_continuesToIncrementIteration() {
        // Regression guard: unnamed checkpoints MUST retain their positional iteration behavior.
        val executionContext = newExecContext()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val method = DemoActions::class.java.declaredMethods.first { it.name == "hello" }
        val req =
            ActionExecutor.ExecuteActionRequest.builder()
                .actionObject(actionMap[DemoActions::class.java]!!)
                .proxyMethod(method)
                .originalMethod(method)
                .arg(arrayOf<Any?>("Ricardo"))
                .executionContext(executionContext)
                .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
                .build()
        actionExecutor.executeAction(req)
        actionExecutor.executeAction(req)
        assertEquals(2, executionContext.getActionIteration(DemoActions::class.java, "hello"))
    }
}
