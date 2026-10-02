package com.airbnb.skipper

import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.TestUtils
import com.airbnb.skipper.internal.api.RunRequest
import com.airbnb.skipper.internal.serde.SmartSerde
import com.airbnb.skipper.testutils.TestClient
import com.airbnb.skipper.testutils.TestRequestContext
import com.airbnb.skipper.testutils.TestRuntime
import com.airbnb.skipper.util.ExtraRequestData
import io.opentracing.mock.MockTracer
import io.vavr.control.Either
import io.vavr.control.Option
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class WorkflowFactoryTest {
    @get:Rule
    var coroutinesTestRule = CoroutineTestRule()

    @Suppress("UNUSED_PARAMETER")
    class SampleWorkflow : Workflow() {
        @com.google.inject.Inject
        private lateinit var testClient: TestClient

        @WorkflowMethod
        fun workflowMethod(input: String): String {
            return "Not reachable as Skipper engine is mocked"
        }

        @WorkflowMethod
        fun asyncWorkflowMethod(input: String): CompletableFuture<String> {
            return CompletableFuture.completedFuture("Not reachable as Skipper engine is mocked")
        }

        /** Result-less future return type, the only future shape a detached invocation accepts. */
        @WorkflowMethod
        fun asyncVoidWorkflowMethod(input: String): CompletableFuture<Void> {
            return CompletableFuture.completedFuture(null)
        }

        @SignalMethod
        fun signalMethod(input: String): String {
            return "Not reachable as Skipper engine is mocked"
        }

        @WorkflowMethod
        suspend fun suspendWorkflowMethod(input: String): String {
            return "Not reachable as Skipper engine is mocked"
        }

        @WorkflowMethod
        suspend fun suspendWorkflowMethodNoArgs(): String {
            return "Not reachable as Skipper engine is mocked"
        }

        @SignalMethod
        suspend fun suspendSignalMethod(data: String): String {
            return "Not reachable as Skipper engine is mocked"
        }

        @QueryMethod
        suspend fun suspendQueryMethod(): String {
            return "Not reachable as Skipper engine is mocked"
        }

        fun nonWorkflowMethod(): String {
            testClient.action("nonWorkflowMethod")
            return "Non-workflow method executed"
        }
    }

    class InvalidAction : Actions() {
        @Execute
        fun action() {
            throw UnsupportedOperationException()
        }

        @Execute
        fun action(int: Int) {
            throw UnsupportedOperationException()
        }
    }

    class WorkflowWithInvalidActions : Workflow() {
        @com.google.inject.Inject
        private var testActions: InvalidAction = actions(InvalidAction::class.java)

        @WorkflowMethod
        fun workflowMethod(input: String): String {
            return input
        }
    }

    private lateinit var workflowFactory: WorkflowFactory
    private lateinit var testClient: TestClient
    private lateinit var mockTracer: MockTracer
    private lateinit var workflowOptions: WorkflowOptions
    private var skipperEngine = mock<SkipperEngine>()

    private val workflowId: String = "test-id"

    private val REQUEST_CONTEXT = TestRequestContext.builder().userId("77").build()

    @Before
    fun init() {
        mockTracer = MockTracer()
        mockTracer.reset()
    }

    @AfterEach
    fun tearDown() {
        TestRequestContext.clearAllRequestContext()
    }

    @org.junit.jupiter.api.BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        mockTracer = io.opentracing.mock.MockTracer()
        workflowOptions = WorkflowOptions()
        testClient = mock()

        // Register TestClient so SimpleInjector can inject it into SampleWorkflow
        deps.addBinding(TestClient::class.java, testClient)

        val serde = SmartSerde()
        val runtime = deps.runtime
        workflowFactory = WorkflowFactory(
            workflowOptions,
            deps.config.utcClock,
            deps.config.injector,
            skipperEngine,
            runtime.middleware.get(),
            deps.config.resultPollingTimeLimit,
            java.time.Duration.ofMillis(500),
            WorkflowValidator(serde),
            ActionValidator(serde),
            mockTracer,
            deps.config.mainThreadPool,
            deps.internalDeps
        )
    }

    /**
     * A detached invocation returns before the workflow executes, so its future must not report
     * success — a caller that awaits it by mistake would otherwise take an immediate completion as
     * proof the workflow had finished.
     */
    @Test
    fun testDetachedInvocationReturnsFutureThatRefusesToBeAwaited() {
        // Deliberately left incomplete: a detached invocation must not depend on the result future.
        val workflowInstance = TestUtils.getWorkflowInstance()
        whenever(skipperEngine.startWorkflow(any())).thenReturn(workflowInstance)

        val workflow = workflowFactory.builder(SampleWorkflow::class.java, workflowId)
            .requestContext(REQUEST_CONTEXT)
            .detached()
            .build()
        val result = workflow.asyncVoidWorkflowMethod("input")

        verify(skipperEngine).startWorkflow(any())
        val thrown = assertThrows<java.util.concurrent.CompletionException> { result.join() }
        assertThat(thrown.cause).isInstanceOf(ResultUnavailable::class.java)
        assertThat(thrown.cause).hasMessageContaining("detached")
    }

    @Test
    fun testWorkflowIdAssignment() {
        val workflow = workflowFactory<SampleWorkflow>(workflowId)
        assertEquals(workflowId, workflow.id)
    }

    @Test
    fun testInvokeWorkflowMethod() {
        val workflowInstance = TestUtils.getWorkflowInstance()
        workflowInstance.result.complete("Workflow method executed")
        whenever(skipperEngine.startWorkflow(any()))
            .thenReturn(workflowInstance)
        val expectedExtraRequestData = ExtraRequestData()
        val workflow = workflowFactory<SampleWorkflow>(workflowId, REQUEST_CONTEXT)
        assertEquals("Workflow method executed", workflow.workflowMethod("input"))
        verify(skipperEngine).startWorkflow(
            RunRequest(
                workflowId,
                SampleWorkflow::class.java,
                "workflowMethod",
                "input",
                REQUEST_CONTEXT,
                expectedExtraRequestData,
                null,
                workflowOptions.executionTimeout,
                workflowOptions.allowQueryOnNonExistentWorkflow
            )
        )
    }

    @Test
    fun testInvokeWorkflowMethod_captureSpanContext() {
        val workflowInstance = TestUtils.getWorkflowInstance()
        workflowInstance.result.complete("Workflow method executed")
        whenever(skipperEngine.startWorkflow(any()))
            .thenReturn(workflowInstance)

        val span = mockTracer.buildSpan("root").start()
        val scope = mockTracer.activateSpan(span)

        val expectedExtraRequestData = ExtraRequestData()
        expectedExtraRequestData.storeSpanContext(mockTracer)
        val workflow = workflowFactory<SampleWorkflow>(workflowId, REQUEST_CONTEXT)
        val result = workflow.workflowMethod("input")

        span.finish()
        scope.close()

        assertEquals("Workflow method executed", result)
        verify(skipperEngine).startWorkflow(
            RunRequest(
                workflowId,
                SampleWorkflow::class.java,
                "workflowMethod",
                "input",
                REQUEST_CONTEXT,
                expectedExtraRequestData,
                null,
                workflowOptions.executionTimeout,
                workflowOptions.allowQueryOnNonExistentWorkflow
            )
        )
    }

    @Test
    fun testInvokeSignalMethod() {
        val workflowInstance = TestUtils.getWorkflowInstance()
        workflowInstance.result.complete("Signal method executed")
        whenever(skipperEngine.sendSignal(any()))
            .thenReturn(
                SkipperEngine.SendSignalResult(
                    workflowInstance,
                    Either.right("Signal method executed")
                )
            )
        val expectedExtraRequestData = ExtraRequestData()
        val workflow = workflowFactory<SampleWorkflow>(workflowId, REQUEST_CONTEXT)
        assertEquals("Signal method executed", workflow.signalMethod("input"))
        verify(skipperEngine).sendSignal(
            RunRequest(
                workflowId,
                SampleWorkflow::class.java,
                "signalMethod",
                "input",
                REQUEST_CONTEXT,
                expectedExtraRequestData,
                null,
                workflowOptions.executionTimeout,
                workflowOptions.allowQueryOnNonExistentWorkflow
            ),
        )
    }

    @Test
    fun testInvokeSignalMethod_captureSpanContext() {
        val workflowInstance = TestUtils.getWorkflowInstance()
        workflowInstance.result.complete("Signal method executed")
        whenever(skipperEngine.sendSignal(any()))
            .thenReturn(
                SkipperEngine.SendSignalResult(
                    workflowInstance,
                    Either.right("Signal method executed")
                )
            )

        val span = mockTracer.buildSpan("root").start()
        val scope = mockTracer.activateSpan(span)

        val expectedExtraRequestData = ExtraRequestData()
        expectedExtraRequestData.storeSpanContext(mockTracer)
        val workflow = workflowFactory<SampleWorkflow>(workflowId, REQUEST_CONTEXT)
        val result = workflow.signalMethod("input")

        span.finish()
        scope.close()

        assertEquals("Signal method executed", result)
        verify(skipperEngine).sendSignal(
            RunRequest(
                workflowId,
                SampleWorkflow::class.java,
                "signalMethod",
                "input",
                REQUEST_CONTEXT,
                expectedExtraRequestData,
                null,
                workflowOptions.executionTimeout,
                workflowOptions.allowQueryOnNonExistentWorkflow
            )
        )
    }

    @Test
    fun testInvokeNonWorkflowMethod() {
        val workflow = workflowFactory<SampleWorkflow>(workflowId)
        assertEquals("Non-workflow method executed", workflow.nonWorkflowMethod())
        verify(testClient).action("nonWorkflowMethod")
    }

    @Test
    fun testInvokeWhenWorkflowMethodThrowsTransientError_FallsBackToPolling() {
        val workflowInstance = TestUtils.getWorkflowInstance()
        val completedWorkflowInstance = workflowInstance.toBuilder()
            .status(WorkflowInstance.Status.COMPLETED)
            .result(CompletableFuture.completedFuture("Workflow method executed"))
            .build()
        workflowInstance.result.completeExceptionally(TransientError("Transient error"))
        whenever(skipperEngine.startWorkflow(any()))
            .thenReturn(workflowInstance)
        whenever(skipperEngine.getWorkflow(eq(workflowId)))
            .thenReturn(Option.of(completedWorkflowInstance))
        val workflow = workflowFactory<SampleWorkflow>(workflowId, REQUEST_CONTEXT)
        val result = workflow.workflowMethod("test")
        assertEquals("Workflow method executed", result)
        verify(skipperEngine).getWorkflow(workflowId)
    }

    @Test
    fun testInvokeWhenAsyncWorkflowMethodThrowsTransientError_FallsBackToPolling() {
        val workflowInstance = TestUtils.getWorkflowInstance()
        val completedWorkflowInstance = workflowInstance.toBuilder()
            .status(WorkflowInstance.Status.COMPLETED)
            .result(CompletableFuture.completedFuture("Workflow method executed"))
            .build()
        workflowInstance.result.completeExceptionally(TransientError("Transient error"))
        whenever(skipperEngine.startWorkflow(any()))
            .thenReturn(workflowInstance)
        whenever(skipperEngine.getWorkflow(eq(workflowId)))
            .thenReturn(Option.of(completedWorkflowInstance))
        val workflow = workflowFactory<SampleWorkflow>(workflowId, REQUEST_CONTEXT)
        val result = workflow.asyncWorkflowMethod("test")
        assertEquals("Workflow method executed", result.get())
        verify(skipperEngine, times(1)).getWorkflow(workflowId)
    }

    @Test
    fun testInvokeSuspendWorkflowMethodNoArgs_methodArgIsNull() {
        // When the proxy intercepts a suspend @WorkflowMethod with no user args,
        // args contains only [Continuation], so the handler falls to the `isSuspend -> null` branch,
        // giving methodArg = null in the RunRequest.
        val workflowInstance = TestUtils.getWorkflowInstance()
        workflowInstance.result.complete("Suspend no-arg workflow executed")
        whenever(skipperEngine.startWorkflow(any())).thenReturn(workflowInstance)
        val expectedExtraRequestData = ExtraRequestData()
        val workflow = workflowFactory<SampleWorkflow>(workflowId, REQUEST_CONTEXT)
        val method = SampleWorkflow::class.java.declaredMethods.first { it.name == "suspendWorkflowMethodNoArgs" }
        val result = SuspendSupport.invokeSuspendFunctionAsync(method, workflow).get()

        assertEquals("Suspend no-arg workflow executed", result)
        verify(skipperEngine).startWorkflow(
            RunRequest(
                workflowId,
                SampleWorkflow::class.java,
                "suspendWorkflowMethodNoArgs",
                null,
                REQUEST_CONTEXT,
                expectedExtraRequestData,
                null,
                workflowOptions.executionTimeout,
                workflowOptions.allowQueryOnNonExistentWorkflow
            )
        )
    }

    @Test
    fun testInvokeSuspendWorkflowMethod_nonTransientError_resumesCallerWithFailure() {
        // When the workflow result future fails with a non-transient error (e.g. NonRetryableError),
        // the async continuation path must resume the caller's Continuation with Result.failure,
        // propagating the exception back to the caller.
        val workflowInstance = TestUtils.getWorkflowInstance()
        workflowInstance.result.completeExceptionally(NonRetryableError("charge failed"))
        whenever(skipperEngine.startWorkflow(any())).thenReturn(workflowInstance)

        val workflow = workflowFactory<SampleWorkflow>(workflowId, REQUEST_CONTEXT)
        val method = SampleWorkflow::class.java.declaredMethods.first { it.name == "suspendWorkflowMethod" }
        val error = assertThrows<java.util.concurrent.ExecutionException> {
            SuspendSupport.invokeSuspendFunctionAsync(method, workflow, ContextSnapshot.NOOP, "input").get()
        }
        assertThat(error.cause).isInstanceOf(NonRetryableError::class.java)
        assertThat(error.cause?.message).isEqualTo("charge failed")
    }

    @Test
    fun testInvokeWhenSuspendWorkflowMethodThrowsTransientError_FallsBackToPolling() {
        // Validates that the async continuation-based path in the proxy correctly handles
        // TransientError by polling for the result and resuming the caller's Continuation
        // with the polled value.
        val workflowInstance = TestUtils.getWorkflowInstance()
        val completedWorkflowInstance = workflowInstance.toBuilder()
            .status(WorkflowInstance.Status.COMPLETED)
            .result(CompletableFuture.completedFuture("Suspend workflow executed"))
            .build()
        workflowInstance.result.completeExceptionally(TransientError("Transient error"))
        whenever(skipperEngine.startWorkflow(any())).thenReturn(workflowInstance)
        whenever(skipperEngine.getWorkflow(eq(workflowId))).thenReturn(Option.of(completedWorkflowInstance))

        val workflow = workflowFactory<SampleWorkflow>(workflowId, REQUEST_CONTEXT)
        val method = SampleWorkflow::class.java.declaredMethods.first { it.name == "suspendWorkflowMethod" }
        val result = SuspendSupport.invokeSuspendFunctionAsync(method, workflow, ContextSnapshot.NOOP, "test").get()

        assertEquals("Suspend workflow executed", result)
        verify(skipperEngine).getWorkflow(workflowId)
    }

    @Test
    fun testValidateWorkflowAction() {
        val error = assertThrows<IllegalArgumentException> {
            workflowFactory<WorkflowWithInvalidActions>(workflowId, REQUEST_CONTEXT)
        }
        assertThat(error.message).contains("contains multiple methods with the same name annotated with @Execute or @Compensate")
    }

    @Test
    fun testInvokeWorkflowMethod_ossDefaultMiddlewareDoesNotPersistRefreshFlag() {
        // The OSS default/test middleware does not implement storeWorkflowCreationMetadata, so
        // even when refreshRequestContextForLongRunningWorkflows is requested the persisted
        // ExtraRequestData carries no such flag. Persisting it is now a host-middleware
        // concern, wired through the creation-metadata hook.
        val workflowInstance = TestUtils.getWorkflowInstance()
        workflowInstance.result.complete("Workflow method executed")
        whenever(skipperEngine.startWorkflow(any()))
            .thenReturn(workflowInstance)

        val customWorkflowOptions = WorkflowOptions(refreshRequestContextForLongRunningWorkflows = true)
        val workflow = workflowFactory<SampleWorkflow>(workflowId, REQUEST_CONTEXT, customWorkflowOptions)
        assertEquals("Workflow method executed", workflow.workflowMethod("input"))

        // No active span and a no-op creation-metadata hook => empty data bag.
        val expectedExtraRequestData = ExtraRequestData()

        verify(skipperEngine).startWorkflow(
            RunRequest(
                workflowId,
                SampleWorkflow::class.java,
                "workflowMethod",
                "input",
                REQUEST_CONTEXT,
                expectedExtraRequestData,
                null,
                customWorkflowOptions.executionTimeout,
                customWorkflowOptions.allowQueryOnNonExistentWorkflow
            )
        )
    }

    @Test
    fun testInvokeWorkflowMethod_passesCreateExistingWorkflowIsNoop() {
        val workflowInstance = TestUtils.getWorkflowInstance()
        workflowInstance.result.complete("Workflow method executed")
        whenever(skipperEngine.startWorkflow(any()))
            .thenReturn(workflowInstance)

        val customWorkflowOptions = WorkflowOptions(createExistingWorkflowIsNoop = true)
        val workflow = workflowFactory<SampleWorkflow>(workflowId, REQUEST_CONTEXT, customWorkflowOptions)

        assertEquals("Workflow method executed", workflow.workflowMethod("input"))
        verify(skipperEngine).startWorkflow(
            RunRequest.builder()
                .workflowId(workflowId)
                .workflowClass(SampleWorkflow::class.java)
                .workflowMethod("workflowMethod")
                .input("input")
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(ExtraRequestData())
                .createExistingWorkflowIsNoop(true)
                .build()
        )
    }

    @Test
    fun testInvokeSuspendWorkflowMethod_stripsContinuation() {
        // When the proxy intercepts a suspend @WorkflowMethod with 1 user arg,
        // the Kotlin compiler passes [input, Continuation] as the args array.
        // The handler must extract args[0] as methodArg, not the raw array (which includes Continuation).
        val workflowInstance = TestUtils.getWorkflowInstance()
        workflowInstance.result.complete("Suspend workflow executed")
        whenever(skipperEngine.startWorkflow(any()))
            .thenReturn(workflowInstance)
        val expectedExtraRequestData = ExtraRequestData()
        val workflow = workflowFactory<SampleWorkflow>(workflowId, REQUEST_CONTEXT)
        // Use SuspendSupport.invokeSuspendFunctionAsync to call the proxy's suspend method.
        // It appends a real Continuation to [input], producing args=[input, continuation] in the handler.
        val method = SampleWorkflow::class.java.declaredMethods.first { it.name == "suspendWorkflowMethod" }
        val result = SuspendSupport.invokeSuspendFunctionAsync(method, workflow, ContextSnapshot.NOOP, "input").get()

        assertEquals("Suspend workflow executed", result)
        verify(skipperEngine).startWorkflow(
            RunRequest(
                workflowId,
                SampleWorkflow::class.java,
                "suspendWorkflowMethod",
                "input",
                REQUEST_CONTEXT,
                expectedExtraRequestData,
                null,
                workflowOptions.executionTimeout,
                workflowOptions.allowQueryOnNonExistentWorkflow
            )
        )
    }

    @Test
    fun testInvokeSuspendSignalMethod_stripsContinuation() {
        // When the proxy intercepts a suspend @SignalMethod with 1 user arg,
        // the Kotlin compiler passes [data, Continuation] as args.
        // The handler must extract args[0] as methodArg.
        val workflowInstance = TestUtils.getWorkflowInstance()
        workflowInstance.result.complete("Suspend signal executed")
        whenever(skipperEngine.sendSignal(any()))
            .thenReturn(
                SkipperEngine.SendSignalResult(
                    workflowInstance,
                    Either.right("Suspend signal executed")
                )
            )
        val expectedExtraRequestData = ExtraRequestData()
        val workflow = workflowFactory<SampleWorkflow>(workflowId, REQUEST_CONTEXT)
        val method = SampleWorkflow::class.java.declaredMethods.first { it.name == "suspendSignalMethod" }
        val result = SuspendSupport.invokeSuspendFunctionAsync(method, workflow, ContextSnapshot.NOOP, "data").get()

        assertEquals("Suspend signal executed", result)
        verify(skipperEngine).sendSignal(
            RunRequest(
                workflowId,
                SampleWorkflow::class.java,
                "suspendSignalMethod",
                "data",
                REQUEST_CONTEXT,
                expectedExtraRequestData,
                null,
                workflowOptions.executionTimeout,
                workflowOptions.allowQueryOnNonExistentWorkflow
            )
        )
    }

    @Test
    fun testInvokeSuspendQueryMethod_noUserArgs_methodArgIsNull() {
        // When the proxy intercepts a suspend @QueryMethod with 0 user args,
        // SuspendSupport.invokeSuspendFunctionAsync passes [Continuation] only.
        // The handler must fall through to the `isSuspend -> null` branch, giving methodArg = null.
        whenever(skipperEngine.invokeQueryMethod(any())).thenReturn("Query result")
        val expectedExtraRequestData = ExtraRequestData()
        val workflow = workflowFactory<SampleWorkflow>(workflowId, REQUEST_CONTEXT)
        val method = SampleWorkflow::class.java.declaredMethods.first { it.name == "suspendQueryMethod" }
        val result = SuspendSupport.invokeSuspendFunctionAsync(method, workflow).get()

        assertEquals("Query result", result)
        verify(skipperEngine).invokeQueryMethod(
            RunRequest(
                workflowId,
                SampleWorkflow::class.java,
                "suspendQueryMethod",
                null,
                REQUEST_CONTEXT,
                expectedExtraRequestData,
                null,
                workflowOptions.executionTimeout,
                workflowOptions.allowQueryOnNonExistentWorkflow
            )
        )
    }
}
