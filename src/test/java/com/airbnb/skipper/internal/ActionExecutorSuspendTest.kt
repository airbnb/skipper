package com.airbnb.skipper.internal

import com.airbnb.skipper.Actions
import com.airbnb.skipper.CheckpointMode
import com.airbnb.skipper.ContextPropagator
import com.airbnb.skipper.EventPublisher
import com.airbnb.skipper.Execute
import com.airbnb.skipper.ExecutionMetricsCollector
import com.airbnb.skipper.FixedRetryStrategy
import com.airbnb.skipper.NoOpMetrics
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.internal.storage.WorkflowStore
import io.opentracing.Scope
import io.opentracing.Span
import io.opentracing.Tracer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Tests verifying that [ActionExecutor.executeAction] correctly handles Kotlin suspend
 * `@Execute` methods. Suspend action methods go through [SuspendSupport.invokeSuspendFunctionAsync]
 * which returns a [CompletableFuture], entering the existing async handling path in ActionExecutor.
 *
 * These tests use real Kotlin suspend functions (not mocks) because
 * [SuspendSupport.isSuspendFunction] inspects the method's parameter types for a trailing
 * [kotlin.coroutines.Continuation].
 */
class ActionExecutorSuspendTest {
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var mockClock: Clock
    private lateinit var mockWorkflowStore: WorkflowStore
    private lateinit var mockEventPublisher: EventPublisher
    private lateinit var mockExecutionMetricsCollector: ExecutionMetricsCollector
    private lateinit var mockTracer: Tracer
    private lateinit var actionExecutor: ActionExecutor
    private val errorMapper = ActionErrorMapper(DefaultExceptionClassifier())
    private val metrics = NoOpMetrics.INSTANCE

    @BeforeEach
    fun setup() {
        mockClock = mock(Clock::class.java)
        mockWorkflowStore = mock(WorkflowStore::class.java)
        mockEventPublisher = mock(EventPublisher::class.java)
        mockExecutionMetricsCollector = mock(ExecutionMetricsCollector::class.java)
        mockTracer = mock(Tracer::class.java)

        val mockSpan = mock(Span::class.java)
        `when`(mockExecutionMetricsCollector.createActionSpan(org.mockito.ArgumentMatchers.any()))
            .thenReturn(mockSpan)
        `when`(mockTracer.activateSpan(org.mockito.ArgumentMatchers.any()))
            .thenReturn(mock(Scope::class.java))

        actionExecutor = ActionExecutor(
            mockWorkflowStore,
            CheckpointMode.EVENTUAL_CHECKPOINT,
            metrics,
            errorMapper,
            mockEventPublisher,
            mockExecutionMetricsCollector,
            mockTracer,
            ContextPropagator.NOOP,
            null,
            InFlightActions(),
        )
    }

    private fun newExecContext(): ExecutionContext =
        ExecutionContext.builder()
            .workflow(TestUtils.getWorkflowInstance())
            .clock(mockClock)
            .executorService(executorService)
            .build()

    // ── Suspend action classes (must be real Kotlin suspend funs) ────────────

    open class SuspendDemoActions : Actions() {
        @Execute
        suspend fun greet(name: String): String = "Hello $name"

        @Execute
        suspend fun failNonRetryable(msg: String): String {
            throw NonRetryableError(msg)
        }
    }

    // ── Tests ───────────────────────────────────────────────────────────────

    @Test
    fun `suspend action executeAction returns CompletableFuture`() {
        val executionContext = newExecContext()
        `when`(mockClock.instant()).thenReturn(Instant.EPOCH)

        val method = SuspendDemoActions::class.java.declaredMethods
            .first { it.name == "greet" }
        val actionObject = SuspendDemoActions()

        val req = ActionExecutor.ExecuteActionRequest.builder()
            .actionObject(actionObject)
            .proxyMethod(method)
            .originalMethod(method)
            .arg(arrayOf<Any>("World"))
            .executionContext(executionContext)
            .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
            .build()

        val result = actionExecutor.executeAction(req)

        // Suspend actions are invoked via invokeSuspendFunctionAsync which returns a
        // CompletableFuture. The ActionExecutor's async path wraps this into a new
        // CompletableFuture (via .exceptionally().whenComplete()), so the returned object
        // must be a CompletableFuture.
        assertThat(result).isInstanceOf(CompletableFuture::class.java)

        @Suppress("UNCHECKED_CAST")
        val future = result as CompletableFuture<String>
        assertThat(future.get()).isEqualTo("Hello World")
    }

    @Test
    fun `suspend action checkpoint created on future completion`() {
        val executionContext = newExecContext()
        `when`(mockClock.instant()).thenReturn(Instant.EPOCH)

        val method = SuspendDemoActions::class.java.declaredMethods
            .first { it.name == "greet" }
        val actionObject = SuspendDemoActions()

        val req = ActionExecutor.ExecuteActionRequest.builder()
            .actionObject(actionObject)
            .proxyMethod(method)
            .originalMethod(method)
            .arg(arrayOf<Any>("World"))
            .executionContext(executionContext)
            .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
            .build()

        val result = actionExecutor.executeAction(req)

        // Wait for the future to complete so that the whenComplete handler runs.
        @Suppress("UNCHECKED_CAST")
        (result as CompletableFuture<*>).get()

        // After completion, the async whenComplete handler should have added a dirty checkpoint.
        val dirtyCheckpoints = executionContext.dirtyCheckpoints
        assertThat(dirtyCheckpoints).hasSize(1)

        val checkpoint = dirtyCheckpoints.get(0)
        assertThat(checkpoint.isResultIsAsync).isTrue()
        assertThat(checkpoint.isTransient).isFalse()

        // The checkpoint result is itself a CompletableFuture wrapping the action's return value.
        @Suppress("UNCHECKED_CAST")
        val checkpointResult = (checkpoint.generateResult() as CompletableFuture<String>).get()
        assertThat(checkpointResult).isEqualTo("Hello World")
    }

    @Test
    fun `suspend action error wrapped in CompletableFuture`() {
        val executionContext = newExecContext()
        `when`(mockClock.instant()).thenReturn(Instant.EPOCH)

        val method = SuspendDemoActions::class.java.declaredMethods
            .first { it.name == "failNonRetryable" }
        val actionObject = SuspendDemoActions()

        val req = ActionExecutor.ExecuteActionRequest.builder()
            .actionObject(actionObject)
            .proxyMethod(method)
            .originalMethod(method)
            .arg(arrayOf<Any>("expected failure"))
            .executionContext(executionContext)
            .retryStrategy(FixedRetryStrategy(Duration.ofSeconds(1), 1))
            .build()

        // The error should NOT be thrown synchronously; it should be wrapped inside a
        // CompletableFuture that completes exceptionally.
        val result = actionExecutor.executeAction(req)
        assertThat(result).isInstanceOf(CompletableFuture::class.java)

        @Suppress("UNCHECKED_CAST")
        val future = result as CompletableFuture<*>

        // Joining the future should throw CompletionException wrapping NonRetryableError.
        var caught: Throwable? = null
        try {
            future.join()
        } catch (e: CompletionException) {
            caught = e
        }
        assertThat(caught).isNotNull()
        assertThat(caught!!.cause).isInstanceOf(NonRetryableError::class.java)
        assertThat(caught.cause!!.message).contains("expected failure")
    }
}
