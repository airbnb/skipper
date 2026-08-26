package com.airbnb.skipper.internal

import com.airbnb.skipper.ApplicationError
import com.airbnb.skipper.ExecutionMetricsCollector
import com.airbnb.skipper.FixedRetryStrategy
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.RawActionInvocation
import com.airbnb.skipper.RawRequestContextMiddleware
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.SignalMethod
import com.airbnb.skipper.SkipperInjector
import com.airbnb.skipper.StateField
import com.airbnb.skipper.ValidationError
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.internal.TestUtils.REQUEST_CONTEXT
import com.airbnb.skipper.testutils.TestRuntime
import com.airbnb.skipper.util.ExtraRequestData
import io.opentracing.Span
import io.opentracing.mock.MockTracer
import io.vavr.collection.HashMap
import io.vavr.control.Either
import java.time.Clock
import java.time.Duration
import java.util.Objects
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class WorkflowExecutorTest {
    private lateinit var injector: SkipperInjector
    private lateinit var workflowExecutor: WorkflowExecutor
    private lateinit var executorService: ExecutorService
    private lateinit var middleware: RawRequestContextMiddleware
    private lateinit var mockTracer: MockTracer
    private lateinit var metrics: Metrics
    private lateinit var mockClock: Clock

    private lateinit var executionContext: ExecutionContext
    private lateinit var mockExecutionMetricsCollector: ExecutionMetricsCollector
    private lateinit var deps: TestRuntime
    private val unexpectedErrorRetryDelay: Duration = Duration.ofSeconds(10)

    @BeforeEach
    fun setUp() {
        deps = TestRuntime()
        val runtime = deps.getRuntime()
        injector = deps.config.injector
        middleware = spy(runtime.middleware.get())
        mockTracer = MockTracer()
        metrics = runtime.metrics.get()
        mockClock = deps.clock
        executorService = deps.config.mainThreadPool

        workflowExecutor =
            WorkflowExecutor(
                injector,
                deps.internalDeps,
                middleware,
                deps.config.contextPropagator,
                mockTracer,
                metrics,
                ExecutionMetricsCollector(mockTracer),
            )

        executionContext =
            ExecutionContext.builder()
                .workflow(TestUtils.getWorkflowInstance())
                .clock(mockClock)
                .executorService(executorService)
                .build()
        mockTracer.reset()
        mockExecutionMetricsCollector = mock()
        whenever(mockExecutionMetricsCollector.createWorkflowSpan(any())).thenReturn(mock<Span>())
    }

    @Test
    fun testExecuteWorkflowMethod() {
        val requestContext =
            REQUEST_CONTEXT.toBuilder()
                .userId("77986")
                .build()
        val instance =
            TestUtils.getWorkflowInstance().toBuilder()
                .workflowClass(Greeter::class.java)
                .workflowMethod("hello")
                .input("Ricardo")
                .requestContext(requestContext)
                .state(HashMap.of("count", 123))
                .build()
        val state =
            HashMap.of(
                "lastGreeting",
                "Workflow Id: test-workflow-id; Hello, Ricardo!",
                "count",
                123,
                "shouldProceed",
                false,
            )
        val result =
            workflowExecutor.executeWorkflowMethod(instance, executorService, executionContext).join()
        assertEquals("Workflow Id: test-workflow-id; Hello, Ricardo!", result.result!!.get())
        assertFalse(result.isResultIsAsync)
        // Verify that the new state has been set, and also check that the count has been preserved
        // from the initial instance state.
        assertEquals(state, result.newState)
        // async version
        val instance2 = instance.toBuilder().workflowMethod("helloAsync").build()
        val asyncResult =
            workflowExecutor.executeWorkflowMethod(instance2, executorService, executionContext).join()
        // For async methods where the result is a completable future, the executor will join
        assertTrue(asyncResult.isResultIsAsync)
        assertEquals("Hello, Ricardo!", asyncResult.result!!.get())
        verify(middleware, atLeastOnce()).rawBeforeExecution(any<RawActionInvocation>())
        verify(middleware, atLeastOnce()).rawAfterExecution(any<RawActionInvocation>())
    }

    @Test
    fun testExecuteWorkflowMethod_tracing() {
        val span = mockTracer.buildSpan("root").start()
        val scope = mockTracer.activateSpan(span)
        val extraRequestData = ExtraRequestData()
        extraRequestData.storeSpanContext(mockTracer)
        span.finish()
        scope.close()

        val requestContext =
            REQUEST_CONTEXT.toBuilder()
                .userId("77986")
                .build()
        val instance =
            TestUtils.getWorkflowInstance().toBuilder()
                .workflowClass(Greeter::class.java)
                .workflowMethod("hello")
                .input("Ricardo")
                .requestContext(requestContext)
                .extraRequestData(extraRequestData)
                .state(HashMap.of("count", 123))
                .build()
        workflowExecutor.executeWorkflowMethod(instance, executorService, executionContext).join()

        val finishedSpans = mockTracer.finishedSpans()
        assertEquals(2, finishedSpans.size)
        val resultSpan = finishedSpans.get(1)
        assertEquals(
            String.format("workflow.%s.%s", Greeter::class.java.simpleName, "hello"),
            resultSpan.operationName(),
        )
        assertEquals(span.context().toTraceId(), resultSpan.context().toTraceId())
        assertEquals(span.context().toSpanId(), resultSpan.parentId().toString())
    }

    @Test
    fun testExecuteWorkflowMethod_whenInvalidArgumentIsPassed() {
        val instance =
            TestUtils.getWorkflowInstance().toBuilder()
                .workflowClass(Greeter::class.java)
                .workflowMethod("hello")
                .input(1)
                .build()
        val result =
            workflowExecutor.executeWorkflowMethod(instance, executorService, executionContext).join()
        assertTrue(result.result!!.left is NonRetryableError)
        val nonRetryable = result.result!!.left as NonRetryableError
        assertEquals(IllegalArgumentException::class.java.name, nonRetryable.cause!!.type)
        assertEquals(WorkflowInstance.Status.ERROR, result.newStatus)
        verify(middleware, atLeastOnce()).rawAfterExecution(any<RawActionInvocation>())
    }

    @Test
    fun testExecuteWorkflowMethod_whenWorkflowThrowsWaitSignal() {
        val instance =
            TestUtils.getWorkflowInstance().toBuilder()
                .workflowClass(Greeter::class.java)
                .workflowMethod("helloWithWait")
                .input("Ricardo")
                .build()
        val result =
            workflowExecutor.executeWorkflowMethod(instance, executorService, executionContext).join()
        assertEquals(WorkflowInstance.Status.WAITING, result.newStatus)
        assertEquals(Duration.ofDays(Workflow.DEFAULT_WAIT_DURATION_DAYS), result.waitDuration)
    }

    @Test
    @DisplayName("All exceptions thrown by workflow code should handled gracefully")
    fun testExecuteWorkflowMethod_whenWorkflowMethodThrowsException() {
        // Retryable error
        val instance =
            TestUtils.getWorkflowInstance().toBuilder()
                .workflowClass(Greeter::class.java)
                .workflowMethod("hello")
                .input("retryable-error")
                .build()
        val result =
            workflowExecutor.executeWorkflowMethod(instance, executorService, executionContext).join()
        assertEquals(Duration.ZERO, result.retryDelay)
        assertEquals(WorkflowInstance.Status.TRANSIENT_ERROR, result.newStatus)
        // Non retryable error
        val instance2 = instance.toBuilder().input("non-retryable-error").build()
        val result2 =
            workflowExecutor.executeWorkflowMethod(instance2, executorService, executionContext).join()
        assertEquals(WorkflowInstance.Status.ERROR, result2.newStatus)
        assertNull(result2.retryDelay)
        // All other exceptions are wrapped into NonRetryableError
        val instance3 = instance.toBuilder().input("throw").build()
        val result3 =
            workflowExecutor.executeWorkflowMethod(instance3, executorService, executionContext).join()
        assertEquals(WorkflowInstance.Status.ERROR, result3.newStatus)
        assertNull(result3.retryDelay)
        val wrappedError = result3.result!!.left.cause as ApplicationError
        assertEquals(IllegalStateException::class.java.name, wrappedError.type)
    }

    @Test
    fun testExecuteWorkflowMethod_whenWorkflowMethodThrowsPersistentException() {
        // Persistent Retryable error
        val instance =
            TestUtils.getWorkflowInstance().toBuilder()
                .workflowClass(Greeter::class.java)
                .workflowMethod("hello")
                .input("persistent-error")
                .build()
        val result =
            workflowExecutor.executeWorkflowMethod(instance, executorService, executionContext).join()
        assertNull(result.retryDelay)
        assertNull(result.waitDuration)
        assertEquals(WorkflowInstance.Status.RETRIES_EXHAUSTED, result.newStatus)
        assertTrue(result.result!!.left is PersistentRetryableError)
    }

    @Test
    fun testExecuteWorkflowMethod_whenWorkflowClassDoesntExist() {
        val instance =
            TestUtils.getWorkflowInstance().toBuilder().workflowClass(Workflow::class.java).build()
        val result =
            workflowExecutor.executeWorkflowMethod(instance, executorService, executionContext).join()
        assertTrue(result.result!!.left is NonRetryableError)
        assertTrue(
            (result.result!!.left as NonRetryableError)
                .message!!
                .contains("Cannot provide a workflow for class"),
        )
    }

    @Test
    fun testExecuteWorkflowMethod_whenWorkflowClassHasErrors() {
        val instance =
            TestUtils.getWorkflowInstance().toBuilder().workflowClass(InvalidWorkflow::class.java).build()
        val result =
            workflowExecutor.executeWorkflowMethod(instance, executorService, executionContext).join()
        assertTrue(result.result!!.left is NonRetryableError)
        assertTrue(
            result.result!!.left.message!!.contains("must have at most one parameter"),
        )
    }

    @Test
    fun testExecuteSignalMethod() {
        val requestContext =
            REQUEST_CONTEXT.toBuilder()
                .userId("77984")
                .build()
        val instance =
            TestUtils.getWorkflowInstance().toBuilder()
                .workflowClass(Greeter::class.java)
                .workflowMethod("helloWithWait")
                .input(1)
                .requestContext(requestContext)
                .build()
        val result =
            workflowExecutor
                .executeSignalMethod(
                    instance,
                    executorService,
                    executionContext,
                    "updateShouldProceed",
                    true,
                )
                .join()
        assertNull(result.newStatus)
        assertTrue(result.newState!!.get("shouldProceed").get() as Boolean)
        verify(middleware, atLeastOnce()).rawBeforeExecution(any<RawActionInvocation>())
        verify(middleware, atLeastOnce()).rawAfterExecution(any<RawActionInvocation>())
    }

    @Test
    fun testExecuteSignalMethod_whenInvalidInputIsSent() {
        val instance =
            TestUtils.getWorkflowInstance().toBuilder()
                .workflowClass(Greeter::class.java)
                .workflowMethod("helloWithWait")
                .input(1)
                .build()
        val result =
            workflowExecutor
                .executeSignalMethod(
                    instance,
                    executorService,
                    executionContext,
                    "updateShouldProceed",
                    1,
                )
                .join()
        assertEquals(WorkflowInstance.Status.ERROR, result.newStatus)
        assertTrue(result.result!!.left is NonRetryableError)
        verify(middleware, atLeastOnce()).rawAfterExecution(any<RawActionInvocation>())
        assertEquals(
            IllegalArgumentException::class.java.name,
            (result.result!!.left as NonRetryableError).cause!!.type,
        )
    }

    @Test
    fun testExecuteSignalMethod_whenInvalidSignalMethodIsProvided() {
        val instance =
            TestUtils.getWorkflowInstance().toBuilder()
                .workflowClass(Greeter::class.java)
                .workflowMethod("helloWithWait")
                .input(1)
                .build()
        val error =
            assertThrows(CompletionException::class.java) {
                workflowExecutor
                    .executeSignalMethod(
                        instance,
                        executorService,
                        executionContext,
                        "invalidSignalMethod",
                        true,
                    )
                    .join()
            }
        assertTrue(error.cause is ValidationError)
    }

    @Test
    fun testWorkflowResultGetWrappedSuccessfulResult() {
        val result =
            WorkflowExecutor.ExecutionResult.builder()
                .result(Either.right("hello world"))
                .resultIsAsync(false)
                .build()
        assertEquals("hello world", result.wrappedSuccessfulResult)
        // Test when result is not successful
        val result2 =
            WorkflowExecutor.ExecutionResult.builder()
                .result(Either.left(NonRetryableError("error")))
                .resultIsAsync(false)
                .build()
        assertThrows(IllegalStateException::class.java) { result2.wrappedSuccessfulResult }
        // Test when result is async
        val result3 =
            WorkflowExecutor.ExecutionResult.builder()
                .result(Either.right("hello world"))
                .resultIsAsync(true)
                .build()
        assertTrue(result3.wrappedSuccessfulResult is CompletableFuture<*>)
        assertEquals(
            "hello world",
            (result3.wrappedSuccessfulResult as CompletableFuture<*>).join(),
        )
    }

    @Test
    fun testExecuteMethodWhenRetryableError_ErrorIsConvertedToNonRetryable() {
        val instance =
            TestUtils.getWorkflowInstance().toBuilder()
                .workflowClass(Greeter::class.java)
                .workflowMethod("hello")
                .input("retryable-error")
                .build()
        val result =
            workflowExecutor.executeWorkflowMethod(instance, executorService, executionContext).join()
        assertEquals(WorkflowInstance.Status.TRANSIENT_ERROR, result.newStatus)
        assertEquals(Duration.ZERO, result.retryDelay)
    }

    @Test
    @DisplayName("RejectedExecutionException should be converted to RetryableError")
    fun testExecuteWorkflowMethod_whenRejectedExecutionExceptionIsThrown() {
        val instance =
            TestUtils.getWorkflowInstance().toBuilder()
                .workflowClass(Greeter::class.java)
                .workflowMethod("hello")
                .input("rejected-execution")
                .build()
        val result =
            workflowExecutor.executeWorkflowMethod(instance, executorService, executionContext).join()
        assertEquals(WorkflowInstance.Status.TRANSIENT_ERROR, result.newStatus)
        assertTrue(result.result!!.left is RetryableError)
        val retryableError = result.result!!.left as RetryableError
        assertEquals("unable to schedule task execution in thread pool", retryableError.message)
        assertEquals(Duration.ZERO, result.retryDelay)
        assertEquals(RejectedExecutionException::class.java.name, retryableError.cause!!.type)
    }

    @Test
    @DisplayName(
        "RejectedExecutionException wrapped in CompletionException should be converted to" +
            " RetryableError",
    )
    fun testExecuteWorkflowMethod_whenRejectedExecutionExceptionIsWrappedInCompletionException() {
        val instance =
            TestUtils.getWorkflowInstance().toBuilder()
                .workflowClass(Greeter::class.java)
                .workflowMethod("helloAsyncWithRejectedExecution")
                .input("test")
                .build()
        val result =
            workflowExecutor.executeWorkflowMethod(instance, executorService, executionContext).join()
        assertEquals(WorkflowInstance.Status.TRANSIENT_ERROR, result.newStatus)
        assertTrue(result.result!!.left is RetryableError)
        val retryableError = result.result!!.left as RetryableError
        assertEquals("unable to schedule task execution in thread pool", retryableError.message)
        assertEquals(Duration.ZERO, result.retryDelay)
        assertEquals(RejectedExecutionException::class.java.name, retryableError.cause!!.type)
    }

    @Test
    fun testExecuteMethodWhenWorkflowIsInTerminalState() {
        val instance =
            TestUtils.getWorkflowInstance().toBuilder()
                .workflowClass(Greeter::class.java)
                .workflowMethod("hello")
                .input("retryable-error")
                .status(WorkflowInstance.Status.COMPLETED)
                .result(CompletableFuture.completedFuture("hello result!"))
                .build()
        val result =
            workflowExecutor.executeWorkflowMethod(instance, executorService, executionContext).join()
        assertEquals(WorkflowInstance.Status.COMPLETED, result.newStatus)
        assertEquals("hello result!", result.result!!.get())
    }

    @ParameterizedTest
    @EnumSource(
        value = WorkflowInstance.Status::class,
        mode = EnumSource.Mode.INCLUDE,
        names = ["COMPENSATION_IN_PROGRESS", "COMPENSATION_ERROR"],
    )
    fun testExecuteMethodWhenWorkflowIsInCompensationState(status: WorkflowInstance.Status) {
        val instance =
            TestUtils.getWorkflowInstance().toBuilder()
                .workflowClass(Greeter::class.java)
                .workflowMethod("hello")
                .input("Ricardo")
                .status(status)
                .result(CompletableFuture.completedFuture("compensation result"))
                .build()
        val result =
            workflowExecutor.executeWorkflowMethod(instance, executorService, executionContext).join()
        assertEquals(status, result.newStatus)
        assertEquals("compensation result", result.result!!.get())
    }

    private class Greeter : Workflow() {
        @StateField var lastGreeting: String = ""

        @StateField var count: Int = 0

        @StateField var shouldProceed: Boolean = false

        @WorkflowMethod
        fun hello(name: String): String {
            if ("retryable-error" == name) {
                throw RetryableError(
                    "retryable error",
                    null,
                    FixedRetryStrategy(Duration.ZERO, 10),
                    0,
                )
            }
            if ("non-retryable-error" == name) {
                throw NonRetryableError("non retryable error")
            }
            if ("throw" == name) {
                throw IllegalStateException("illegal argument")
            }
            if ("persistent-error" == name) {
                throw PersistentRetryableError(
                    RetryableError(
                        "retryable error",
                        null,
                        FixedRetryStrategy(Duration.ZERO, 10),
                        0,
                    ),
                )
            }
            if ("rejected-execution" == name) {
                throw RejectedExecutionException("thread pool is shutting down")
            }
            lastGreeting = String.format("Workflow Id: %s; Hello, %s!", id, name)
            return lastGreeting
        }

        @WorkflowMethod
        fun helloAsync(name: String): CompletableFuture<String> = CompletableFuture.completedFuture(String.format("Hello, %s!", name))

        @WorkflowMethod
        fun helloAsyncWithRejectedExecution(name: String): CompletableFuture<String> {
            val future = CompletableFuture<String>()
            future.completeExceptionally(
                RejectedExecutionException("async thread pool is shutting down"),
            )
            return future
        }

        @WorkflowMethod
        fun helloWithWait(name: String): CompletableFuture<String> {
            waitUntil { shouldProceed }
            return CompletableFuture.completedFuture(String.format("Hello, %s!", name))
        }

        @SignalMethod
        fun updateShouldProceed(shouldProceed: Boolean) {
            Objects.requireNonNull(id)
            this.shouldProceed = shouldProceed
        }
    }

    private class InvalidWorkflow : Workflow() {
        @WorkflowMethod
        fun invalidMethod(
            arg: Int,
            arg2: Int
        ) = Unit
    }
}
