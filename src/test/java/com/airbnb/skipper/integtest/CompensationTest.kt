package com.airbnb.skipper.integtest

import com.airbnb.skipper.Actions
import com.airbnb.skipper.CheckpointMode
import com.airbnb.skipper.Compensate
import com.airbnb.skipper.Execute
import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.FixedRetryStrategy
import com.airbnb.skipper.InvocationBuilder
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.RetryStrategy
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.SkipperError
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowCallbackHandler
import com.airbnb.skipper.WorkflowFactory
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.WorkflowsService
import com.airbnb.skipper.api.WorkflowInstanceView
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.SkipperSchedulerManager
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.testutils.TestHelper
import com.airbnb.skipper.testutils.TestRuntime
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertTrue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.mockito.Mockito.lenient
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@Execution(ExecutionMode.SAME_THREAD)
class CompensationTest {
    private lateinit var ledger: Ledger
    private val demoClient: DemoClient = mock<DemoClient>()
    private lateinit var workflowsService: WorkflowsService
    private val compensationRetryStrategy: RetryStrategy = FixedRetryStrategy(Duration.ZERO, 3)
    private val retryStrategy: RetryStrategy = FixedRetryStrategy(Duration.ofMillis(100), 3)
    private lateinit var scheduler: Scheduler
    private val callbackHandler: DemoCallbackHandler = mock<DemoCallbackHandler>()

    private lateinit var workflowFactory: WorkflowFactory
    private lateinit var skipperSchedulerManager: SkipperSchedulerManager
    private lateinit var featureGate: FeatureGate
    private lateinit var skipperEngine: SkipperEngine

    protected lateinit var helper: TestHelper
    protected lateinit var workflowId: String

    @BeforeEach
    fun setUp() {
        val clock = Clock.systemUTC()
        ledger = Ledger()

        val deps = TestRuntime()
        deps.setClock(clock)
        deps.config.compensationRetryStrategy = compensationRetryStrategy
        deps.addBinding(DemoClient::class.java, demoClient)
        deps.addBinding(Ledger::class.java, ledger)
        deps.addBinding(RetryStrategy::class.java, retryStrategy)
        deps.addBinding(DemoCallbackHandler::class.java, callbackHandler)

        val runtime = deps.runtime
        workflowsService = runtime.workflowsService.get()
        scheduler = runtime.scheduler.get()
        workflowFactory = runtime.workflowFactory.get()
        skipperSchedulerManager = runtime.skipperSchedulerManager.get()
        featureGate = runtime.featureGate.get()
        skipperEngine = runtime.skipperEngine.get()

        workflowId = UUID.randomUUID().toString()
        helper = TestHelper(skipperEngine, scheduler, workflowId)

        whenever(featureGate.isEnabled(FeatureGate.Keys.CREATE_EXISTING_WORKFLOW_IS_NOOP))
            .thenReturn(true)
        whenever(featureGate.isEnabled(FeatureGate.Keys.FORCE_SIGNAL_WORKFLOW_EXEC_IN_SCHEDULER))
            .thenReturn(true)

        skipperSchedulerManager.start()

        resetTestState()
    }

    @AfterEach
    fun tearDown() {
        // Stop the scheduler so its polling threads do not leak into other test classes that share
        // the same JVM and embedded MySQL (they would otherwise steal each other's scheduler tasks).
        skipperSchedulerManager.forceStop()
    }

    fun <T : Workflow> workflowBuilder(workflowClass: Class<T>): InvocationBuilder<T> {
        return workflowFactory
            .builder(workflowClass, workflowId)
            .requestContext(com.airbnb.skipper.testutils.TestRequestContext.getCurrentRequestContext())
    }

    private fun resetTestState() {
        // Reset ledger balances to initial state
        ledger.resetToInitialState()
        // Reset mock behaviors to clean state
        reset(demoClient)
        // Reset callback handler
        reset(callbackHandler)
        // Ensure mock is properly configured with lenient behavior
        lenient().doNothing().whenever(demoClient).debitHook()
        lenient().doNothing().whenever(demoClient).creditHook()
        lenient().doNothing().whenever(demoClient).undoDebitHook()
        lenient().doNothing().whenever(demoClient).undoCreditHook()
    }

    protected fun printEvents(vararg workflowId: String) {
        for (id in workflowId) {
            println("Events for workflow: $id")
            println("--------------------------")
        }
    }

    @Test
    @Execution(ExecutionMode.SAME_THREAD)
    fun testCompensationFlowHappyPath() {
        var currentWorkflow: TransferRequestWorkflow

        // First create a workflow that is expected to succeed
        // Create a helper for the first workflow to properly wait for completion
        val firstWorkflowId = workflowId
        val firstHelper = TestHelper(skipperEngine, scheduler, firstWorkflowId)
        currentWorkflow =
            workflowBuilder(TransferRequestWorkflow::class.java)
                .callbackHandler(DemoCallbackHandler::class.java)
                .build()
        currentWorkflow.transferRequest(TransferRequest(1, 2, 100, 1000, null))
        // Wait for the first workflow to complete before asserting
        firstHelper.waitForWorkflowToComplete()
        firstHelper.waitForExecutionFlowToFinish()
        assertThat(ledger.getBalances()[1]).isEqualTo(0)
        assertThat(ledger.getBalances()[2]).isEqualTo(199)
        assertThat(ledger.getBalances()[1000]).isEqualTo(1) // system account should have 1 as fee

        // Now create a workflow that will fail and trigger compensation
        doThrow(NonRetryableError("unable to perform")).whenever(demoClient).creditHook()
        workflowId = UUID.randomUUID().toString() // reset the workflowID
        helper = TestHelper(skipperEngine, scheduler, workflowId)
        currentWorkflow =
            workflowBuilder(TransferRequestWorkflow::class.java)
                .callbackHandler(DemoCallbackHandler::class.java)
                .build()

        val finalCurrentWorkflow = currentWorkflow
        try {
            val error =
                assertThrows(
                    NonRetryableError::class.java
                ) {
                    finalCurrentWorkflow.transferRequest(TransferRequest(1, 2, 100, 0, null))
                }
            assertThat(error.message).contains("unable to perform")
        } catch (error: Throwable) {
            println("Compensation test failed for workflow: $workflowId")
            throw error
        }

        helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.COMPENSATION_COMPLETED)

        // Verify that balances have been restored
        assertThat(ledger.getBalances()[1]).isEqualTo(0)
        assertThat(ledger.getBalances()[2]).isEqualTo(199)

        // Verify that compensation completion callback was called
        helper.waitForExecutionFlowToFinish() // Ensure all callbacks have been processed
        // Use timeout() because compensation callbacks are invoked asynchronously by the
        // compensation task (whose ID is workflowId-compensation, not workflowId), so
        // waitForExecutionFlowToFinish cannot detect it.
        verify(callbackHandler, timeout(5000)).onCompensationCompleted(any<WorkflowInstanceView>())

        // Now try creating the same workflow again, it should fail with the same error message.
        val error =
            assertThrows(
                NonRetryableError::class.java
            ) {
                finalCurrentWorkflow.transferRequest(TransferRequest(1, 2, 100, 0, null))
            }

        printEvents(workflowId)
    }

    @Execution(ExecutionMode.SAME_THREAD)
    @Test
    fun testCompensationFlowErrorScenarios() {
        var currentWorkflow: TransferRequestWorkflow
        for (errorType in CompensationErrorType.values()) {
            println("Testing compensation failure with $errorType")
            resetTestState() // This resets callbackHandler mock, so verify times(1) not times(n)

            workflowId = UUID.randomUUID().toString()
            helper = TestHelper(skipperEngine, scheduler, workflowId)
            currentWorkflow =
                workflowBuilder(TransferRequestWorkflow::class.java)
                    .callbackHandler(DemoCallbackHandler::class.java)
                    .build()

            // Configure workflow to fail, and compensation to fail with specific error type
            doThrow(NonRetryableError("unable to perform action")).whenever(demoClient).creditHook()
            doThrow(errorType.createError()).whenever(demoClient).undoDebitHook()

            val finalCurrentWorkflow1 = currentWorkflow
            val error =
                assertThrows(
                    NonRetryableError::class.java
                ) {
                    finalCurrentWorkflow1.transferRequest(TransferRequest(1, 2, 123, 0, null))
                }
            assertThat(error.message).contains("unable to perform action")

            try {
                helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.COMPENSATION_ERROR)

                // Verify that compensation error callback was called
                helper.waitForExecutionFlowToFinish() // Ensure all callbacks have been processed
                // Use timeout() because callback may be processed asynchronously
                // times(1) because resetTestState() resets the mock between iterations
                verify(callbackHandler, timeout(5000).times(1))
                    .onCompensationError(any<WorkflowInstanceView>(), any<SkipperError>())
            } catch (e: AssertionError) {
                println("$errorType: Failed to reach COMPENSATION_ERROR - ${e.message}")
                printEvents(workflowId)
                throw e
            }
        }

        // Reset state for this scenario
        resetTestState()

        workflowId = UUID.randomUUID().toString()
        helper = TestHelper(skipperEngine, scheduler, workflowId)

        // Configure workflow to fail, and compensation to fail with retryable error
        doThrow(NonRetryableError("unable to perform action")).whenever(demoClient).creditHook()
        doThrow(RetryableError("unable to perform compensation")).whenever(demoClient).undoDebitHook()

        currentWorkflow =
            workflowBuilder(TransferRequestWorkflow::class.java)
                .callbackHandler(DemoCallbackHandler::class.java)
                .build()
        val finalCurrentWorkflow2 = currentWorkflow
        val error =
            assertThrows(
                NonRetryableError::class.java
            ) {
                finalCurrentWorkflow2.transferRequest(TransferRequest(1, 2, 121, 0, null))
            }
        assertThat(error.message).contains("unable to perform")

        try {
            helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.COMPENSATION_ERROR)
            helper.waitForExecutionFlowToFinish()
            // Also wait for the compensation task to be fully removed from the scheduler.
            // waitForExecutionFlowToFinish only checks for tasks with ID=workflowId, but
            // compensation tasks have ID=workflowId + "-compensation" (see SkipperEngine:455).
            // Without this, reExecuteWorkflows can race with the old compensation handler.
            helper.waitForCondition { scheduler.getTask<Any>("$workflowId-compensation").isEmpty }

            // Now the workflow should be in compensation error state
            val stuckWorkflows =
                workflowsService.findWorkflowsWithExhaustedRetries(10)
            assertTrue(
                stuckWorkflows.any { it.id == workflowId },
                "Expected workflow $workflowId to be in stuck workflows list"
            )

            // Reset mock to avoid the previous exception being thrown again
            resetTestState()
            workflowsService.reExecuteWorkflows(listOf(workflowId))

            // Use a longer polling window (60s) because after reExecuteWorkflows, the scheduler
            // needs multiple cycles to re-fetch the task, process it, and complete compensation.
            // The default 20s can be too tight on slow CI machines.
            helper.waitForWorkflowToReachStatus(
                setOf(WorkflowInstance.Status.COMPENSATION_COMPLETED),
                600,
                100
            )

            // Verify that compensation completion callback was called after successful retry
            helper.waitForExecutionFlowToFinish() // Ensure all callbacks have been processed
            // Use timeout() because callback may be processed asynchronously
            verify(callbackHandler, timeout(5000))
                .onCompensationCompleted(any<WorkflowInstanceView>())
        } catch (e: AssertionError) {
            println("Retryable compensation test failed: ${e.message}")
            printEvents(workflowId)
            throw e
        }
    }

    class TransferRequestWorkflow : Workflow() {
        private val actions = actions<LedgerActions>()

        @WorkflowMethod
        fun transferRequest(request: TransferRequest): String {
            val fee = 1
            actions.debit(LedgerRequest(request.fromUserId, request.amount)).join()
            actions.credit(LedgerRequest(request.toUserId, request.amount - fee))
            actions.credit(
                LedgerRequest(request.sysAccount, fee)
            ) // credit to the system for the fee
            return "Transfer completed"
        }
    }

    data class LedgerRequest(
        @JvmField var userId: Int = 0,
        @JvmField var amount: Int = 0
    )

    data class TransferRequest(
        @JvmField var fromUserId: Int = 0,
        @JvmField var toUserId: Int = 0,
        @JvmField var amount: Int = 0,
        @JvmField var sysAccount: Int = 0,
        @JvmField var workflowId: Int? = null
    )

    class TransferRequests {
        @JvmField val requests: MutableList<TransferRequest> = ArrayList()
    }

    class Ledger {
        @JvmField val balances: MutableMap<Int, Int> = ConcurrentHashMap()
        private val lock = Any()

        init {
            balances[1] = 100
            balances[2] = 100
        }

        fun debit(
            userId: Int,
            amount: Int
        ) {
            synchronized(lock) {
                balances.putIfAbsent(userId, 0)
                balances[userId] = balances[userId]!! - amount
            }
        }

        fun credit(
            userId: Int,
            amount: Int
        ) {
            synchronized(lock) {
                balances.putIfAbsent(userId, 0)
                balances[userId] = balances[userId]!! + amount
            }
        }

        fun resetToInitialState() {
            synchronized(lock) {
                balances.clear()
                balances[1] = 100
                balances[2] = 100
            }
        }

        fun getBalances(): Map<Int, Int> {
            return balances
        }
    }

    open class DemoClient {
        open fun debitHook() {}

        open fun creditHook() {}

        open fun undoDebitHook() {}

        open fun undoCreditHook() {}
    }

    class LedgerActions : Actions() {
        @javax.inject.Inject private lateinit var ledger: Ledger
        @javax.inject.Inject private lateinit var demoClient: DemoClient

        @Execute(returnType = String::class)
        fun debit(request: LedgerRequest): CompletableFuture<String> {
            demoClient.debitHook()
            ledger.debit(request.userId, request.amount)
            return CompletableFuture.completedFuture("debit-id")
        }

        @Compensate(forExecute = "debit")
        fun undoDebit(
            request: LedgerRequest,
            debitId: String
        ) {
            if ("debit-id" != debitId) {
                // Let's test that the compensation method is passed in the correct debitId which is
                // the result of the original debit action.
                throw NonRetryableError("invalid debit id")
            }
            demoClient.undoDebitHook()
            ledger.credit(request.userId, request.amount)
        }

        @Execute
        fun credit(request: LedgerRequest): String {
            demoClient.creditHook()
            ledger.credit(request.userId, request.amount)
            return "credit-id"
        }

        @Compensate(forExecute = "credit")
        fun undoCredit(
            request: LedgerRequest,
            creditId: String
        ) {
            if ("credit-id" != creditId) {
                throw NonRetryableError("invalid credit id")
            }
            demoClient.undoCreditHook()
            ledger.debit(request.userId, request.amount)
        }
    }

    enum class CompensationErrorType {
        NON_RETRYABLE_ERROR,
        UNEXPECTED_ERROR_1,
        UNEXPECTED_ERROR_2;

        fun createError(): Throwable {
            return when (this) {
                NON_RETRYABLE_ERROR -> NonRetryableError("unable to perform compensation")
                UNEXPECTED_ERROR_1 -> IllegalArgumentException("unable to perform compensation")
                UNEXPECTED_ERROR_2 -> NullPointerException("java.lang.RuntimeException")
            }
        }
    }

    open class DemoCallbackHandler : WorkflowCallbackHandler {
        @JvmField val compensationCompletedCalls: MutableList<String> = ArrayList()
        @JvmField val compensationErrorCalls: MutableList<String> = ArrayList()

        override fun onSuccess(workflowInstance: WorkflowInstanceView) {}

        override fun onNonRetryableError(
            workflowInstance: WorkflowInstanceView,
            error: Throwable
        ) {}

        override fun onWorkflowInWaitingStatus(workflowInstance: WorkflowInstanceView) {}

        override fun onWorkflowTimeout(workflowInstance: WorkflowInstanceView) {}

        override fun onCompensationCompleted(workflowInstance: WorkflowInstanceView) {
            compensationCompletedCalls.add(workflowInstance.id)
        }

        override fun onCompensationError(
            workflowInstance: WorkflowInstanceView,
            error: SkipperError?
        ) {
            compensationErrorCalls.add(
                workflowInstance.id +
                    ":" +
                    (if (error != null) error.javaClass.simpleName else "null")
            )
        }

        fun reset() {
            compensationCompletedCalls.clear()
            compensationErrorCalls.clear()
        }
    }

    // Integration tests for @Compensate checkpoint modes
    @Test
    fun testCompensateWithImmediateCheckpointMode() {
        workflowId = UUID.randomUUID().toString()
        helper = TestHelper(skipperEngine, scheduler, workflowId)

        val workflow =
            workflowBuilder(CheckpointModeTestWorkflow::class.java)
                .callbackHandler(DemoCallbackHandler::class.java)
                .build()

        // Workflow will fail after execute succeeds, triggering compensation
        val error =
            assertThrows(
                NonRetryableError::class.java
            ) { workflow.workflowWithImmediateCheckpointCompensation("test-data") }
        assertThat(error.message).contains("Force compensation")

        // Wait for compensation to complete
        helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.COMPENSATION_COMPLETED)

        // Verify checkpoints were created for both execute and compensate actions
        val view = workflow.getWorkflowInstanceView()
        assertThat(view.actionCheckpoints.size).isGreaterThanOrEqualTo(2)

        // Verify execute checkpoint
        assertTrue(
            view.actionCheckpoints.stream()
                .anyMatch { cp -> cp.actionMethod == "executeActionWithImmediateCompensation" },
            "Execute checkpoint should exist"
        )

        // Verify compensate checkpoint - this proves compensation ran
        assertTrue(
            view.actionCheckpoints.stream()
                .anyMatch { cp -> cp.actionMethod == "compensateImmediateCheckpoint" },
            "Compensate checkpoint should exist"
        )
    }

    @Test
    fun testCompensateWithEventualCheckpointMode() {
        workflowId = UUID.randomUUID().toString()
        helper = TestHelper(skipperEngine, scheduler, workflowId)

        val workflow =
            workflowBuilder(CheckpointModeTestWorkflow::class.java)
                .callbackHandler(DemoCallbackHandler::class.java)
                .build()

        // Workflow will fail after execute succeeds, triggering compensation
        val error =
            assertThrows(
                NonRetryableError::class.java
            ) { workflow.workflowWithEventualCheckpointCompensation("test-data") }
        assertThat(error.message).contains("Force compensation")

        // Wait for compensation to complete
        helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.COMPENSATION_COMPLETED)

        // Verify checkpoints were created for both execute and compensate actions
        val view = workflow.getWorkflowInstanceView()
        assertThat(view.actionCheckpoints.size).isGreaterThanOrEqualTo(2)

        // Verify execute checkpoint
        assertTrue(
            view.actionCheckpoints.stream()
                .anyMatch { cp -> cp.actionMethod == "executeActionWithEventualCompensation" },
            "Execute checkpoint should exist"
        )

        // Verify compensate checkpoint - this proves compensation ran
        assertTrue(
            view.actionCheckpoints.stream()
                .anyMatch { cp -> cp.actionMethod == "compensateEventualCheckpoint" },
            "Compensate checkpoint should exist"
        )
    }

    @Test
    fun testCompensateWithNoCheckpointModeStillCreatesCheckpoint() {
        workflowId = UUID.randomUUID().toString()
        helper = TestHelper(skipperEngine, scheduler, workflowId)

        val workflow =
            workflowBuilder(CheckpointModeTestWorkflow::class.java)
                .callbackHandler(DemoCallbackHandler::class.java)
                .build()

        // Workflow will fail after execute succeeds, triggering compensation
        val error =
            assertThrows(
                NonRetryableError::class.java
            ) { workflow.workflowWithNoCheckpointCompensation("test-data") }
        assertThat(error.message).contains("Force compensation")

        // Wait for compensation to complete
        helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.COMPENSATION_COMPLETED)

        // Verify checkpoints were created even with NO_CHECKPOINT mode
        // (compensable actions are always checkpointed)
        val view = workflow.getWorkflowInstanceView()
        assertThat(view.actionCheckpoints.size).isGreaterThanOrEqualTo(2)

        // Verify execute checkpoint
        assertTrue(
            view.actionCheckpoints.stream()
                .anyMatch { cp -> cp.actionMethod == "executeActionWithNoCheckpointCompensation" },
            "Execute checkpoint should exist"
        )

        // Verify compensate checkpoint - this proves compensation ran despite NO_CHECKPOINT
        assertTrue(
            view.actionCheckpoints.stream()
                .anyMatch { cp -> cp.actionMethod == "compensateNoCheckpoint" },
            "Compensate checkpoint should exist"
        )
    }

    class CheckpointModeTestWorkflow : Workflow() {
        private val actions = actions<CheckpointModeTestActions>()

        @WorkflowMethod
        fun workflowWithImmediateCheckpointCompensation(data: String): String {
            val result = actions.executeActionWithImmediateCompensation(data)
            // Force workflow to fail after execute succeeds to trigger compensation
            throw NonRetryableError("Force compensation")
        }

        @WorkflowMethod
        fun workflowWithEventualCheckpointCompensation(data: String): String {
            val result = actions.executeActionWithEventualCompensation(data)
            // Force workflow to fail after execute succeeds to trigger compensation
            throw NonRetryableError("Force compensation")
        }

        @WorkflowMethod
        fun workflowWithNoCheckpointCompensation(data: String): String {
            val result = actions.executeActionWithNoCheckpointCompensation(data)
            // Force workflow to fail after execute succeeds to trigger compensation
            throw NonRetryableError("Force compensation")
        }
    }

    class CheckpointModeTestActions : Actions() {
        @Execute
        fun executeActionWithImmediateCompensation(data: String): String {
            return "Executed $data"
        }

        @Compensate(
            forExecute = "executeActionWithImmediateCompensation",
            checkpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT
        )
        fun compensateImmediateCheckpoint(
            data: String,
            result: String
        ) {
            // Compensation logic with immediate checkpoint
        }

        @Execute
        fun executeActionWithEventualCompensation(data: String): String {
            return "Executed $data"
        }

        @Compensate(
            forExecute = "executeActionWithEventualCompensation",
            checkpointMode = CheckpointMode.EVENTUAL_CHECKPOINT
        )
        fun compensateEventualCheckpoint(
            data: String,
            result: String
        ) {
            // Compensation logic with eventual checkpoint
        }

        @Execute
        fun executeActionWithNoCheckpointCompensation(data: String): String {
            return "Executed $data"
        }

        @Compensate(
            forExecute = "executeActionWithNoCheckpointCompensation",
            checkpointMode = CheckpointMode.NO_CHECKPOINT
        )
        fun compensateNoCheckpoint(
            data: String,
            result: String
        ) {
            // Compensation logic with no checkpoint (but will still be checkpointed)
        }
    }
}
