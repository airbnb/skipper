package com.airbnb.skipper.internal

import com.airbnb.skipper.QueryMethod
import com.airbnb.skipper.SignalMethod
import com.airbnb.skipper.StateField
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.testutils.TestRuntime
import io.vavr.collection.HashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for suspend-function handling in [WorkflowExecutor].
 *
 * These tests verify that [WorkflowExecutor.executeWorkflowMethod],
 * [WorkflowExecutor.executeSignalMethod], and [WorkflowExecutor.executeQueryMethod]
 * correctly handle Kotlin suspend functions:
 * - `resultIsAsync` is `false` for suspend methods (the CF is our internal bridge,
 *   not the declared return type)
 * - suspend methods complete successfully through the CompletableFuture chain
 * - suspend query methods unwrap the internal CompletableFuture
 * - non-suspend CF-returning query methods preserve the CompletableFuture as-is
 */
class WorkflowExecutorSuspendTest {
    private lateinit var workflowExecutor: WorkflowExecutor
    private lateinit var executorService: ExecutorService
    private lateinit var mockClock: java.time.Clock
    private lateinit var executionContext: ExecutionContext

    @BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        val runtime = deps.getRuntime()
        mockClock = deps.config.utcClock
        executorService = deps.config.mainThreadPool
        workflowExecutor = runtime.workflowExecutor.get()

        executionContext = ExecutionContext.builder()
            .workflow(TestUtils.getWorkflowInstance())
            .clock(mockClock)
            .executorService(executorService)
            .build()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Workflow definitions
    // ──────────────────────────────────────────────────────────────────────────

    /** Workflow with a suspend @WorkflowMethod. */
    open class SuspendGreeter : Workflow() {
        @WorkflowMethod
        suspend fun greet(name: String): String = "Hello, $name!"
    }

    /** Workflow with a suspend @SignalMethod. */
    open class SuspendSignalWorkflow : Workflow() {
        @StateField var flag: Boolean = false

        @WorkflowMethod
        fun run(): CompletableFuture<String> {
            waitUntil { flag }
            return CompletableFuture.completedFuture("done")
        }

        @SignalMethod
        suspend fun setFlag(value: Boolean) {
            flag = value
        }
    }

    /** Workflow with both suspend and non-suspend @QueryMethod. */
    open class QueryWorkflow : Workflow() {
        @StateField var counter: Int = 42

        @WorkflowMethod
        fun run(): String = "running"

        @QueryMethod
        suspend fun getCounterSuspend(): Int = counter

        @QueryMethod
        fun getCounterAsCF(): CompletableFuture<Int> = CompletableFuture.completedFuture(counter)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("suspend workflow method sets resultIsAsync to false")
    fun `suspend workflow method sets resultIsAsync to false`() {
        val instance = TestUtils.getWorkflowInstance().toBuilder()
            .workflowClass(SuspendGreeter::class.java)
            .workflowMethod("greet")
            .input("Alice")
            .build()

        val result = workflowExecutor
            .executeWorkflowMethod(instance, executorService, executionContext)
            .join()

        // The CF returned by invokeSuspendFunctionAsync is our internal bridge,
        // NOT the method's declared return type. So resultIsAsync must be false.
        assertThat(result.isResultIsAsync).isFalse()
    }

    @Test
    @DisplayName("suspend workflow method completes via CompletableFuture chain")
    fun `suspend workflow method completes via CompletableFuture chain`() {
        val instance = TestUtils.getWorkflowInstance().toBuilder()
            .workflowClass(SuspendGreeter::class.java)
            .workflowMethod("greet")
            .input("Bob")
            .build()

        val result = workflowExecutor
            .executeWorkflowMethod(instance, executorService, executionContext)
            .join()

        assertThat(result.result!!.get()).isEqualTo("Hello, Bob!")
    }

    @Test
    @DisplayName("suspend signal method completes via CompletableFuture chain")
    fun `suspend signal method completes via CompletableFuture chain`() {
        val instance = TestUtils.getWorkflowInstance().toBuilder()
            .workflowClass(SuspendSignalWorkflow::class.java)
            .workflowMethod("run")
            .build()

        val result = workflowExecutor
            .executeSignalMethod(instance, executorService, executionContext, "setFlag", true)
            .join()

        // Signal completes without error; the state field should be updated
        assertThat(result.newState!!.get("flag").get()).isEqualTo(true)
    }

    @Test
    @DisplayName("suspend query method unwraps CompletableFuture")
    fun `suspend query method unwraps CompletableFuture`() {
        val queryResult = workflowExecutor.executeQueryMethod(
            QueryWorkflow::class.java,
            "test-workflow-id",
            HashMap.of("counter", 42),
            "getCounterSuspend",
            null,
        )

        // For suspend query methods, the internal CF from invokeSuspendFunctionAsync
        // must be unwrapped so the caller gets the raw value.
        assertThat(queryResult).isNotInstanceOf(CompletableFuture::class.java)
        assertThat(queryResult).isEqualTo(42)
    }

    @Test
    @DisplayName("non-suspend CF-returning query method preserves CompletableFuture")
    fun `non-suspend CF-returning query method preserves CompletableFuture`() {
        val queryResult = workflowExecutor.executeQueryMethod(
            QueryWorkflow::class.java,
            "test-workflow-id",
            HashMap.of("counter", 42),
            "getCounterAsCF",
            null,
        )

        // Non-suspend methods that return CompletableFuture must NOT be unwrapped.
        // This is the regression guard.
        assertThat(queryResult).isInstanceOf(CompletableFuture::class.java)
        @Suppress("UNCHECKED_CAST")
        val future = queryResult as CompletableFuture<Int>
        assertThat(future.join()).isEqualTo(42)
    }
}
