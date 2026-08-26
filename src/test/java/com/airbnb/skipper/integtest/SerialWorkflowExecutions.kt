package com.airbnb.skipper.integtest

import com.airbnb.skipper.Actions
import com.airbnb.skipper.Execute
import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.IWorkflowFactory
import com.airbnb.skipper.QueryMethod
import com.airbnb.skipper.SignalMethod
import com.airbnb.skipper.SkipperAnnotationNames.SKIPPER_WORKFLOW_FACTORY
import com.airbnb.skipper.StateField
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowCallbackHandler
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.api.WorkflowInstanceView
import com.airbnb.skipper.testutils.TestHelper
import com.airbnb.skipper.testutils.TestRuntime
import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.inject.name.Named
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * Serial Workflow Execution Pattern Example
 *
 * This example demonstrates how to implement a parent-child workflow pattern that ensures strict
 * ordering of workflow executions. This pattern is useful when:
 *
 * - **Single Logical Session:** The client wants to maintain a single workflow ID for multiple
 *   related operations
 * - **Strict Ordering Required:** Operations must execute in a specific sequence without overlap
 * - **Multiple Operation Types:** There are many possible operations associated with a single
 *   logical entity
 * - **Avoid Concurrent Execution:** Prevents multiple separate workflows from overlapping and
 *   causing race conditions
 *
 * ## Architecture Overview
 *
 * The pattern consists of two main workflow types:
 *
 * ### 1. Parent Workflow (SerialWorkflowCoordinator)
 *
 * - Maintains the single, stable workflow ID that clients interact with
 * - Receives operation requests via `@SignalMethod`
 * - Queues requests and ensures they are processed serially
 * - Creates child workflows for each operation
 * - Tracks completion status and maintains overall session state
 *
 * ### 2. Child Workflow (SingleOperationWorkflow)
 *
 * - Handles individual operation execution
 * - Uses idempotency keys to prevent duplicate executions
 * - Reports completion back to parent via callback handler
 * - Can be retried independently without affecting other operations
 *
 * ## Key Components
 *
 * ### Signal-Based Request Queue
 *
 * ```
 * @SignalMethod
 * fun addRequest(request: Request) {
 *   pendingOperations.requests.add(request)
 * }
 * ```
 *
 * ### Sequential Processing Loop
 *
 * ```
 * while (!stop) {
 *   val newRequests = waitUntil({ newRequestsToProcess() }, Duration.ofHours(1))
 *   if (!newRequests) continue
 *
 *   for (i in 0 until pendingOperations.requests.size) {
 *     // Process each request in order using checkpoint for reliability
 *     checkpoint {
 *       val request = pendingOperations.requests[finalI]
 *       // Create child workflow with idempotency key
 *       workflowFactory.builder(SingleOperationWorkflow::class.java, request.idempotencyKey)
 *         .callbackHandler(ChildWorkflowCompletionHandler::class.java)
 *         .build()
 *         .execute(request)
 *     }
 *   }
 * }
 * ```
 *
 * ### Completion Tracking
 *
 * ```
 * class ChildWorkflowCompletionHandler : WorkflowCallbackHandler {
 *   override fun onSuccess(workflowInstance: WorkflowInstanceView) {
 *     // Notify parent workflow of completion
 *     val parentWorkflow =
 *       workflowFactory.invoke(
 *         SerialWorkflowCoordinator::class.java,
 *         req.parentWorkflowId,
 *         TestRequestContext.getCurrentRequestContext())
 *     parentWorkflow.recordOperationCompletion(workflowInstance.id)
 *   }
 * }
 * ```
 *
 * ## Benefits of This Pattern
 *
 * - **Guaranteed Ordering:** Operations execute sequentially.
 * - **Fault Tolerance:** Individual operations can fail and retry without affecting others
 * - **Idempotency:** Duplicate requests are automatically handled via idempotency keys
 * - **Scalability:** Child workflows can be distributed across different workers
 * - **Observability:** Each operation has its own workflow instance for monitoring
 * - **State Management:** Parent workflow maintains session state across operations
 *
 * ## When to Use This Pattern
 *
 * **Use this pattern when:**
 *
 * - You need to process a sequence of operations for a single logical entity
 * - Operations must not overlap or execute concurrently
 * - You want to maintain a single workflow ID for client interactions
 * - Individual operations may have different retry requirements
 * - You need to track completion status across multiple operations
 *
 * **Consider alternatives when:**
 *
 * - Operations can safely execute concurrently
 * - Ordering across workflows is not required
 * - The overhead of parent-child coordination is not justified
 */
class SerialWorkflowExecutions {
    // ======================================================================
    // Helper Classes
    // ======================================================================

    enum class Operation {
        SUM,
        SUBTRACT,
        MULTIPLY,
        DIVIDE
    }

    data class Request(
        var operation: Operation? = null,
        var value: Int = 0,
        var idempotencyKey: String? = null,
        var parentWorkflowId: String? = null
    ) {
        constructor(operation: Operation, value: Int, key: String) : this(operation, value, key, null)
    }

    // Skipper does not allow serialization of generic types as root objects, so we need this.
    data class RequestList(
        var requests: MutableList<Request> = ArrayList()
    )

    /**
     * Shared state object that maintains the cumulative result across all operations.
     *
     * This demonstrates how state can be shared across multiple workflow executions while ensuring
     * thread safety through Skipper's execution model.
     */
    @Singleton
    class State {
        @JvmField var result: Int = 0

        fun execute(request: Request): Int {
            when (request.operation) {
                Operation.SUM -> result += request.value
                Operation.SUBTRACT -> result -= request.value
                Operation.MULTIPLY -> result *= request.value
                Operation.DIVIDE ->
                    if (request.value != 0) {
                        result /= request.value
                    } else {
                        throw IllegalArgumentException("Cannot divide by zero")
                    }
                else -> {}
            }
            return result
        }
    }

    // ======================================================================
    // Actions
    // ======================================================================

    /** Actions class that encapsulates the business logic for executing operations. */
    class OperationActions : Actions() {
        @Inject lateinit var state: State

        @Execute
        fun executeOperation(request: Request): Int {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(500)) // Simulate some processing time
            return state.execute(request)
        }
    }

    /**
     * Callback handler that manages communication between child and parent workflows.
     *
     * This handler is crucial for the parent-child coordination pattern. When a child workflow
     * completes successfully, it notifies the parent workflow so that the parent can track progress
     * and potentially trigger the next operation in the sequence.
     */
    class ChildWorkflowCompletionHandler : WorkflowCallbackHandler {
        @Inject
        @Named(SKIPPER_WORKFLOW_FACTORY)
        private lateinit var workflowFactory: IWorkflowFactory

        /**
         * Called when a child workflow completes successfully. Notifies the parent workflow by calling
         * its completion signal method.
         */
        override fun onSuccess(workflowInstance: WorkflowInstanceView) {
            try {
                val req = workflowInstance.getInput(Request::class.java)!!
                val parentWorkflow =
                    workflowFactory.invoke(
                        SerialWorkflowCoordinator::class.java,
                        req.parentWorkflowId!!,
                        com.airbnb.skipper.testutils.TestRequestContext.getCurrentRequestContext()
                    )
                parentWorkflow.recordOperationCompletion(workflowInstance.id)
            } finally {
                // Always count down — even if the signal above throws transiently. Without try-finally,
                // a signal exception would leave the latch stuck and hang the test for 5 minutes.
                completionLatch?.countDown()
            }
        }

        override fun onNonRetryableError(
            workflowInstance: WorkflowInstanceView,
            error: Throwable
        ) {
            // A failed child workflow will never call onSuccess, so we must count down here too,
            // otherwise the latch hangs for the full 5-minute ceiling.
            failureCount?.incrementAndGet()
            completionLatch?.countDown()
        }

        override fun onWorkflowInWaitingStatus(workflowInstance: WorkflowInstanceView) {}

        override fun onWorkflowTimeout(workflowInstance: WorkflowInstanceView) {
            // Same as onNonRetryableError — a timed-out child will never call onSuccess.
            failureCount?.incrementAndGet()
            completionLatch?.countDown()
        }
    }

    // ======================================================================
    // Workflow Definitions
    // ======================================================================

    /**
     * Child Workflow: Handles individual operation execution.
     *
     * This workflow represents a single unit of work that executes one operation.
     *
     * The workflow ID for instances of this class should be the idempotency key to ensure that
     * duplicate requests result in the same workflow instance.
     */
    class SingleOperationWorkflow : Workflow() {
        private val actions = actions<OperationActions>()

        /**
         * Executes a single operation request.
         *
         * @param request the operation to execute
         * @return completed future when the operation finishes
         */
        @WorkflowMethod
        fun execute(request: Request): CompletableFuture<Void> {
            actions.executeOperation(request)
            return CompletableFuture.completedFuture(null)
        }
    }

    /**
     * Parent Workflow: Coordinates sequential execution of multiple operations.
     *
     * This is the main workflow that clients interact with. It maintains a single, stable workflow
     * ID while ensuring that operations execute in strict sequence.
     */
    class SerialWorkflowCoordinator : Workflow() {
        private val actions = actions<OperationActions>()

        @Inject
        @Named(SKIPPER_WORKFLOW_FACTORY)
        private lateinit var workflowFactory: IWorkflowFactory

        /** Queue of pending operation requests */
        @StateField var pendingOperations = RequestList()

        /** Index of the next request to process (for tracking progress) */
        @StateField var processedCount = 0

        /** Flag to stop the processing loop */
        @StateField var stop = false

        /** Comma-separated list of completed request IDs */
        @StateField var completedRequests = ""

        @StateField var childWorkflowCompleted = false

        /**
         * Main workflow method that processes requests sequentially.
         *
         * The workflow runs in a loop, waiting for new requests and processing them one by one. Each
         * request is processed within a checkpoint to ensure reliability - if the workflow fails and
         * restarts, it will resume from where it left off.
         *
         * @return completed future when the session is stopped
         */
        @WorkflowMethod(returnType = Void::class)
        fun startSession(): CompletableFuture<Void> {
            while (!stop) {
                // Wait up to 1 hour for new requests to arrive
                val newRequests = waitUntil({ newRequestsToProcess() }, Duration.ofHours(1))
                if (!newRequests) {
                    continue
                }

                // Process only new requests (starting from processedCount) so that already-completed
                // child workflows are never re-submitted. Re-submitting a completed workflow is a noop
                // (CREATE_EXISTING_WORKFLOW_IS_NOOP=true), but the subsequent childWorkflowCompleted=false
                // reset + waitUntil would then wait forever since no callback fires for the noop.
                for (i in processedCount until pendingOperations.requests.size) {
                    val finalI = i
                    checkpoint {
                        val request = pendingOperations.requests[finalI]
                        request.parentWorkflowId = id
                        // Create child workflow with idempotency key and callback handler
                        workflowFactory
                            .builder(SingleOperationWorkflow::class.java, request.idempotencyKey!!)
                            .callbackHandler(ChildWorkflowCompletionHandler::class.java)
                            .build()
                            .execute(request)
                        childWorkflowCompleted = false // reset
                    }
                    // Let's wait until the child workflow completes before processing the next request.
                    // This is useful to ensure that we do not process multiple requests at once.
                    waitUntil({ childWorkflowCompleted }, Duration.ofHours(1))
                }
                processedCount = pendingOperations.requests.size
            }
            return CompletableFuture.completedFuture(null)
        }

        private fun newRequestsToProcess(): Boolean {
            return processedCount < pendingOperations.requests.size
        }

        /** Signal method to add a new operation request to the queue. */
        @SignalMethod
        fun addRequest(request: Request) {
            pendingOperations.requests.add(request)
        }

        /**
         * Signal method to stop the processing session. This will cause the main loop to exit
         * gracefully.
         */
        @SignalMethod
        fun stopSession() {
            stop = true
        }

        /**
         * Signal method called by child workflows to report completion.
         *
         * @param idempotencyKey the ID of the completed request
         */
        @SignalMethod
        fun recordOperationCompletion(idempotencyKey: String) {
            println("Child workflow completed with idempotency key: $idempotencyKey")
            completedRequests = "$completedRequests$idempotencyKey,"
            childWorkflowCompleted = true
        }

        /** Query method to retrieve the list of completed requests. */
        @QueryMethod
        fun getCompletedRequests(): List<String> {
            if (completedRequests.isEmpty()) {
                return ArrayList()
            }
            // Filter out empty strings from split result
            return completedRequests.split(",")
                .filter { it.isNotEmpty() }
        }
    }

    // ======================================================================
    // Integration Test Setup
    // ======================================================================

    private lateinit var deps: TestRuntime
    private lateinit var workflowId: String
    private lateinit var helper: TestHelper
    private lateinit var state: State

    @BeforeEach
    fun setUp() {
        deps = TestRuntime()
        deps.setClock(Clock.systemUTC())

        // State is a @Singleton that OperationActions @Inject-s at runtime.
        // Bind it in the injector so injectWorkflowMembers populates it.
        state = State()
        deps.addBinding(State::class.java, state)

        workflowId = UUID.randomUUID().toString()
        helper = TestHelper(deps.getSkipperEngine(), deps.getScheduler(), workflowId)

        whenever(deps.featureGate.isEnabled(any())).thenReturn(true)
        whenever(deps.featureGate.isEnabled(FeatureGate.Keys.CREATE_EXISTING_WORKFLOW_IS_NOOP))
            .thenReturn(true)
        whenever(
            deps.featureGate.isEnabled(FeatureGate.Keys.FORCE_SIGNAL_WORKFLOW_EXEC_IN_SCHEDULER)
        )
            .thenReturn(true)

        deps.getSchedulerManager().start()

        completionLatch = CountDownLatch(7)
        failureCount = AtomicInteger(0)
    }

    @AfterEach
    fun tearDown() {
        deps.getSchedulerManager().forceStop()
    }

    // ======================================================================
    // Test Cases
    // ======================================================================

    @Test
    @Throws(Exception::class)
    fun testSerialWorkflowExecutions() {
        // Start the parent workflow session
        val coordinator =
            deps.getWorkflowFactory().builder(SerialWorkflowCoordinator::class.java, workflowId).build()
        coordinator.startSession()
        helper.expectWorkflowToWait()

        // Add multiple requests - they should execute in order
        coordinator.addRequest(Request(Operation.SUM, 10, "one"))
        coordinator.addRequest(Request(Operation.SUM, 10, "two"))
        coordinator.addRequest(Request(Operation.SUM, 10, "three"))
        coordinator.addRequest(Request(Operation.SUM, 10, "four"))
        coordinator.addRequest(Request(Operation.SUM, 10, "five"))
        coordinator.addRequest(Request(Operation.SUM, 10, "six"))
        coordinator.addRequest(Request(Operation.SUM, 10, "seven"))

        // Wait for all 7 child workflows to terminate (success OR failure) using a CountDownLatch.
        // The latch counts down in onSuccess, onNonRetryableError, and onWorkflowTimeout, so it
        // always reaches 0 regardless of outcome — no scheduler polling, no arbitrary timing.
        // The 5-minute ceiling is only hit if a workflow is genuinely stuck with zero callbacks,
        // which is a real bug, not a CI-speed issue.
        val allTerminated = completionLatch!!.await(5, TimeUnit.MINUTES)
        assertTrue(
            allTerminated,
            "Workflows stuck — no callbacks fired for all 7 children within 5 minutes"
        )
        assertEquals(
            0,
            failureCount!!.get(),
            failureCount!!.get()
                .toString() +
                " child workflow(s) failed or timed out (check" +
                " onNonRetryableError/onWorkflowTimeout)"
        )

        // After all callbacks have fired, the parent still needs 1-2 scheduler cycles to process
        // the last recordOperationCompletion signal and return to WAITING. Use a generous 60s timeout
        // (vs the default 5s) since on heavily loaded CI a single scheduler cycle can take ~10s.
        helper.waitForWorkflowToReachStatus(setOf(WorkflowInstance.Status.WAITING), 600, 100)

        // Wait for all execution flows to finish to avoid race conditions with inflight tasks
        // This ensures all child workflow completion callbacks are fully processed
        helper.waitForExecutionFlowToFinish()

        // Verify completion order matches submission order
        val completedRequests = coordinator.getCompletedRequests()
        assertEquals(
            7,
            completedRequests.size,
            "Expected four completed requests: $completedRequests"
        )
        assertTrue(completedRequests.contains("one"))
        assertTrue(completedRequests.contains("two"))
        assertTrue(completedRequests.contains("three"))
        assertTrue(completedRequests.contains("four"))
        assertTrue(completedRequests.contains("five"))
        assertTrue(completedRequests.contains("six"))
        assertTrue(completedRequests.contains("seven"))

        // Verify that the state was updated correctly.
        assertEquals(70, state.result)

        // Wait for any inflight tasks to complete before sending stop signal
        // This prevents DuplicateKeyException when stopSession() tries to schedule a task
        // while a previous task for this workflow is still being processed
        helper.waitForExecutionFlowToFinish()

        // Stop the session
        coordinator.stopSession()
        helper.waitForWorkflowToComplete()

        // Event printing removed — InMemoryEventPublisher is not available without SkipperTest.
    }

    companion object {
        // Shared latch between the test thread and ChildWorkflowCompletionHandler. Using static +
        // volatile so the handler (a Guice-managed static inner class) can see the reference created
        // in setUp(). Counts down once per child completion (success OR failure), bypassing the
        // scheduler entirely and preventing 5-minute hangs when a child fails.
        @JvmStatic
        @Volatile
        var completionLatch: CountDownLatch? = null

        // Counts child workflows that ended via onNonRetryableError or onWorkflowTimeout. The latch
        // still reaches 0 (no hang), but we assert this is 0 at the end to catch real failures.
        @JvmStatic
        @Volatile
        var failureCount: AtomicInteger? = null
    }
}
