package com.airbnb.skipper.internal

import com.airbnb.skipper.Actions
import com.airbnb.skipper.CheckpointMode
import com.airbnb.skipper.Compensate
import com.airbnb.skipper.Execute
import com.airbnb.skipper.ExecutionMetricsCollector
import com.airbnb.skipper.FixedRetryStrategy
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.RawRequestContextMiddleware
import com.airbnb.skipper.RetryStrategy
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.WorkflowOptions
import com.airbnb.skipper.internal.TestUtils.REQUEST_CONTEXT
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.testutils.TestRuntime
import com.airbnb.skipper.util.ExtraRequestData
import com.airbnb.skipper.util.SpanTagger
import io.opentracing.mock.MockTracer
import io.vavr.collection.HashMap
import io.vavr.collection.List
import io.vavr.control.Either
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.spy

class CompensationExecutorTest {
    private lateinit var compensationExecutor: CompensationExecutor
    private lateinit var executorService: ExecutorService
    private lateinit var middleware: RawRequestContextMiddleware
    private lateinit var mockTracer: MockTracer
    private lateinit var metrics: Metrics
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var mockClock: Clock

    private val defaultCompensationRetryStrategy: RetryStrategy =
        FixedRetryStrategy(Duration.ZERO, 10)

    private lateinit var executionContext: ExecutionContext

    @BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        mockClock = deps.clock
        val runtime = deps.runtime
        executorService = deps.config.mainThreadPool
        middleware = spy(runtime.middleware.get())
        mockTracer = MockTracer()
        metrics = runtime.metrics.get()

        val executionMetricsCollector = ExecutionMetricsCollector(mockTracer)
        val workflowStore = runtime.workflowStore.get()
        actionExecutor =
            ActionExecutor(
                workflowStore,
                CheckpointMode.EVENTUAL_CHECKPOINT,
                metrics,
                ActionErrorMapper(DefaultExceptionClassifier()),
                NoOpEventPublisher(),
                executionMetricsCollector,
                mockTracer,
                deps.config.contextPropagator,
                null,
            )

        compensationExecutor =
            CompensationExecutor(
                deps.config.injector,
                deps.internalDeps,
                WorkflowOptions(),
                middleware,
                mockTracer,
                metrics,
                actionExecutor,
                deps.config.contextPropagator,
                defaultCompensationRetryStrategy,
                SpanTagger.DEFAULT,
            )

        executionContext =
            ExecutionContext.builder()
                .workflow(TestUtils.getWorkflowInstance())
                .clock(mockClock)
                .executorService(executorService)
                .build()
        mockTracer.reset()
    }

    @Test
    fun testExecuteCompensationFlow_noCompensableActions() {
        val workflowInstance =
            WorkflowInstance.builder()
                .workflowId("test-workflow-id")
                .workflowClass(TestWorkflow::class.java)
                .workflowMethod("hello")
                .input("world")
                .status(WorkflowInstance.Status.ERROR)
                .state(HashMap.empty())
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(ExtraRequestData())
                .result(CompletableFuture())
                .version(1)
                .createdAt(Instant.now())
                .build()

        val executionContext =
            ExecutionContext.builder()
                .workflow(workflowInstance)
                .clock(mockClock)
                .executorService(executorService)
                .build()

        val result =
            compensationExecutor
                .executeCompensationFlow(workflowInstance, executorService, executionContext)
                .join()

        // Should succeed with COMPENSATION_COMPLETED status since no actions to compensate
        assertEquals(WorkflowInstance.Status.COMPENSATION_COMPLETED, result.newStatus)
        assertTrue(result.error.isEmpty)
    }

    @Test
    fun testExecuteCompensationFlow_withSuccessfulActions() {
        val workflowInstance =
            WorkflowInstance.builder()
                .workflowId("test-workflow-id")
                .workflowClass(CompensableWorkflow::class.java)
                .workflowMethod("processOrder")
                .input("payment-123")
                .status(WorkflowInstance.Status.ERROR)
                .state(HashMap.empty())
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(ExtraRequestData())
                .result(CompletableFuture())
                .version(1)
                .createdAt(Instant.now())
                .build()

        // Create successful action checkpoint
        val checkpoint =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId("test-workflow-id")
                        .actionClass(CompensableActions::class.java)
                        .actionMethod("processPayment")
                        .build()
                )
                .result(Either.right("payment-processed"))
                .input("payment-123")
                .executionStartTime(Instant.EPOCH)
                .build()

        val executionContext =
            ExecutionContext.builder()
                .workflow(workflowInstance)
                .clock(mockClock)
                .executorService(executorService)
                .actionCheckpoints(List.of(checkpoint))
                .build()

        val result =
            compensationExecutor
                .executeCompensationFlow(workflowInstance, executorService, executionContext)
                .join()

        assertEquals(WorkflowInstance.Status.COMPENSATION_COMPLETED, result.newStatus)
        assertTrue(result.error.isEmpty)
    }

    @Test
    fun testExecuteCompensationFlow_withRetryableError() {
        val workflowInstance =
            WorkflowInstance.builder()
                .workflowId("test-workflow-id")
                .workflowClass(CompensableWorkflow::class.java)
                .workflowMethod("notifyUser")
                .input("test-message")
                .status(WorkflowInstance.Status.ERROR)
                .state(HashMap.empty())
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(ExtraRequestData())
                .result(CompletableFuture())
                .version(1)
                .createdAt(Instant.now())
                .build()

        // Create successful action checkpoint that will cause retryable error during compensation
        val checkpoint =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId("test-workflow-id")
                        .actionClass(CompensableActions::class.java)
                        .actionMethod("sendNotification2")
                        .build()
                )
                .result(Either.right("notification-sent"))
                .input("test-message")
                .executionStartTime(Instant.EPOCH)
                .build()

        val executionContext =
            ExecutionContext.builder()
                .workflow(workflowInstance)
                .clock(mockClock)
                .executorService(executorService)
                .actionCheckpoints(List.of(checkpoint))
                .build()

        val result =
            compensationExecutor
                .executeCompensationFlow(workflowInstance, executorService, executionContext)
                .join()

        assertEquals(WorkflowInstance.Status.COMPENSATION_IN_PROGRESS, result.newStatus)
        assertTrue(result.error.isDefined)
        assertTrue(result.error.get() is RetryableError)
    }

    @Test
    fun testExecuteCompensationFlow_withNonRetryableError() {
        val workflowInstance =
            WorkflowInstance.builder()
                .workflowId("test-workflow-id")
                .workflowClass(CompensableWorkflow::class.java)
                .workflowMethod("notifyUser")
                .input("test-message")
                .status(WorkflowInstance.Status.ERROR)
                .state(HashMap.empty())
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(ExtraRequestData())
                .result(CompletableFuture())
                .version(1)
                .createdAt(Instant.now())
                .build()

        // Mock the compensation method to throw NonRetryableError
        val checkpoint =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId("test-workflow-id")
                        .actionClass(CompensableActions::class.java)
                        .actionMethod("sendNotification")
                        .build()
                )
                .result(Either.right("notification-sent"))
                .input("test-message")
                .executionStartTime(Instant.EPOCH)
                .build()

        val executionContext =
            ExecutionContext.builder()
                .workflow(workflowInstance)
                .clock(mockClock)
                .executorService(executorService)
                .actionCheckpoints(List.of(checkpoint))
                .build()

        val result =
            compensationExecutor
                .executeCompensationFlow(workflowInstance, executorService, executionContext)
                .join()

        assertEquals(WorkflowInstance.Status.COMPENSATION_ERROR, result.newStatus)
        assertTrue(result.error.isDefined)
        assertTrue(result.error.get() is NonRetryableError)
    }

    @Test
    fun testExecuteCompensationFlow_withTwoParameterCompensation() {
        // Reset static fields before test
        TwoParameterCompensableActions.reset()

        val workflowInstance =
            WorkflowInstance.builder()
                .workflowId("test-workflow-id")
                .workflowClass(TwoParameterCompensableWorkflow::class.java)
                .workflowMethod("processOrder")
                .input("order-123")
                .status(WorkflowInstance.Status.ERROR)
                .state(HashMap.empty())
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(ExtraRequestData())
                .result(CompletableFuture())
                .version(1)
                .createdAt(Instant.now())
                .build()

        // Create successful action checkpoint
        val checkpoint =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId("test-workflow-id")
                        .actionClass(TwoParameterCompensableActions::class.java)
                        .actionMethod("processOrder")
                        .build()
                )
                .result(Either.right("order-processed-123"))
                .input("order-123")
                .executionStartTime(Instant.EPOCH)
                .build()

        val executionContext =
            ExecutionContext.builder()
                .workflow(workflowInstance)
                .clock(mockClock)
                .executorService(executorService)
                .actionCheckpoints(List.of(checkpoint))
                .build()

        val result =
            compensationExecutor
                .executeCompensationFlow(workflowInstance, executorService, executionContext)
                .join()

        // Verify compensation was successful
        assertEquals(WorkflowInstance.Status.COMPENSATION_COMPLETED, result.newStatus)
        assertTrue(result.error.isEmpty)

        // Verify that the compensation method was called with both input and result
        assertEquals("order-123", TwoParameterCompensableActions.lastCancelOrderInput)
        assertEquals("order-processed-123", TwoParameterCompensableActions.lastCancelOrderResult)
    }

    @Test
    fun testExecuteCompensationFlow_withTwoParameterCompensation_multipleActions() {
        // Reset static fields before test
        TwoParameterCompensableActions.reset()

        val workflowInstance =
            WorkflowInstance.builder()
                .workflowId("test-workflow-id")
                .workflowClass(TwoParameterCompensableWorkflow::class.java)
                .workflowMethod("processOrderAndPayment")
                .input("test-input")
                .status(WorkflowInstance.Status.ERROR)
                .state(HashMap.empty())
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(ExtraRequestData())
                .result(CompletableFuture())
                .version(1)
                .createdAt(Instant.now())
                .build()

        // Create successful action checkpoints - order processing then payment processing
        val orderCheckpoint =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId("test-workflow-id")
                        .actionClass(TwoParameterCompensableActions::class.java)
                        .actionMethod("processOrder")
                        .build()
                )
                .result(Either.right("order-processed-123"))
                .input("order-123")
                .executionStartTime(Instant.EPOCH)
                .build()

        val paymentCheckpoint =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId("test-workflow-id")
                        .actionClass(TwoParameterCompensableActions::class.java)
                        .actionMethod("processPayment")
                        .build()
                )
                .result(Either.right("payment-id-456"))
                .input("payment-789")
                .executionStartTime(Instant.EPOCH.plusSeconds(10))
                .build()

        val executionContext =
            ExecutionContext.builder()
                .workflow(workflowInstance)
                .clock(mockClock)
                .executorService(executorService)
                .actionCheckpoints(List.of(paymentCheckpoint, orderCheckpoint))
                .build()

        val result =
            compensationExecutor
                .executeCompensationFlow(workflowInstance, executorService, executionContext)
                .join()

        // Verify compensation was successful
        assertEquals(WorkflowInstance.Status.COMPENSATION_COMPLETED, result.newStatus)
        assertTrue(result.error.isEmpty)

        // Verify that both compensation methods were called with correct parameters
        // Payment should be compensated first (reverse chronological order)
        assertEquals("payment-789", TwoParameterCompensableActions.lastRefundPaymentInput)
        assertEquals("payment-id-456", TwoParameterCompensableActions.lastRefundPaymentResult)

        // Then order should be compensated
        assertEquals("order-123", TwoParameterCompensableActions.lastCancelOrderInput)
        assertEquals("order-processed-123", TwoParameterCompensableActions.lastCancelOrderResult)
    }

    @Test
    fun testExecuteCompensationFlow_withTwoParameterCompensation_nullInput() {
        // Reset static fields before test
        TwoParameterCompensableActions.reset()

        val workflowInstance =
            WorkflowInstance.builder()
                .workflowId("test-workflow-id")
                .workflowClass(TwoParameterCompensableWorkflow::class.java)
                .workflowMethod("processOrder")
                .input(null)
                .status(WorkflowInstance.Status.ERROR)
                .state(HashMap.empty())
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(ExtraRequestData())
                .result(CompletableFuture())
                .version(1)
                .createdAt(Instant.now())
                .build()

        // Create successful action checkpoint with null input
        val checkpoint =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId("test-workflow-id")
                        .actionClass(TwoParameterCompensableActions::class.java)
                        .actionMethod("processOrder")
                        .build()
                )
                .result(Either.right("order-processed-123"))
                .input(null)
                .executionStartTime(Instant.EPOCH)
                .build()

        val executionContext =
            ExecutionContext.builder()
                .workflow(workflowInstance)
                .clock(mockClock)
                .executorService(executorService)
                .actionCheckpoints(List.of(checkpoint))
                .build()

        val result =
            compensationExecutor
                .executeCompensationFlow(workflowInstance, executorService, executionContext)
                .join()

        // Verify compensation was successful
        assertEquals(WorkflowInstance.Status.COMPENSATION_COMPLETED, result.newStatus)
        assertTrue(result.error.isEmpty)

        // Verify that the compensation method was called with null input and valid result
        assertEquals(null, TwoParameterCompensableActions.lastCancelOrderInput)
        assertEquals("order-processed-123", TwoParameterCompensableActions.lastCancelOrderResult)
    }

    @Test
    fun testExecuteCompensationFlow_withTwoParameterCompensation_nullResult() {
        // Reset static fields before test
        TwoParameterCompensableActions.reset()

        val workflowInstance =
            WorkflowInstance.builder()
                .workflowId("test-workflow-id")
                .workflowClass(TwoParameterCompensableWorkflow::class.java)
                .workflowMethod("processOrder")
                .input("order-123")
                .status(WorkflowInstance.Status.ERROR)
                .state(HashMap.empty())
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(ExtraRequestData())
                .result(CompletableFuture())
                .version(1)
                .createdAt(Instant.now())
                .build()

        // Create successful action checkpoint with null result
        val checkpoint =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId("test-workflow-id")
                        .actionClass(TwoParameterCompensableActions::class.java)
                        .actionMethod("processOrder")
                        .build()
                )
                .result(Either.right(null))
                .input("order-123")
                .executionStartTime(Instant.EPOCH)
                .build()

        val executionContext =
            ExecutionContext.builder()
                .workflow(workflowInstance)
                .clock(mockClock)
                .executorService(executorService)
                .actionCheckpoints(List.of(checkpoint))
                .build()

        val result =
            compensationExecutor
                .executeCompensationFlow(workflowInstance, executorService, executionContext)
                .join()

        // Verify compensation was successful
        assertEquals(WorkflowInstance.Status.COMPENSATION_COMPLETED, result.newStatus)
        assertTrue(result.error.isEmpty)

        // Verify that the compensation method was called with valid input and null result
        assertEquals("order-123", TwoParameterCompensableActions.lastCancelOrderInput)
        assertEquals(null, TwoParameterCompensableActions.lastCancelOrderResult)
    }

    @Test
    fun testExecuteCompensationFlow_withMixedParameterCompensation() {
        // Reset static fields before test
        MixedParameterCompensableActions.reset()

        val workflowInstance =
            WorkflowInstance.builder()
                .workflowId("test-workflow-id")
                .workflowClass(MixedParameterCompensableWorkflow::class.java)
                .workflowMethod("processOrderAndNotification")
                .input("test-input")
                .status(WorkflowInstance.Status.ERROR)
                .state(HashMap.empty())
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(ExtraRequestData())
                .result(CompletableFuture())
                .version(1)
                .createdAt(Instant.now())
                .build()

        // Create checkpoints for both 1-param and 2-param compensation methods
        val oneParamCheckpoint =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId("test-workflow-id")
                        .actionClass(MixedParameterCompensableActions::class.java)
                        .actionMethod("sendNotification")
                        .build()
                )
                .result(Either.right("notification-sent"))
                .input("notification-message")
                .executionStartTime(Instant.EPOCH)
                .build()

        val twoParamCheckpoint =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId("test-workflow-id")
                        .actionClass(MixedParameterCompensableActions::class.java)
                        .actionMethod("processOrder")
                        .build()
                )
                .result(Either.right("order-processed-456"))
                .input("order-456")
                .executionStartTime(Instant.EPOCH.plusSeconds(10))
                .build()

        val executionContext =
            ExecutionContext.builder()
                .workflow(workflowInstance)
                .clock(mockClock)
                .executorService(executorService)
                .actionCheckpoints(List.of(oneParamCheckpoint, twoParamCheckpoint))
                .build()

        val result =
            compensationExecutor
                .executeCompensationFlow(workflowInstance, executorService, executionContext)
                .join()

        // Verify compensation was successful
        assertEquals(WorkflowInstance.Status.COMPENSATION_COMPLETED, result.newStatus)
        assertTrue(result.error.isEmpty)

        // Verify two-parameter compensation was called correctly (executed first due to reverse order)
        assertEquals("order-456", MixedParameterCompensableActions.lastCancelOrderInput)
        assertEquals("order-processed-456", MixedParameterCompensableActions.lastCancelOrderResult)

        // Verify one-parameter compensation was called correctly (executed second)
        assertEquals(
            "notification-message",
            MixedParameterCompensableActions.lastCancelNotificationInput,
        )
    }

    @Test
    fun testExecuteCompensationFlow_withAsyncActionResult() {
        // Reset static fields before test
        AsyncCompensableActions.reset()

        val workflowInstance =
            WorkflowInstance.builder()
                .workflowId("test-workflow-id")
                .workflowClass(AsyncCompensableWorkflow::class.java)
                .workflowMethod("processOrderAsync")
                .input("async-order-123")
                .status(WorkflowInstance.Status.ERROR)
                .state(HashMap.empty())
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(ExtraRequestData())
                .result(CompletableFuture())
                .version(1)
                .createdAt(Instant.now())
                .build()

        // Create successful action checkpoint with async result
        val checkpoint =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId("test-workflow-id")
                        .actionClass(AsyncCompensableActions::class.java)
                        .actionMethod("processOrderAsync")
                        .build()
                )
                .result(Either.right("async-order-processed-789"))
                .input("async-order-123")
                .executionStartTime(Instant.EPOCH)
                .build()

        val executionContext =
            ExecutionContext.builder()
                .workflow(workflowInstance)
                .clock(mockClock)
                .executorService(executorService)
                .actionCheckpoints(List.of(checkpoint))
                .build()

        val result =
            compensationExecutor
                .executeCompensationFlow(workflowInstance, executorService, executionContext)
                .join()

        // Verify compensation was successful
        assertEquals(WorkflowInstance.Status.COMPENSATION_COMPLETED, result.newStatus)
        assertTrue(result.error.isEmpty)

        // Verify that the compensation method was called with both async input and result
        assertEquals("async-order-123", AsyncCompensableActions.lastCancelOrderInput)
        assertEquals("async-order-processed-789", AsyncCompensableActions.lastCancelOrderResult)
    }

    @Test
    fun testExecuteCompensationFlow_withFailedAction() {
        // Reset static fields before test
        TwoParameterCompensableActions.reset()

        val workflowInstance =
            WorkflowInstance.builder()
                .workflowId("test-workflow-id")
                .workflowClass(TwoParameterCompensableWorkflow::class.java)
                .workflowMethod("processOrder")
                .input("order-123")
                .status(WorkflowInstance.Status.ERROR)
                .state(HashMap.empty())
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(ExtraRequestData())
                .result(CompletableFuture())
                .version(1)
                .createdAt(Instant.now())
                .build()

        // Create failed action checkpoint - should not be compensated
        val failedCheckpoint =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId("test-workflow-id")
                        .actionClass(TwoParameterCompensableActions::class.java)
                        .actionMethod("processOrder")
                        .build()
                )
                .result(Either.left(RuntimeException("Action failed")))
                .input("order-123")
                .executionStartTime(Instant.EPOCH)
                .build()

        val executionContext =
            ExecutionContext.builder()
                .workflow(workflowInstance)
                .clock(mockClock)
                .executorService(executorService)
                .actionCheckpoints(List.of(failedCheckpoint))
                .build()

        val result =
            compensationExecutor
                .executeCompensationFlow(workflowInstance, executorService, executionContext)
                .join()

        // Verify compensation was successful (no actions to compensate)
        assertEquals(WorkflowInstance.Status.COMPENSATION_COMPLETED, result.newStatus)
        assertTrue(result.error.isEmpty)

        // Verify that the compensation method was not called
        assertEquals(null, TwoParameterCompensableActions.lastCancelOrderInput)
        assertEquals(null, TwoParameterCompensableActions.lastCancelOrderResult)
    }

    @Test
    fun testCompensationResult_staticFactoryMethods() {
        // Test success factory method
        val success = CompensationExecutor.CompensationResult.success()
        assertEquals(WorkflowInstance.Status.COMPENSATION_COMPLETED, success.newStatus)
        assertTrue(success.error.isEmpty)

        // Test retryable error factory method
        val retryableError = RetryableError("test retry error")
        val retryableResult =
            CompensationExecutor.CompensationResult.retryableError(retryableError)
        assertEquals(WorkflowInstance.Status.COMPENSATION_IN_PROGRESS, retryableResult.newStatus)
        assertTrue(retryableResult.error.isDefined)
        assertEquals(retryableError, retryableResult.error.get())

        // Test non-retryable error factory method
        val nonRetryableError = NonRetryableError("test non-retry error")
        val nonRetryableResult =
            CompensationExecutor.CompensationResult.nonRetryableError(nonRetryableError)
        assertEquals(WorkflowInstance.Status.COMPENSATION_ERROR, nonRetryableResult.newStatus)
        assertTrue(nonRetryableResult.error.isDefined)
        assertEquals(nonRetryableError, nonRetryableResult.error.get())
    }

    private class TestWorkflow : Workflow() {
        @WorkflowMethod
        fun hello(name: String): String = "Hello $name"
    }

    // Test Actions class with compensable actions
    class CompensableActions : Actions() {
        @Execute
        fun processPayment(paymentId: String): String = "payment-processed"

        @Compensate(forExecute = "processPayment")
        fun refundPayment(paymentId: String) {
            // Compensation logic
        }

        @Execute
        fun sendNotification(message: String): String {
            throw RetryableError("notification service down")
        }

        @Compensate(forExecute = "sendNotification")
        fun cancelNotification(message: String) {
            throw NonRetryableError("cancellation failed")
        }

        @Execute
        fun sendNotification2(message: String): String {
            throw RetryableError("notification service down")
        }

        @Compensate(forExecute = "sendNotification2")
        fun cancelNotification2(message: String) {
            throw RetryableError("cancellation failed")
        }
    }

    // Test Actions class with two-parameter compensation methods
    class TwoParameterCompensableActions : Actions() {
        @Execute
        fun processOrder(orderId: String): String = "order-processed-123"

        @Compensate(forExecute = "processOrder")
        fun cancelOrder(
            orderId: String?,
            result: String?
        ) {
            // Store the arguments for test verification
            lastCancelOrderInput = orderId
            lastCancelOrderResult = result
        }

        @Execute
        fun processPayment(paymentId: String): String = "payment-id-456"

        @Compensate(forExecute = "processPayment")
        fun refundPayment(
            paymentId: String?,
            paymentResult: String?
        ) {
            // Store the arguments for test verification
            lastRefundPaymentInput = paymentId
            lastRefundPaymentResult = paymentResult
        }

        companion object {
            @JvmStatic var lastCancelOrderInput: String? = null

            @JvmStatic var lastCancelOrderResult: String? = null

            @JvmStatic var lastRefundPaymentInput: String? = null

            @JvmStatic var lastRefundPaymentResult: String? = null

            // Reset static fields for clean test state
            @JvmStatic
            fun reset() {
                lastCancelOrderInput = null
                lastCancelOrderResult = null
                lastRefundPaymentInput = null
                lastRefundPaymentResult = null
            }
        }
    }

    // Test Workflow class that uses compensable actions
    class CompensableWorkflow : Workflow() {
        private val actions = CompensableActions()

        @WorkflowMethod
        fun processOrder(orderId: String): String = actions.processPayment(orderId)

        @WorkflowMethod
        fun notifyUser(message: String): String = actions.sendNotification(message)
    }

    // Test Workflow class that uses two-parameter compensable actions
    class TwoParameterCompensableWorkflow : Workflow() {
        private val actions = TwoParameterCompensableActions()

        @WorkflowMethod
        fun processOrder(orderId: String): String = actions.processOrder(orderId)

        @WorkflowMethod
        fun processOrderAndPayment(input: String): String {
            val orderResult = actions.processOrder("order-123")
            val paymentResult = actions.processPayment("payment-789")
            return "$orderResult,$paymentResult"
        }
    }

    // Test Actions class with mixed 1-param and 2-param compensation methods
    class MixedParameterCompensableActions : Actions() {
        @Execute
        fun processOrder(orderId: String): String = "order-processed-456"

        @Compensate(forExecute = "processOrder")
        fun cancelOrder(
            orderId: String?,
            result: String?
        ) {
            // Two-parameter compensation method
            lastCancelOrderInput = orderId
            lastCancelOrderResult = result
        }

        @Execute
        fun sendNotification(message: String): String = "notification-sent"

        @Compensate(forExecute = "sendNotification")
        fun cancelNotification(message: String?) {
            // One-parameter compensation method
            lastCancelNotificationInput = message
        }

        companion object {
            @JvmStatic var lastCancelOrderInput: String? = null

            @JvmStatic var lastCancelOrderResult: String? = null

            @JvmStatic var lastCancelNotificationInput: String? = null

            // Reset static fields for clean test state
            @JvmStatic
            fun reset() {
                lastCancelOrderInput = null
                lastCancelOrderResult = null
                lastCancelNotificationInput = null
            }
        }
    }

    // Test Workflow class that uses mixed parameter compensable actions
    class MixedParameterCompensableWorkflow : Workflow() {
        private val actions = MixedParameterCompensableActions()

        @WorkflowMethod
        fun processOrderAndNotification(input: String): String {
            val notificationResult = actions.sendNotification("notification-message")
            val orderResult = actions.processOrder("order-456")
            return "$notificationResult,$orderResult"
        }
    }

    // Test Actions class with async action and two-parameter compensation
    class AsyncCompensableActions : Actions() {
        @Execute
        fun processOrderAsync(orderId: String): CompletableFuture<String> = CompletableFuture.completedFuture("async-order-processed-789")

        @Compensate(forExecute = "processOrderAsync")
        fun cancelOrder(
            orderId: String?,
            result: String?
        ) {
            // Compensation method for async action
            lastCancelOrderInput = orderId
            lastCancelOrderResult = result
        }

        companion object {
            @JvmStatic var lastCancelOrderInput: String? = null

            @JvmStatic var lastCancelOrderResult: String? = null

            // Reset static fields for clean test state
            @JvmStatic
            fun reset() {
                lastCancelOrderInput = null
                lastCancelOrderResult = null
            }
        }
    }

    // Test Workflow class that uses async compensable actions
    class AsyncCompensableWorkflow : Workflow() {
        private val actions = AsyncCompensableActions()

        @WorkflowMethod
        fun processOrderAsync(orderId: String): String = actions.processOrderAsync(orderId).join()
    }
}
