package com.airbnb.skipper.integtest

import com.airbnb.skipper.Actions
import com.airbnb.skipper.ApplicationError
import com.airbnb.skipper.CheckpointMode
import com.airbnb.skipper.Compensate
import com.airbnb.skipper.Execute
import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.FixedRetryStrategy
import com.airbnb.skipper.IWorkflowFactory
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.PersistentRetryStrategy
import com.airbnb.skipper.QueryMethod
import com.airbnb.skipper.RetriesExhaustedError
import com.airbnb.skipper.RetryStrategy
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.SignalMethod
import com.airbnb.skipper.StateField
import com.airbnb.skipper.Timer
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowCallbackHandler
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.WorkflowOptions
import com.airbnb.skipper.WorkflowsService
import com.airbnb.skipper.api.WorkflowInstanceStatusView
import com.airbnb.skipper.api.WorkflowInstanceView
import com.airbnb.skipper.internal.PersistentRetryableError
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.SkipperSchedulerManager
import com.airbnb.skipper.internal.api.PersistedSignal
import com.airbnb.skipper.internal.api.WaitSignal
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.SchedulerExecutionQueue
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.internal.testutils.TestTypes
import com.airbnb.skipper.invoke
import com.airbnb.skipper.named
import com.airbnb.skipper.testutils.MutableClock
import com.airbnb.skipper.testutils.TestClient
import com.airbnb.skipper.testutils.TestHelper
import com.airbnb.skipper.testutils.TestRequestContext
import com.airbnb.skipper.testutils.TestRuntime
import com.google.inject.name.Named
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.locks.LockSupport
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Abstract integration test for full workflow execution. Subclasses provide the storage backend
 * (MySQL, SQLite, ...) by extending this class and applying the appropriate test setup extension and,
 * if needed, overriding [createTestRuntime] to configure the runtime.
 */
@ExperimentalCoroutinesApi
@Execution(ExecutionMode.SAME_THREAD)
abstract class BaseWorkflowIntegTest {
    /**
     * Hook for subclasses to provide a storage-specific [TestRuntime]. The default implementation
     * returns a [TestRuntime] configured with whatever defaults [TestRuntime] ships with.
     */
    protected open fun createTestRuntime(): TestRuntime = TestRuntime()

    open class SampleWorkflow : Workflow() {
        private val actions =
            actions<SampleActions>()

        @StateField
        var shouldProceed = false

        @WorkflowMethod
        fun workflowMethod(): String {
            return actions.executeMethod()
        }

        @WorkflowMethod(returnType = String::class)
        fun completableFutureWorkflowMethod(): CompletableFuture<String> {
            return actions.completableFutureExecuteMethod()
        }

        @WorkflowMethod(returnType = Int::class)
        fun completableFutureWorkflowMethodWithInvalidReturnType(): CompletableFuture<String> {
            return actions.completableFutureExecuteMethod()
        }

        @WorkflowMethod
        fun completableFutureWorkflowMethodWithInvalidActionReturnType(): String {
            return actions.completableFutureExecuteMethodWithInvalidReturnType().join()
        }

        @WorkflowMethod(returnType = String::class)
        fun failingWorkflow(): CompletableFuture<String> {
            return actions.failingAction()
        }

        @StateField var shouldFailWithPersistent = true

        @WorkflowMethod
        fun persistentFailingWorkflowWithRetries(): String {
            return actions.persistentFailingAction(shouldFailWithPersistent)
        }

        @WorkflowMethod(returnType = String::class)
        fun persistentFailingWorkflowWithRetriesAsync(): CompletableFuture<String> {
            return actions.persistentFailingActionAsync(shouldFailWithPersistent)
        }

        @SignalMethod
        fun updateShouldFailWithPersistent(shouldFail: Boolean) {
            this.shouldFailWithPersistent = shouldFail
        }

        @WorkflowMethod(returnType = String::class)
        fun asyncFailingWorkflow(): CompletableFuture<String> {
            return actions.failingAsyncAction()
        }

        @WorkflowMethod(returnType = String::class)
        fun asyncNonRetryableFailingWorkflow(): CompletableFuture<String> {
            return actions.failingNonRetryableAsyncAction()
        }

        @WorkflowMethod
        fun unexpectedActionFailure(): String {
            return actions.failingActionUnexpectedError()
        }

        @WorkflowMethod
        fun nonExecuteHelperWorkflow(): String {
            return actions.helperCallingFailingAction()
        }

        @WorkflowMethod
        fun unexpectedActionFailureWithCustomRetryStrategy(): CompletableFuture<String> {
            return actions.failingActionWithCustomRetryStrategy()
        }

        @WorkflowMethod(returnType = String::class)
        fun waitingWorkflow(a: String): CompletableFuture<String> {
            val success = waitUntil({ shouldProceed }, Duration.ofSeconds(5))
            if (!success) {
                return CompletableFuture.completedFuture("Waiting workflow timeout")
            }
            return CompletableFuture.completedFuture("Waiting workflow executed")
        }

        @StateField var shouldProceedCount = 0

        @WorkflowMethod
        fun namedWaitingWorkflowMethod(a: String): String {
            val success = waitUntil({ shouldProceedCount == 1 }, Duration.ofSeconds(5), "first-timer")
            if (!success) {
                return "Waiting workflow timeout 1"
            }
            val success2 = waitUntil({ shouldProceedCount == 2 }, Duration.ofSeconds(5), "second-timer")
            if (!success2) {
                return "Waiting workflow timeout 2"
            }
            return "Waiting workflow executed"
        }

        @SignalMethod
        fun updateShouldProceedCount(count: Int) {
            this.shouldProceedCount = count
        }

        @WorkflowMethod(returnType = String::class)
        fun unboundedWaitingWorkflow(a: String): CompletableFuture<String> {
            val success = waitUntil { shouldProceed }
            return CompletableFuture.completedFuture(
                if (success) {
                    "Waiting workflow executed: success=$success"
                } else {
                    "Waiting workflow timeout: success=$success"
                }
            )
        }

        @WorkflowMethod(returnType = String::class)
        fun waitingWorkflowWithNoWait(a: String): CompletableFuture<String> {
            // Here we are testing a workflow here the condition is met even before the wait is executed.
            val success = waitUntil({ true }, Duration.ofSeconds(5))
            if (!success) {
                return CompletableFuture.completedFuture("Waiting workflow timeout")
            }
            return CompletableFuture.completedFuture("Waiting workflow executed")
        }

        @WorkflowMethod(returnType = String::class)
        fun longWaitingWorkflow(waitDuration: Duration): CompletableFuture<String> {
            val success = waitUntil({ shouldProceed }, waitDuration)
            if (!success) {
                return CompletableFuture.completedFuture("Waiting workflow timeout")
            }
            return CompletableFuture.completedFuture("Waiting workflow executed")
        }

        @WorkflowMethod
        fun syncWaitingWorkflow(a: String): String {
            waitUntil { shouldProceed }
            return "Waiting workflow executed"
        }

        @WorkflowMethod
        fun longRunningWorkflow(durationInSecs: Int): CompletableFuture<Void> {
            LockSupport.parkNanos(durationInSecs * 1_000_000_000L)
            return CompletableFuture.completedFuture(null)
        }

        @StateField var resultString = ""

        @WorkflowMethod(returnType = String::class)
        fun workflowWithCheckpointLambdas(): CompletableFuture<String> {
            checkpoint { resultString += "1" }
            waitUntil({ false }, Duration.ofSeconds(1))
            checkpoint { resultString += "2" }
            waitUntil({ false }, Duration.ofSeconds(1))
            checkpoint { resultString += "3" }
            return CompletableFuture.completedFuture(resultString)
        }

        @StateField var namedResultString = ""

        @WorkflowMethod(returnType = String::class)
        fun workflowWithNamedCheckpointLambdas(): CompletableFuture<String> {
            checkpoint(name = "step-one") { namedResultString += "A" }
            waitUntil({ false }, Duration.ofSeconds(1))
            checkpoint(name = "step-two") { namedResultString += "B" }
            return CompletableFuture.completedFuture(namedResultString)
        }

        @WorkflowMethod(returnType = String::class)
        fun workflowWithMixedNamedAndPositionalActions(): CompletableFuture<String> {
            val r1 = actions.executeMethod()
            val r2 = actions.named("named-before-wait").executeMethod()
            waitUntil({ false }, Duration.ofSeconds(1))
            val r3 = actions.executeMethod()
            val r4 = actions.named("named-after-wait").executeMethod()
            return CompletableFuture.completedFuture("$r1|$r2|$r3|$r4")
        }

        @StateField var observedVersion = 0
        @StateField var observedBranch = ""

        @WorkflowMethod(returnType = String::class)
        fun workflowWithVersionGate(): CompletableFuture<String> {
            observedVersion =
                version("payment-migration", minVersion = 1, maxVersion = currentMaxVersion)
            waitUntil({ false }, Duration.ofSeconds(1))
            return CompletableFuture.completedFuture("v$observedVersion")
        }

        @WorkflowMethod(returnType = String::class)
        fun workflowWithVersionGateBranching(): CompletableFuture<String> {
            val v = version("branching-change", minVersion = 1, maxVersion = currentMaxVersion)
            observedBranch = if (v >= 2) "new-branch" else "legacy-branch"
            return CompletableFuture.completedFuture(observedBranch)
        }

        companion object {
            // Mutable static lets tests simulate a "redeploy" that bumps maxVersion between
            // executions of the same workflow method.
            @JvmStatic
            var currentMaxVersion: Int = 1
        }

        @SignalMethod
        fun updateShouldProceed(shouldProceed: Boolean) {
            this.shouldProceed = shouldProceed
        }

        @SignalMethod
        fun signalThatThrows() {
            throw IllegalStateException("Signal that throws")
        }

        @SignalMethod(persist = true)
        fun updateShouldProceedDurably(shouldProceed: Boolean) {
            this.shouldProceed = shouldProceed
        }

        @SignalMethod(persist = true)
        fun durableSignalThatThrows() {
            throw IllegalStateException("Durable signal that throws")
        }

        fun nonWorkflowMethod(): String {
            return actions.nonExecuteMethod()
        }

        @StateField var contentiousState: TestTypes.User = TestTypes.User()

        @WorkflowMethod(returnType = TestTypes.User::class)
        fun contentiousWorkflow(): CompletableFuture<TestTypes.User> {
            checkpoint {
                val sleepMillis = (0..10).random()
                LockSupport.parkNanos((sleepMillis * 1000000).toLong())
            }
            checkpoint {
                contentiousState.id = "123"
            }
            waitUntil({ contentiousState.id != null && contentiousState.name != null }, Duration.ofSeconds(10))
            return CompletableFuture.completedFuture(contentiousState)
        }

        @SignalMethod
        fun updateContentiousState(name: String) {
            contentiousState.name = name
        }

        @WorkflowMethod
        fun sayNameAndAge(): String {
            return actions.multipleArgs("John", 30)
        }
    }

    class CompensationWorkflow : Workflow() {
        private val actions = actions<CompensationActions>()

        @WorkflowMethod
        fun workflowWithImmediateCheckpointCompensation(): String {
            val result = actions.executeActionWithImmediateCompensation("data1")
            // Force workflow to fail after execute succeeds to trigger compensation
            actions.failingAction()
            return result
        }

        @WorkflowMethod
        fun workflowWithEventualCheckpointCompensation(): String {
            val result = actions.executeActionWithEventualCompensation("data2")
            // Force workflow to fail after execute succeeds to trigger compensation
            actions.failingAction()
            return result
        }

        @WorkflowMethod
        fun workflowWithNoCheckpointCompensation(): String {
            val result = actions.executeActionWithNoCheckpointCompensation("data3")
            // Force workflow to fail after execute succeeds to trigger compensation
            actions.failingAction()
            return result
        }
    }

    class CompensationActions : Actions() {
        @Inject
        @Named("noRetries")
        override lateinit var retryStrategy: RetryStrategy

        @Inject
        private lateinit var testClient: TestClient

        @Execute
        fun executeActionWithImmediateCompensation(data: String): String {
            testClient.action("executeActionWithImmediateCompensation")
            return "Executed $data"
        }

        @Compensate(forExecute = "executeActionWithImmediateCompensation", checkpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT)
        fun compensateImmediateCheckpoint(
            data: String,
            result: String
        ) {
            testClient.action("compensateImmediateCheckpoint")
        }

        @Execute
        fun executeActionWithEventualCompensation(data: String): String {
            testClient.action("executeActionWithEventualCompensation")
            return "Executed $data"
        }

        @Compensate(forExecute = "executeActionWithEventualCompensation", checkpointMode = CheckpointMode.EVENTUAL_CHECKPOINT)
        fun compensateEventualCheckpoint(
            data: String,
            result: String
        ) {
            testClient.action("compensateEventualCheckpoint")
        }

        @Execute
        fun executeActionWithNoCheckpointCompensation(data: String): String {
            testClient.action("executeActionWithNoCheckpointCompensation")
            return "Executed $data"
        }

        @Compensate(forExecute = "executeActionWithNoCheckpointCompensation", checkpointMode = CheckpointMode.NO_CHECKPOINT)
        fun compensateNoCheckpoint(
            data: String,
            result: String
        ) {
            testClient.action("compensateNoCheckpoint")
        }

        @Execute
        fun failingAction() {
            testClient.action("failingAction")
            throw NonRetryableError("Intentional failure to trigger compensation")
        }
    }

    open class SampleActions : Actions() {
        @Inject
        @Named("noRetries")
        override lateinit var retryStrategy: RetryStrategy

        @Inject
        private lateinit var testClient: TestClient

        val otherRetryStrategy = FixedRetryStrategy(Duration.ZERO, 10)
        val persistentRetry = PersistentRetryStrategy.of(FixedRetryStrategy(Duration.ZERO, 1))

        @Execute
        fun executeMethod(): String {
            testClient.action("executeMethod")
            return "Execute method executed"
        }

        @Execute(returnType = String::class)
        fun completableFutureExecuteMethod(): CompletableFuture<String> {
            testClient.action("completableFutureExecuteMethod")
            return CompletableFuture.completedFuture("Completable future method executed")
        }

        @Execute(returnType = Double::class)
        fun completableFutureExecuteMethodWithInvalidReturnType(): CompletableFuture<String> {
            testClient.action("completableFutureExecuteMethodWithInvalidReturnType")
            return CompletableFuture.completedFuture("")
        }

        fun nonExecuteMethod(): String {
            testClient.action("nonExecuteMethod")
            return "Non-execute method executed"
        }

        @Execute
        fun failingAction(): CompletableFuture<String> {
            throw RetryableError("Failing action")
        }

        @Execute(retryStrategy = "persistentRetry")
        fun persistentFailingAction(shouldFail: Boolean): String {
            if (shouldFail) {
                throw RetryableError("Failing action")
            }
            return "success!"
        }

        @Execute(retryStrategy = "persistentRetry", returnType = String::class)
        fun persistentFailingActionAsync(shouldFail: Boolean): CompletableFuture<String> {
            val future = CompletableFuture<String>()
            if (shouldFail) {
                future.completeExceptionally(RetryableError("Failing action"))
                return future
            }
            return CompletableFuture.completedFuture("success!")
        }

        @Execute
        fun failingAsyncAction(): CompletableFuture<String> {
            val future = CompletableFuture<String>()
            future.completeExceptionally(RetryableError("Failing action"))
            return future
        }

        @Execute
        fun failingNonRetryableAsyncAction(): CompletableFuture<String> {
            val future = CompletableFuture<String>()
            future.completeExceptionally(NonRetryableError("Failing action"))
            return future
        }

        @Execute
        fun failingActionUnexpectedError(): String {
            throw IllegalStateException("Illegal state error!")
        }

        @Execute(retryStrategy = "otherRetryStrategy")
        fun failingActionWithCustomRetryStrategy(): CompletableFuture<String> {
            testClient.action("failingActionWithCustomRetryStrategy")
            throw RetryableError("Failing action")
        }

        fun multipleArgs(
            name: String,
            age: Int
        ): String {
            return "Name: $name, Age: $age"
        }

        @Execute
        fun failingNonRetryableAction(): String {
            throw NonRetryableError("error message from @Execute action")
        }

        fun helperCallingFailingAction(): String {
            return failingNonRetryableAction()
        }
    }

    /**
     * Workflow used to exercise the admin "rewind" operation. It runs two named actions in
     * sequence: a first action that always succeeds, followed by an action that fails (with a
     * non-retryable error) while [RewindActions.shouldFail] is set, simulating an action affected
     * by an incident.
     */
    open class RewindWorkflow : Workflow() {
        private val actions = actions<RewindActions>()

        @WorkflowMethod
        fun rewindWorkflow(): String {
            val first = actions.named("first-step").firstAction()
            val second = actions.named("failing-step").maybeFailingAction()
            return "$first|$second"
        }
    }

    open class RewindActions : Actions() {
        @Inject
        @Named("noRetries")
        override lateinit var retryStrategy: RetryStrategy

        @Inject
        private lateinit var testClient: TestClient

        @Execute
        fun firstAction(): String {
            testClient.action("firstAction")
            return "first"
        }

        @Execute
        fun maybeFailingAction(): String {
            testClient.action("maybeFailingAction")
            if (shouldFail) {
                throw NonRetryableError("Incident: maybeFailingAction failed")
            }
            return "second"
        }

        companion object {
            // Simulates an incident: the action fails while this is true and succeeds once the
            // incident is considered resolved (the flag is flipped to false).
            @JvmStatic
            var shouldFail: Boolean = true
        }
    }

    class ActionsWithCustomExceptionClassifier : Actions() {
        val customClassifier = object : com.airbnb.skipper.internal.ExceptionClassifier {
            override fun isRetryable(throwable: Throwable): Boolean {
                // Treat IllegalArgumentException as retryable (opposite of default behavior)
                return throwable is IllegalArgumentException
            }
        }

        @Execute(exceptionClassifier = "customClassifier")
        fun actionWithCustomClassifier(): String {
            throw IllegalArgumentException("This should be retryable with custom classifier")
        }

        @Execute
        fun actionWithoutCustomClassifier(): String {
            throw IllegalArgumentException("This should be non-retryable with global classifier")
        }
    }

    class InvalidActions1 : Actions() {
        @Execute
        fun executeMethod() {
            throw UnsupportedOperationException()
        }

        @Execute
        fun executeMethod(a: Int): Int {
            return a
        }
    }

    class InvalidActions2 : Actions() {
        @Compensate(forExecute = "executeMethod")
        fun compensateMethod() {
            throw UnsupportedOperationException()
        }

        @Compensate(forExecute = "executeMethod")
        fun compensateMethod(a: Int): Int {
            return a
        }
    }

    class WorkflowWithInvalidActions1 : Workflow() {
        private val actions =
            actions<InvalidActions1>()

        @WorkflowMethod
        fun workflowMethod() {
            throw UnsupportedOperationException()
        }
    }

    class WorkflowWithOverloadedWorkflowMethod : Workflow() {
        @WorkflowMethod
        fun workflowMethod() {
            throw UnsupportedOperationException()
        }

        @WorkflowMethod
        fun workflowMethod(a: Int) {
            throw UnsupportedOperationException()
        }
    }

    class WorkflowWithCustomExceptionClassifier : Workflow() {
        private val actions = actions<ActionsWithCustomExceptionClassifier>()

        @WorkflowMethod
        fun workflowMethodWithCustomClassifier(): String {
            return actions.actionWithCustomClassifier()
        }

        @WorkflowMethod
        fun workflowMethodWithoutCustomClassifier(): String {
            return actions.actionWithoutCustomClassifier()
        }
    }

    class WaitWorkflow : Workflow() {
        @StateField var counter: Int = 0
        @StateField var status = "pending"

        @WorkflowMethod(returnType = Int::class)
        fun waitWorkflow(countLimit: Int): CompletableFuture<Int> {
            for (i in 1..countLimit) {
                status = "in_loop"
                val success = waitUntil({ counter >= i }, Duration.ofSeconds(5))
                if (!success) {
                    return CompletableFuture.completedFuture(-1)
                }
            }
            status = "finished"
            return CompletableFuture.completedFuture(counter)
        }

        @SignalMethod
        fun incrementCounter() {
            counter++
        }

        @SignalMethod(persist = true)
        fun incrementCounterDurably() {
            counter++
        }

        @QueryMethod
        fun getCounterValue(): Int {
            return counter
        }

        @QueryMethod
        fun getCounterAsync(): CompletableFuture<Int> {
            return CompletableFuture.completedFuture(counter)
        }

        @QueryMethod
        fun getStatusValue(): String {
            return status
        }
    }

    /**
     * Test-only sink for `TestRequestContext.userId` values observed by workflow / action /
     * signal / compensation handlers. Injected via Guice; tests inspect the captured values
     * to assert that the host request-context flowed through the appropriate engine path.
     */
    class RequestContextRecorder {
        @Volatile var actionUserIdBeforeWait: String? = null
        @Volatile var actionUserIdAfterResume: String? = null

        @Volatile var signalUserId: String? = null
        @Volatile var compensationUserId: String? = null
    }

    /**
     * Workflow used by `TestRequestContextPropagation`:
     *  - The workflow method captures the request-context userId on an action, waits for a signal, then
     *    captures it again on a second action. The second capture exercises the persistence
     *    reload path: the workflow is persisted while waiting, the scheduler resumes it after
     *    the signal, and the request context must come back through the serde + middleware.
     *  - The signal handler captures the request-context userId visible to it. With the
     *    `WorkflowFactory.kt` gating change in this PR, signals against an existing workflow
     *    must NOT clobber the persisted request context with the caller's empty TL.
     */
    class RequestContextCapturingWorkflow : Workflow() {
        @Inject private lateinit var recorder: RequestContextRecorder

        @StateField var shouldProceed = false

        private val actions = actions<RequestContextCapturingActions>()

        @WorkflowMethod
        fun executeAndWait(): String {
            actions.captureUserIdBeforeWait()
            val resumed = waitUntil({ shouldProceed }, Duration.ofSeconds(5))
            if (!resumed) return "TIMEOUT"
            actions.captureUserIdAfterResume()
            return "DONE"
        }

        @SignalMethod
        fun proceed() {
            recorder.signalUserId =
                TestRequestContext.getCurrentRequestContext()?.userIdOptional?.orElse(null)
            shouldProceed = true
        }
    }

    class RequestContextCapturingActions : Actions() {
        @Inject private lateinit var recorder: RequestContextRecorder

        @Execute
        fun captureUserIdBeforeWait(): String? {
            val ctx = TestRequestContext.getCurrentRequestContext()
            recorder.actionUserIdBeforeWait = ctx?.userIdOptional?.orElse(null)
            return recorder.actionUserIdBeforeWait
        }

        @Execute
        fun captureUserIdAfterResume(): String? {
            val userId =
                TestRequestContext.getCurrentRequestContext()?.userIdOptional?.orElse(null)
            recorder.actionUserIdAfterResume = userId
            return userId
        }
    }

    /**
     * Workflow used by `TestRequestContextPropagation` to verify that the persisted request context is
     * available on the thread-local during the compensation chain. The compensation handler
     * stashes the captured userId into [RequestContextRecorder] for assertion.
     */
    class RequestContextCompensationWorkflow : Workflow() {
        private val actions = actions<RequestContextCompensationActions>()

        @WorkflowMethod
        fun executeAndFail(): String {
            actions.successfulCheckpointedAction("data")
            actions.failingAction()
            return "UNREACHABLE"
        }
    }

    class RequestContextCompensationActions : Actions() {
        @Inject
        @Named("noRetries")
        override lateinit var retryStrategy: RetryStrategy

        @Inject private lateinit var recorder: RequestContextRecorder

        @Execute(checkpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT)
        fun successfulCheckpointedAction(data: String): String = "Executed $data"

        @Compensate(
            forExecute = "successfulCheckpointedAction",
            checkpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT,
        )
        fun compensateSuccessfulAction(
            data: String,
            result: String,
        ) {
            recorder.compensationUserId =
                TestRequestContext.getCurrentRequestContext()?.userIdOptional?.orElse(null)
        }

        @Execute
        fun failingAction() {
            throw NonRetryableError("Intentional failure to trigger compensation")
        }
    }

    open class DemoCallbackHandler : WorkflowCallbackHandler {
        override fun onSuccess(workflowInstance: WorkflowInstanceView) {
        }

        override fun onNonRetryableError(
            workflowInstance: WorkflowInstanceView,
            error: Throwable?
        ) {
        }

        override fun onWorkflowInWaitingStatus(workflowInstance: WorkflowInstanceView) {
        }

        override fun onWorkflowTimeout(workflowInstance: WorkflowInstanceView) {
        }
    }

    abstract inner class SuiteBase {
        lateinit var deps: TestRuntime
        lateinit var testClient: TestClient
        lateinit var callbackHandler: DemoCallbackHandler
        lateinit var workflowId: String
        lateinit var helper: TestHelper

        // Convenience accessors
        val workflowFactory: IWorkflowFactory get() = deps.workflowFactory
        val schedulerManager: SkipperSchedulerManager get() = deps.schedulerManager
        val scheduler: Scheduler get() = deps.scheduler
        val schedulerExecutionQueue: SchedulerExecutionQueue get() = deps.schedulerExecutionQueue
        val skipperEngine: SkipperEngine get() = deps.skipperEngine
        val workflowStore: WorkflowStore get() = deps.workflowStore
        val featureGate: FeatureGate get() = deps.featureGate

        open fun customizeDeps(deps: TestRuntime) {}

        @BeforeEach
        open fun setUp() {
            // Reset the process-global MutableClock singleton so each test starts from a clean clock.
            // Otherwise clock fast-forwards from other integtest suites sharing this JVM (e.g. the
            // coroutine TripBooking example) leak in and break timer/wait-based assertions here.
            MutableClock.resetInstance()
            deps = createTestRuntime()
            testClient = mock()
            callbackHandler = mock()

            deps.addBinding(TestClient::class.java, testClient)
            deps.addBinding(DemoCallbackHandler::class.java, callbackHandler)

            // Let nested classes customize
            customizeDeps(deps)

            schedulerManager.start()
            whenever(featureGate.isEnabled(any())).thenReturn(true)
            workflowId = UUID.randomUUID().toString()
            helper = TestHelper(skipperEngine, scheduler, workflowId)
        }

        @AfterEach
        open fun tearDown() {
            schedulerManager.forceStop()
        }

        /**
         * Fetches ready tasks from the persistent scheduler and adds them to the execution queue.
         * This allows tests to explicitly trigger task processing instead of relying on background polling.
         * @return the number of tasks fetched and queued
         */
        protected fun fetchAndQueueReadyTasks(): Int {
            val tasks = scheduler.fetch<Any>(10)
            tasks.forEach { task -> schedulerExecutionQueue.add(task) }
            return tasks.size()
        }
    }

    @Nested
    inner class TestsWithEventualCheckpointExecutionMode : SuiteBase() {
        override fun customizeDeps(deps: TestRuntime) {
            deps.clock = Clock.systemUTC()
            deps.config.defaultCheckpointMode = CheckpointMode.EVENTUAL_CHECKPOINT
        }

        @Test
        fun testInvokeWorkflowMethod() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            assertEquals("Execute method executed", workflow.workflowMethod())
            verify(testClient).action("executeMethod")
        }

        @Test
        fun testInvokeWorkflowMethodWithCallbackHandler() {
            val workflow = workflowFactory<SampleWorkflow>(
                workflowId = workflowId,
                requestContext = TestRequestContext.getCurrentRequestContext(),
                callbackHandler = DemoCallbackHandler::class.java
            )
            assertEquals("Execute method executed", workflow.workflowMethod())
            verify(testClient).action("executeMethod")
            helper.waitForExecutionFlowToFinish()
            verify(callbackHandler, times(1)).onSuccess(any())
            helper.waitForExecutionFlowToFinish()
        }

        @Test
        fun testInvokeCompletableFutureWorkflowMethod() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            assertEquals("Completable future method executed", workflow.completableFutureWorkflowMethod().join())
            verify(testClient).action("completableFutureExecuteMethod")
        }

        @Test
        fun testInvokeWorkflowMethodWithAsync() {
            var workflow = workflowFactory.builder(SampleWorkflow::class.java, workflowId)
                .runAsync().build()
            val result = workflow.completableFutureWorkflowMethod().join()
            assertEquals("Completable future method executed", result)
        }

        @Test
        fun testInvokeWorkflowMethodWithParentWorkflowId() {
            var workflow = workflowFactory.builder(SampleWorkflow::class.java, workflowId)
                .parentWorkflowId("parent-id").build()
            assertEquals(workflowId, workflow.id)
            assertEquals("parent-id", workflow.parentWorkflowId)
            val result = workflow.completableFutureWorkflowMethod().join()
            assertEquals("Completable future method executed", result)
            // Get workflow
            assertEquals("parent-id", workflow.getWorkflowInstanceView().parentWorkflowId)
        }

        @Test
        fun testNonBlockingInvokeCompletableFutureWorkflowMethod() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            workflow.failingWorkflow()
            // This is a non-blocking call, so even if the underlying workflow execution will fail, the test will not fail
            // because we are not blocking on the result.
        }

        @Test
        fun testInvokeAsyncFunctionWhenRetryableErrorEventuallyMustConvertToNonRetryableError() {
            // In this case, the action, who should return a completablefuture, throws an exception instead.
            val workflow = workflowFactory<SampleWorkflow>(
                workflowId = workflowId,
                requestContext = TestRequestContext.getCurrentRequestContext(),
                callbackHandler = DemoCallbackHandler::class.java
            )
            val e = assertThrows<CompletionException> {
                workflow.failingWorkflow().join()
            }
            assertThat(e.cause).isInstanceOf(NonRetryableError::class.java)
            assertThat(e.message).contains("action failingAction has exhausted all retry attempts")
            helper.waitForExecutionFlowToFinish()
        }

        @Test
        fun testInvokeAsyncFunctionWhenAsyncRetryableErrorEventuallyMustConvertToNonRetryableError() {
            // In this case, the action fails with a failed completable future.
            val workflow = workflowFactory<SampleWorkflow>(
                workflowId = workflowId,
                requestContext = TestRequestContext.getCurrentRequestContext(),
                callbackHandler = DemoCallbackHandler::class.java
            )
            val e = assertThrows<CompletionException> {
                workflow.asyncFailingWorkflow().join()
            }
            assertThat(e.cause).isInstanceOf(NonRetryableError::class.java)
            assertThat(e.message).contains("action failingAsyncAction has exhausted all retry attempts")
            helper.waitForExecutionFlowToFinish()
        }

        @Test
        fun testInvokeSyncFunctionWhenPersistentRetryableErrorMaxesOut() {
            // In this case, the action fails with a persistent retryable error.
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            val error = assertThrows<RetriesExhaustedError> {
                workflow.persistentFailingWorkflowWithRetries()
            }
            assertThat(error.cause).isInstanceOf(PersistentRetryableError::class.java)
            assertThat(error.message).contains("Failing action")
            val view = workflow.getWorkflowInstanceView()
            assertThat(view.status).isEqualTo(WorkflowInstanceStatusView.RETRIES_EXHAUSTED)
            helper.waitForExecutionFlowToFinish()
            // Send a signal to make the action no longer fail
            workflow.updateShouldFailWithPersistent(false)
            helper.waitForExecutionFlowToFinish()

            // Now, if we retry the workflow, it should succeed
            val result = workflow.persistentFailingWorkflowWithRetries()
            assertThat(result).isEqualTo("success!")
        }

        @Test
        fun testInvokeAsyncFunctionWhenPersistentRetryableErrorMaxesOut() {
            // In this case, the action fails with a persistent retryable error.
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            val error = assertThrows<CompletionException> {
                workflow.persistentFailingWorkflowWithRetriesAsync().join()
            }
            assertThat(error.cause).isInstanceOf(RetriesExhaustedError::class.java)
            assertThat(error.cause!!.cause).isInstanceOf(PersistentRetryableError::class.java)
            assertThat(error.message).contains("Failing action")
            val view = workflow.getWorkflowInstanceView()
            assertThat(view.status).isEqualTo(WorkflowInstanceStatusView.RETRIES_EXHAUSTED)
            helper.waitForExecutionFlowToFinish()
            // Send a signal to make the action no longer fail
            workflow.updateShouldFailWithPersistent(false)
            helper.waitForExecutionFlowToFinish()

            // Now, if we retry the workflow, it should succeed
            val result = workflow.persistentFailingWorkflowWithRetriesAsync()
            assertThat(result.join()).isEqualTo("success!")
        }

        // TODO: check the above function when both action and workflow return async

        @Test
        fun testInvokeAsyncNonRetryableFailingActionShouldThrowNonRetryableError() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            val e = assertThrows<CompletionException> {
                workflow.asyncNonRetryableFailingWorkflow().join()
            }
            assertThat(e.cause).isInstanceOf(NonRetryableError::class.java)
            assertThat(e.message).contains("Failing action")
        }

        @Test
        fun testUnexpectedErrorInActionShouldBeConvertedToNonRetryableError() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            val e = assertThrows<NonRetryableError> {
                workflow.unexpectedActionFailure()
            }
            assertThat(e.cause).isInstanceOf(ApplicationError::class.java)
            assertThat(e.message).contains("Illegal state error!")
        }

        @Test
        fun testActionWithCustomExceptionClassifierTreatsExceptionAsRetryable() {
            val workflow = workflowFactory<WorkflowWithCustomExceptionClassifier>(workflowId)
            // The custom classifier treats IllegalArgumentException as retryable,
            // so this should exhaust retries and then become non-retryable
            val e = assertThrows<NonRetryableError> {
                workflow.workflowMethodWithCustomClassifier()
            }
            assertThat(e.message).contains("has exhausted all retry attempts")
            helper.waitForExecutionFlowToFinish()
        }

        @Test
        fun testActionWithoutCustomExceptionClassifierUsesGlobalClassifier() {
            val workflow = workflowFactory<WorkflowWithCustomExceptionClassifier>(workflowId)
            // Without custom classifier, IllegalArgumentException should be non-retryable immediately
            val e = assertThrows<NonRetryableError> {
                workflow.workflowMethodWithoutCustomClassifier()
            }
            // Should not say "exhausted all retry attempts" because it should fail immediately
            assertThat(e.message).contains("This should be non-retryable with global classifier")
            helper.waitForExecutionFlowToFinish()
        }

        @Test
        fun testInvokeCompletableFutureWorkflowMethodWithInvalidReturnType() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            val e =
                assertThrows<CompletionException> {
                    workflow.completableFutureWorkflowMethodWithInvalidReturnType().join()
                }
            assertThat(e.cause).isInstanceOf(ClassCastException::class.java)
            verify(testClient).action("completableFutureExecuteMethod")
        }

        @Disabled("Temporarily disabled until we introduce exception DTO")
        @Test
        fun testInvokeCompletableFutureWorkflowMethodWithInvalidActionReturnType() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            val e =
                assertThrows<NonRetryableError> {
                    workflow.completableFutureWorkflowMethodWithInvalidActionReturnType()
                }
            assertThat(e.cause?.cause).isInstanceOf(ClassCastException::class.java)
            verify(testClient).action("completableFutureExecuteMethodWithInvalidReturnType")
        }

        @Test
        fun testInvokeNonWorkflowMethod() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            assertEquals("Non-execute method executed", workflow.nonWorkflowMethod())
            verify(testClient).action("nonExecuteMethod")
        }

        @Test
        fun testNonExecuteHelperPropagatesErrorMessageFromNestedExecuteAction() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            val e = assertThrows<NonRetryableError> {
                workflow.nonExecuteHelperWorkflow()
            }
            assertThat(e.message).contains("error message from @Execute action")
        }

        @Test
        fun testWaitingWorkflow() {
            val workflowId = workflowId
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            val err =
                assertThrows<CompletionException> {
                    workflow.waitingWorkflow("test").join()
                }
            assertThat(err.cause).isInstanceOf(WaitSignal::class.java)
            // Wait for workflow to reach WAITING state before sending signal
            helper.expectWorkflowToWait()
            // Now, let's signal the workflow to proceed
            workflow.updateShouldProceed(true)
            helper.waitForWorkflowToComplete()
            assertEquals("Waiting workflow executed", workflow.waitingWorkflow("test").join())
            // Verify that the workflow execution task has been removed from the scheduler
            helper.waitForExecutionFlowToFinish()
            // Verify that the workflow timers were created and cancelled correctly
            val timers = workflowStore.getTimers(workflowId)
            assertThat(timers).isNotEmpty
            assertThat(timers[0].workflowId).isEqualTo(workflowId)
            assertThat(timers[0].status).isEqualTo(Timer.Status.CANCELLED)
        }

        @Test
        fun testNamedWaitingWorkflow() {
            val workflowId = workflowId
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            val err =
                assertThrows<WaitSignal> {
                    workflow.namedWaitingWorkflowMethod("test")
                }
            // Wait for workflow to reach WAITING state before sending signal
            helper.expectWorkflowToWait()

            // Now, let's signal the workflow to proceed to the first wait
            workflow.updateShouldProceedCount(1)
            // Wait for second timer to be created - this proves the first signal was processed
            // and the workflow has moved to the second wait point
            helper.waitForCondition { workflowStore.getTimer(workflowId, "second-timer").isDefined }
            helper.waitForExecutionFlowToFinish()

            // Now, let's signal the workflow to proceed to the second wait
            workflow.updateShouldProceedCount(2)
            // Wait for workflow to complete - polling handles async processing
            helper.waitForWorkflowToComplete()

            assertEquals("Waiting workflow executed", workflow.namedWaitingWorkflowMethod("test"))
            // Verify that the workflow execution task has been removed from the scheduler
            helper.waitForExecutionFlowToFinish()
            // Verify that the workflow timers were created and cancelled correctly
            val timers = workflowStore.getTimers(workflowId)
            assertThat(timers).hasSize(2)
            // Timer ordering is not guaranteed, so find timers by ID
            val firstTimer = timers.find { it.id == "first-timer" }.getOrNull()
            val secondTimer = timers.find { it.id == "second-timer" }.getOrNull()
            assertThat(firstTimer).isNotNull
            assertThat(firstTimer!!.workflowId).isEqualTo(workflowId)
            assertThat(firstTimer.status).isEqualTo(Timer.Status.CANCELLED)
            assertThat(secondTimer).isNotNull
            assertThat(secondTimer!!.workflowId).isEqualTo(workflowId)
            assertThat(secondTimer.status).isEqualTo(Timer.Status.CANCELLED)
        }

        // TODO: remove this test once these feature gates are completely ramped up.
        @Test
        fun testWaitingWorkflowWhenFeatureGatesTurnedOn() {
            whenever(featureGate.isEnabled(FeatureGate.Keys.FORCE_SIGNAL_WORKFLOW_EXEC_IN_SCHEDULER)).thenReturn(true)
            val workflowId = workflowId
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            workflow.waitingWorkflow("test")
            helper.expectWorkflowToWait()
            // Now, let's signal the workflow to proceed
            workflow.updateShouldProceed(true)
            helper.waitForWorkflowToComplete()
            assertEquals("Waiting workflow executed", workflow.waitingWorkflow("test").join())
            // Verify that the workflow execution task has been removed from the scheduler
            helper.waitForExecutionFlowToFinish()
        }

        @Test
        fun testWaitingWorkflowWhenWaitExpiresBeforeCompleted() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            helper.expectWaitSignal { workflow.waitingWorkflow("test").join() }
            // Wait for workflow to reach WAITING state before waiting for timeout
            helper.expectWorkflowToWait()
            // Wait for the workflow should time out after 5 seconds
            helper.waitForWorkflowToComplete()
            assertThat(workflow.waitingWorkflow("test").join()).isEqualTo("Waiting workflow timeout")
            // Verify that the workflow timers were created and expired correctly
            val timers = workflowStore.getTimers(workflowId)
            assertThat(timers).isNotEmpty
            assertThat(timers[0].workflowId).isEqualTo(workflowId)
            assertThat(timers[0].status).isEqualTo(Timer.Status.EXPIRED)
        }

        @Test
        fun testValidateActionsWhenExecuteMethodIsOverloaded() {
            val e = assertThrows<IllegalArgumentException> {
                workflowFactory<WorkflowWithInvalidActions1>(workflowId)
            }
            assertThat(e.message).contains("contains multiple methods with the same name annotated with @Execute or @Compensate")
        }

        @Test
        fun testWorkflowWithOverloadedWorkflowMethod() {
            val e = assertThrows<IllegalArgumentException> {
                workflowFactory<WorkflowWithOverloadedWorkflowMethod>(workflowId)
            }
            assertThat(e.message).contains("contains multiple methods with the same name annotated with @WorkflowMethod")
        }

        @Test
        fun testCloneWorkflow() {
            val cloneWorkflowId = UUID.randomUUID().toString()
            workflowFactory<SampleWorkflow>(workflowId).workflowMethod()
            workflowFactory.cloneAsNew(workflowId, cloneWorkflowId, TestRequestContext.getCurrentRequestContext())
            val workflow = skipperEngine.getWorkflow(cloneWorkflowId)
            assertThat(workflow.isDefined).isTrue()
        }

        @Test
        fun testSignalThatThrows() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            helper.expectWaitSignal { workflow.waitingWorkflow("test").join() }
            // Wait for workflow to be in WAITING state before sending signal
            helper.expectWorkflowToWait()
            val e = assertThrows<NonRetryableError> {
                workflow.signalThatThrows()
            }
            assertThat(e.cause).isInstanceOf(ApplicationError::class.java)
            assertThat(e.message).contains("Signal that throws")
        }

        @Test
        fun testGetWorkflowInstanceView() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            workflow.workflowMethod()
            val workflowInstanceView = workflow.getWorkflowInstanceView()
            assertThat(workflowInstanceView.id).isEqualTo(workflowId)
            assertThat(workflowInstanceView.status).isEqualTo(WorkflowInstanceStatusView.COMPLETED)
            assertThat(workflowInstanceView.workflowClass).isEqualTo(SampleWorkflow::class.java.name)
            assertThat(workflowInstanceView.actionCheckpoints.size).isEqualTo(1)
            assertThat(workflowInstanceView.actionCheckpoints.get(0).workflowId).isEqualTo(workflowId)
            assertThat(workflowInstanceView.actionCheckpoints.get(0).actionMethod).isEqualTo("executeMethod")
            assertThat(workflowInstanceView.actionCheckpoints.get(0).actionClass).isEqualTo(SampleActions::class.java.name)
            assertThat(workflowInstanceView.actionCheckpoints.get(0).iteration).isEqualTo(0)
            assertThat(workflowInstanceView.actionCheckpoints.get(0).result.isSuccess).isTrue()
            assertThat(workflowInstanceView.actionCheckpoints.get(0).result.getOrNull()).isEqualTo("Execute method executed")
        }

        @Test
        fun testCancelWorkflow() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            workflow.waitingWorkflow("test")
            helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.WAITING)
            val cancelledWorkflow = workflow.cancelWorkflowInstance("cancel workflow")
            helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.CANCELLED)
            assertThat(cancelledWorkflow.status).isEqualTo(WorkflowInstanceStatusView.CANCELLED)
        }

        @Test
        fun testWaitingWorkflowWithNoWait() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            val result = workflow.waitingWorkflowWithNoWait("test").join()
            assertEquals("Waiting workflow executed", result)
            // Verify that the timer was created and cancelled correctly
            val timers = workflowStore.getTimers(workflowId)
            assertThat(timers).isNotEmpty
            assertThat(timers[0].workflowId).isEqualTo(workflowId)
            assertThat(timers[0].status).isEqualTo(Timer.Status.CANCELLED)
        }

        @Test
        fun `waitUntil without timeout returns true when condition met via signal`() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            workflow.unboundedWaitingWorkflow("test")
            helper.expectWorkflowToWait()

            workflow.updateShouldProceed(true)
            helper.waitForWorkflowToComplete()

            val result = workflow.unboundedWaitingWorkflow("test").join()
            assertThat(result).isEqualTo("Waiting workflow executed: success=true")
        }

        @Test
        fun `waitUntil with timeout returns true when condition met before timeout`() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            workflow.waitingWorkflow("test")
            helper.expectWorkflowToWait()

            workflow.updateShouldProceed(true)
            helper.waitForWorkflowToComplete()

            val result = workflow.waitingWorkflow("test").join()
            assertThat(result).isEqualTo("Waiting workflow executed")
        }

        @Test
        fun `waitUntil with timeout returns false when timeout fires`() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            helper.expectWaitSignal { workflow.waitingWorkflow("test").join() }
            helper.expectWorkflowToWait()
            helper.waitForWorkflowToComplete()

            val result = workflow.waitingWorkflow("test").join()
            assertThat(result).isEqualTo("Waiting workflow timeout")
        }

        @Test
        fun testActionWithMultipleArgs() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            val result = workflow.sayNameAndAge()
            assertThat(result).isEqualTo("Name: John, Age: 30")
        }
    }

    @Nested
    inner class TestsWithCheckpointAfterExecutionMode : SuiteBase() {
        override fun customizeDeps(deps: TestRuntime) {
            deps.clock = Clock.systemUTC()
            deps.config.defaultCheckpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT
        }

        @Test
        fun testInvokeWorkflowMethod() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            assertEquals("Execute method executed", workflow.workflowMethod())
            verify(testClient).action("executeMethod")
        }
    }

    @Nested
    inner class TestsWithCompensateCheckpointModes : SuiteBase() {
        override fun customizeDeps(deps: TestRuntime) {
            deps.clock = Clock.systemUTC()
            deps.config.defaultCheckpointMode = CheckpointMode.EVENTUAL_CHECKPOINT
        }

        @BeforeEach
        override fun setUp() {
            super.setUp()
            clearInvocations(testClient)
        }

        @Disabled(
            "Transaction-scope violation when IMMEDIATE_CHECKPOINT compensation runs outside the Guice-managed lifecycle. " +
                "Same test passes in CompensationTest (which uses Guice). " +
                "Tracked for fix when the TestRuntime integration is improved."
        )
        @Test
        fun testCompensateWithImmediateCheckpointMode() {
            val workflow = workflowFactory<CompensationWorkflow>(workflowId)

            // Workflow will fail after execute action succeeds, triggering compensation
            val error = assertThrows<NonRetryableError> {
                workflow.workflowWithImmediateCheckpointCompensation()
            }
            assertThat(error.message).contains("Intentional failure to trigger compensation")

            // Wait for compensation flow to complete
            helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.COMPENSATION_COMPLETED)
            helper.waitForExecutionFlowToFinish() // Ensure all async processing completes

            // Verify that checkpoints were created for both execute and compensate actions
            val workflowInstanceView = workflow.getWorkflowInstanceView()
            assertThat(workflowInstanceView.actionCheckpoints.size).isGreaterThanOrEqualTo(2)

            // Find the execute action checkpoint
            val executeCheckpoint = workflowInstanceView.actionCheckpoints
                .find { it.actionMethod == "executeActionWithImmediateCompensation" }
            assertThat(executeCheckpoint).isNotNull()
            assertThat(executeCheckpoint!!.result.isSuccess).isTrue()

            // Find the compensate action checkpoint - this verifies compensation ran
            val compensateCheckpoint = workflowInstanceView.actionCheckpoints
                .find { it.actionMethod == "compensateImmediateCheckpoint" }
            assertThat(compensateCheckpoint).isNotNull()
            assertThat(compensateCheckpoint!!.result.isSuccess).isTrue()
        }

        @Test
        fun testCompensateWithEventualCheckpointMode() {
            val workflow = workflowFactory<CompensationWorkflow>(workflowId)

            // Workflow will fail after execute action succeeds, triggering compensation
            val error = assertThrows<NonRetryableError> {
                workflow.workflowWithEventualCheckpointCompensation()
            }
            assertThat(error.message).contains("Intentional failure to trigger compensation")

            // Wait for compensation flow to complete
            helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.COMPENSATION_COMPLETED)
            helper.waitForExecutionFlowToFinish() // Ensure all async processing completes

            // Verify that checkpoints were created for both execute and compensate actions
            val workflowInstanceView = workflow.getWorkflowInstanceView()
            assertThat(workflowInstanceView.actionCheckpoints.size).isGreaterThanOrEqualTo(2)

            // Find the execute action checkpoint
            val executeCheckpoint = workflowInstanceView.actionCheckpoints
                .find { it.actionMethod == "executeActionWithEventualCompensation" }
            assertThat(executeCheckpoint).isNotNull()
            assertThat(executeCheckpoint!!.result.isSuccess).isTrue()

            // Find the compensate action checkpoint - this verifies compensation ran
            val compensateCheckpoint = workflowInstanceView.actionCheckpoints
                .find { it.actionMethod == "compensateEventualCheckpoint" }
            assertThat(compensateCheckpoint).isNotNull()
            assertThat(compensateCheckpoint!!.result.isSuccess).isTrue()
        }

        @Test
        fun testCompensateWithNoCheckpointModeStillCreatesCheckpoint() {
            val workflow = workflowFactory<CompensationWorkflow>(workflowId)

            // Workflow will fail after execute action succeeds, triggering compensation
            val error = assertThrows<NonRetryableError> {
                workflow.workflowWithNoCheckpointCompensation()
            }
            assertThat(error.message).contains("Intentional failure to trigger compensation")

            // Wait for compensation flow to complete
            helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.COMPENSATION_COMPLETED)
            helper.waitForExecutionFlowToFinish() // Ensure all async processing completes

            // Verify that checkpoints were created even with NO_CHECKPOINT mode
            // (compensable actions are always checkpointed)
            val workflowInstanceView = workflow.getWorkflowInstanceView()
            assertThat(workflowInstanceView.actionCheckpoints.size).isGreaterThanOrEqualTo(2)

            // Find the execute action checkpoint
            val executeCheckpoint = workflowInstanceView.actionCheckpoints
                .find { it.actionMethod == "executeActionWithNoCheckpointCompensation" }
            assertThat(executeCheckpoint).isNotNull()
            assertThat(executeCheckpoint!!.result.isSuccess).isTrue()

            // Find the compensate action checkpoint - this verifies compensation ran
            val compensateCheckpoint = workflowInstanceView.actionCheckpoints
                .find { it.actionMethod == "compensateNoCheckpoint" }
            assertThat(compensateCheckpoint).isNotNull()
            assertThat(compensateCheckpoint!!.result.isSuccess).isTrue()
        }
    }

    @Nested
    inner class TestExecutionTimeout : SuiteBase() {
        val mockCallbackHandler: DemoCallbackHandler2 = mock()

        open inner class DemoCallbackHandler2 : DemoCallbackHandler()

        override fun customizeDeps(deps: TestRuntime) {
            deps.clock = Clock.systemUTC()
            deps.config.workflowOptions = WorkflowOptions(executionTimeout = Duration.ofSeconds(5))
            deps.addBinding(DemoCallbackHandler2::class.java, mockCallbackHandler)
        }

        @Test
        fun testWorkflowExecutionTimeout() {
            // This workflow will wait forever (unbounded), which is longer than the execution timeout (5s)
            // so it should time out before the wait is completed.
            val workflow = workflowFactory<SampleWorkflow>(
                workflowId,
                TestRequestContext.getCurrentRequestContext(),
                DemoCallbackHandler2::class.java
            )

            workflow.unboundedWaitingWorkflow("test")

            // Wait for the workflow to enter WAITING state first
            helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.WAITING)

            // Wait for the execution timeout (5s) to elapse in real wall-clock time
            // Add 1s buffer to ensure the EXECUTION_TIMEOUT task is ready
            helper.waitFor(6000)

            // Explicitly fetch and queue the EXECUTION_TIMEOUT task (if not already picked up by background scheduler)
            // This removes dependency on background scheduler polling timing
            fetchAndQueueReadyTasks()

            // Wait for the EXECUTION_TIMEOUT task to be processed
            // This will schedule a WORKFLOW task to check for timeout
            helper.waitFor(500)

            // Fetch and queue the WORKFLOW task that was scheduled by ExecutionTimeoutHandler
            fetchAndQueueReadyTasks()

            // Now wait for the workflow to reach TIMEOUT status
            helper.waitForWorkflowToReachStatus(
                setOf(WorkflowInstance.Status.TIMEOUT),
                maxAttempts = 20,
                sleepTimeMs = 250
            )

            // Wait for execution flow to finish to ensure callbacks have been processed
            helper.waitForExecutionFlowToFinish()
            verify(mockCallbackHandler, times(1)).onWorkflowTimeout(any())
        }

        @Disabled("This is a slow test. Run manually as needed")
        @Test
        fun testWorkflowTimeoutBeforeTimerExecutes() {
            val workflow = workflowFactory<SampleWorkflow>(
                workflowId,
                TestRequestContext.getCurrentRequestContext(),
                DemoCallbackHandler2::class.java
            )
            workflow.waitingWorkflow("test")
            helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.TIMEOUT)
            verify(mockCallbackHandler, times(1)).onWorkflowTimeout(any())
            // Let's wait 5 seconds, which should be enough for the wait inside the workflow to timeout.
            helper.waitFor(5000)
            verify(mockCallbackHandler, times(0)).onNonRetryableError(any(), any())
            helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.TIMEOUT)
            // Verify that there are no tasks scheduled for the workflow instance
            assertEquals(0, scheduler.realSize())
        }
    }

    @Nested
    inner class TestLongWaitTimeout : SuiteBase() {
        // Use a short wait duration (5s) that can be tested with real wall-clock time
        // 5 seconds provides enough buffer for test environment timing variations
        private val shortWaitDuration: Duration = Duration.ofSeconds(5)

        override fun customizeDeps(deps: TestRuntime) {
            deps.clock = Clock.systemUTC()
            deps.config.workflowOptions = WorkflowOptions(executionTimeout = Duration.ofSeconds(30))
        }

        @Test
        fun testLongWaitTimeout() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            workflow.longWaitingWorkflow(shortWaitDuration)
            // Wait for the workflow to complete after the short wait duration expires
            // The wait timeout (5s) will expire in real time, then workflow completes
            helper.waitForWorkflowToReachStatus(
                setOf(WorkflowInstance.Status.COMPLETED, WorkflowInstance.Status.ERROR, WorkflowInstance.Status.TIMEOUT),
                maxAttempts = 100,
                sleepTimeMs = 500
            )
            assertThat(workflow.longWaitingWorkflow(shortWaitDuration).join()).isEqualTo("Waiting workflow timeout")
        }
    }

    @Nested
    inner class TestComplexWaits : SuiteBase() {
        override fun customizeDeps(deps: TestRuntime) {
            deps.clock = Clock.systemUTC()
        }

        @Test
        fun testComplexWaitWorkflow() {
            val workflow = workflowFactory<WaitWorkflow>(workflowId)
            val limit = 3
            workflow.waitWorkflow(limit)
            helper.expectWorkflowToWait()
            workflow.incrementCounter()
            assertThat(workflow.getCounterValue()).isEqualTo(1)
            helper.expectWorkflowToWait()
            workflow.incrementCounter()
            helper.expectWorkflowToWait()
            workflow.incrementCounter()
            helper.waitForWorkflowToComplete()
            val result = workflow.waitWorkflow(limit).join()
            assertThat(result).isEqualTo(limit)
            assertThat(workflow.getCounterValue()).isEqualTo(limit)
            // Verify timers were cancelled correctly (ordering is not guaranteed)
            val timers = workflowStore.getTimers(workflowId)
            assertThat(timers.size()).isEqualTo(limit)
            assertThat(timers).allMatch { it.status == Timer.Status.CANCELLED }
        }
    }

    @Nested
    inner class TestQueryMethod : SuiteBase() {
        override fun customizeDeps(deps: TestRuntime) {
            deps.clock = Clock.systemUTC()
        }

        @Test
        fun testQueryMethod() {
            val workflow = workflowFactory<WaitWorkflow>(workflowId)
            // Attempting to use a query method before the workflow is started should result in exception
            assertThrows<IllegalStateException> { workflow.getCounterValue() }
            helper.expectWaitSignal { workflow.waitWorkflow(1).join() }
            // Wait for workflow to reach WAITING state before checking/sending signals
            helper.expectWorkflowToWait()
            assertThat(workflow.getCounterValue()).isEqualTo(0)
            assertThat(workflow.getStatusValue()).isEqualTo("in_loop")
            workflow.incrementCounter()
            helper.waitForWorkflowToComplete()
            assertThat(workflow.getCounterAsync().join()).isEqualTo(1)
            assertThat(workflow.getStatusValue()).isEqualTo("finished")
        }
    }

    @Nested
    inner class TestQueryMethodAllowNonExistentWorkflow : SuiteBase() {
        override fun customizeDeps(deps: TestRuntime) {
            deps.clock = Clock.systemUTC()
            deps.config.workflowOptions = WorkflowOptions(allowQueryOnNonExistentWorkflow = true)
        }

        @Test
        fun testQueryMethodAllowNonExistentWorkflow() {
            val workflow = workflowFactory<WaitWorkflow>(workflowId)
            assertThat(workflow.getCounterValue()).isEqualTo(0)
            helper.expectWaitSignal { workflow.waitWorkflow(1).join() }
            // Wait for workflow to reach WAITING state before checking/sending signals
            helper.expectWorkflowToWait()
            assertThat(workflow.getCounterValue()).isEqualTo(0)
            assertThat(workflow.getStatusValue()).isEqualTo("in_loop")
            workflow.incrementCounter()
            helper.waitForWorkflowToComplete()
            assertThat(workflow.getCounterAsync().join()).isEqualTo(1)
            assertThat(workflow.getStatusValue()).isEqualTo("finished")
        }
    }

    @Nested
    inner class TestActionLevelRetryStrategy : SuiteBase() {
        override fun customizeDeps(deps: TestRuntime) {
            deps.clock = Clock.systemUTC()
        }

        @Test
        fun testActionLevelRetryStrategy() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            workflow.unexpectedActionFailureWithCustomRetryStrategy()
            // Wait for workflow to complete (it will fail after exhausting retries)
            helper.waitForWorkflowToComplete()
            helper.waitForExecutionFlowToFinish()
            verify(testClient, times(11)).action("failingActionWithCustomRetryStrategy")
        }
    }

    @Nested
    inner class TestLeaseRenewal : SuiteBase() {
        override fun customizeDeps(deps: TestRuntime) {
            deps.clock = Clock.systemUTC()
            deps.config.schedulerTaskLeaseDuration = Duration.ofSeconds(5)
            deps.config.leaseRenewalGracePeriod = Duration.ofSeconds(1)
        }

        @BeforeEach
        override fun setUp() {
            super.setUp()
            whenever(featureGate.isEnabled(FeatureGate.Keys.AUTOMATIC_LEASE_RENEWAL))
                .thenReturn(true)
        }

        @Test
        fun testLeaseRenewal() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            workflow.longRunningWorkflow(18).join()
            helper.waitForExecutionFlowToFinish(30, 1000)
            assertThat(scheduler.realSize()).isEqualTo(0)
            assertThat(deps.leaseRenewalManager.totalTasksInFlight).isEqualTo(0)
        }
    }

    @Nested
    inner class TestLambdaCheckpoints : SuiteBase() {
        override fun customizeDeps(deps: TestRuntime) {
            deps.clock = MutableClock.getInstance(Clock.systemUTC())
        }

        @Test
        fun testLambdaCheckpoints() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            workflow.workflowWithCheckpointLambdas()
            helper.expectWorkflowToWait()
            (deps.clock as MutableClock).fastForward(Duration.ofSeconds(1))
            helper.expectWorkflowToWait()
            (deps.clock as MutableClock).fastForward(Duration.ofSeconds(1))
            helper.waitForWorkflowToComplete()
            val result = workflow.workflowWithCheckpointLambdas().get()
            assertThat(result).isEqualTo("123")
        }

        @Test
        fun testNamedLambdaCheckpoints_produceNamedCheckpointsAndSurviveReplay() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            workflow.workflowWithNamedCheckpointLambdas()
            helper.expectWorkflowToWait()
            (deps.clock as MutableClock).fastForward(Duration.ofSeconds(1))
            helper.waitForWorkflowToComplete()

            // Result reflects that each named block executed exactly once, including across the
            // waitUntil-triggered replay. If the first checkpoint re-executed on replay, the
            // result would be "AAB" instead of "AB".
            val result = workflow.workflowWithNamedCheckpointLambdas().get()
            assertThat(result).isEqualTo("AB")

            // Persisted checkpoints carry the names supplied to checkpoint(name, lambda).
            val stored = workflowStore.getActionCheckpoints(workflowId)
            val names = stored.map { it.checkpointTag.checkpointName }.toJavaList()
            assertThat(names).contains("step-one", "step-two")
        }

        @Test
        fun testMixedNamedAndPositionalActionInvocations_executeOnceAndCoexist() {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            workflow.workflowWithMixedNamedAndPositionalActions()
            helper.expectWorkflowToWait()
            (deps.clock as MutableClock).fastForward(Duration.ofSeconds(1))
            helper.waitForWorkflowToComplete()

            val result = workflow.workflowWithMixedNamedAndPositionalActions().get()
            assertThat(result).isEqualTo(
                "Execute method executed|Execute method executed|Execute method executed|Execute method executed"
            )

            // Each of the four invocations of executeMethod runs exactly once across the
            // wait-triggered replay; cached results are reused for both the positional and the
            // named call sites.
            verify(testClient, times(4)).action("executeMethod")

            val stored = workflowStore.getActionCheckpoints(workflowId)
            val tags = stored.toJavaList().map { it.checkpointTag }
            // Two named checkpoints survive replay with their assigned names.
            val names = tags.mapNotNull { it.checkpointName }
            assertThat(names).containsExactlyInAnyOrder("named-before-wait", "named-after-wait")
            // The two positional invocations are stored with null checkpointName and distinct
            // iteration counters (0 and 1) so they don't collide with each other or the named ones.
            val positionalIterations = tags
                .filter { it.checkpointName == null && it.actionMethod == "executeMethod" }
                .map { it.iteration }
            assertThat(positionalIterations).containsExactlyInAnyOrder(0L, 1L)
        }
    }

    @Nested
    inner class TestVersionGate : SuiteBase() {
        override fun customizeDeps(deps: TestRuntime) {
            deps.clock = MutableClock.getInstance(Clock.systemUTC())
        }

        @Test
        fun testVersionGate_returnsMaxVersionOnFirstExecution() {
            SampleWorkflow.currentMaxVersion = 2
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            workflow.workflowWithVersionGate()
            helper.expectWorkflowToWait()
            (deps.clock as MutableClock).fastForward(Duration.ofSeconds(1))
            helper.waitForWorkflowToComplete()

            val result = workflow.workflowWithVersionGate().get()
            assertThat(result).isEqualTo("v2")

            // Persisted under the conventional "version:{changeId}" name.
            val stored = workflowStore.getActionCheckpoints(workflowId)
            val names = stored.map { it.checkpointTag.checkpointName }.toJavaList()
            assertThat(names).contains("version:payment-migration")
        }

        @Test
        fun testVersionGate_returnsStoredVersionOnReplay_evenWhenMaxVersionBumped() {
            // Deploy 1: workflow runs at maxVersion=1, in-flight when waitUntil hits.
            SampleWorkflow.currentMaxVersion = 1
            val v1Workflow = workflowFactory<SampleWorkflow>(workflowId)
            v1Workflow.workflowWithVersionGate()
            helper.expectWorkflowToWait()

            // Confirm version 1 was persisted before the simulated redeploy.
            val storedAfterV1 = workflowStore.getActionCheckpoints(workflowId).toJavaList()
            val v1Checkpoint =
                storedAfterV1.first { it.checkpointTag.checkpointName == "version:payment-migration" }
            assertThat(v1Checkpoint.result.get()).isEqualTo(1)

            // Deploy 2: bump maxVersion to 2 and resume the in-flight workflow. Replay must
            // return the persisted v1 value, not the new maxVersion.
            SampleWorkflow.currentMaxVersion = 2
            (deps.clock as MutableClock).fastForward(Duration.ofSeconds(1))
            helper.waitForWorkflowToComplete()

            val resumed = workflowFactory<SampleWorkflow>(workflowId)
            val result = resumed.workflowWithVersionGate().get()
            assertThat(result).isEqualTo("v1")
        }

        @Test
        fun testVersionGate_oldInstancesTakeLegacyBranch_newInstancesTakeNewBranch() {
            // In-flight (v=1) instance: started while only maxVersion=1 existed in code.
            SampleWorkflow.currentMaxVersion = 1
            val oldWorkflowId = "$workflowId-old"
            val oldWorkflow = workflowFactory<SampleWorkflow>(oldWorkflowId)
            assertThat(oldWorkflow.workflowWithVersionGateBranching().get()).isEqualTo("legacy-branch")

            // Simulated redeploy: maxVersion bumped to 2 and the new branch is now available.
            SampleWorkflow.currentMaxVersion = 2

            // Replaying the same workflow id should still take the legacy branch because the
            // persisted version is 1.
            val resumedOld = workflowFactory<SampleWorkflow>(oldWorkflowId)
            val oldResult = resumedOld.workflowWithVersionGateBranching().get()
            assertThat(oldResult).isEqualTo("legacy-branch")

            // A brand-new instance launched against the v2 code path takes the new branch.
            val newWorkflowId = "$workflowId-new"
            val newWorkflow = workflowFactory<SampleWorkflow>(newWorkflowId)
            val newResult = newWorkflow.workflowWithVersionGateBranching().get()
            assertThat(newResult).isEqualTo("new-branch")
        }
    }

    @Nested
    inner class TestContentionScenarios : SuiteBase() {
        override fun customizeDeps(deps: TestRuntime) {
            deps.clock = Clock.systemUTC()
            deps.config.defaultCheckpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT
        }

        @RepeatedTest(10)
        fun testWorkflowWithContention() {
            whenever(featureGate.isEnabled(FeatureGate.Keys.FORCE_SIGNAL_WORKFLOW_EXEC_IN_SCHEDULER)).thenReturn(true)
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            workflow.contentiousWorkflow()
            // Immediately after invocation, call the signal method to update the state
            workflow.updateContentiousState("ricardo")
            helper.waitForWorkflowToReachStatus(setOf(WorkflowInstance.Status.COMPLETED), 300, 100)
            val user = workflow.contentiousWorkflow().join()
            assertThat(user.name).isEqualTo("ricardo")
            assertThat(user.id).isEqualTo("123")
        }
    }

    /**
     * Integration tests that pin the request-context propagation behavior of the generic
     * RequestContextMiddleware / RequestContextSerde surfaces.
     *
     * Each test creates a workflow with an explicit caller-supplied [TestRequestContext],
     * drives it through a path that exercises a different engine surface (signal, compensation,
     * persistence reload), and asserts the captured `userId` on the relevant handler matches
     * the original caller's userId.
     */
    @Nested
    inner class TestRequestContextPropagation : SuiteBase() {
        private lateinit var recorder: RequestContextRecorder

        override fun customizeDeps(deps: TestRuntime) {
            // Real wall clock — needed for the wait/timeout used in
            // `testWorkflowResumptionAfterWaitPreservesPersistedRequestContext`.
            deps.clock = Clock.systemUTC()
            recorder = RequestContextRecorder()
            deps.addBinding(RequestContextRecorder::class.java, recorder)
        }

        /**
         * Pins the `WorkflowFactory.kt` gate: `middleware.onCreate` only fires for workflow-method
         * invocations. Signals against an existing workflow must NOT run `onCreate` (which would
         * otherwise replace the persisted request context with the caller's empty thread-local
         * fallback). Regression coverage for the signal-clobbers-persisted-context bug that the
         * gate fixes.
         */
        @Test
        fun testSignalPreservesPersistedRequestContext() {
            val creatorUserId = "creator-${UUID.randomUUID()}"
            val createCtx = TestRequestContext.builder().userId(creatorUserId).build()

            // Create the workflow with the explicit caller context, then drive it into WAITING.
            val workflow =
                workflowFactory<RequestContextCapturingWorkflow>(workflowId, createCtx)
            helper.expectWaitSignal { workflow.executeAndWait() }
            helper.expectWorkflowToWait()

            // Get a fresh stub via the 1-arg form — deliberately NO request context. With the
            // pre-fix proxy, `onCreate(null)` here would fall back to the empty thread-local
            // and overwrite the workflow's persisted context. With the fix, the signal flows
            // through `requestContext = null`, the engine's null-check guard skips the
            // override, and the persisted context stays intact.
            val stub = workflowFactory<RequestContextCapturingWorkflow>(workflowId)
            stub.proceed()
            helper.waitForWorkflowToComplete()

            assertThat(recorder.signalUserId)
                .describedAs("Signal handler must see the persisted context userId, not an empty TL fallback")
                .isEqualTo(creatorUserId)
        }

        /**
         * Pins the compensation-path lifecycle: when a workflow's action throws and the
         * compensation chain runs, the middleware's `beforeExecution` must install the persisted
         * request context on the executor thread-local before each compensation handler. The
         * `afterExecution` placement (a sync `finally`) must not regress compensation TL
         * visibility.
         */
        @Test
        fun testCompensationPreservesPersistedRequestContext() {
            val creatorUserId = "creator-${UUID.randomUUID()}"
            val createCtx = TestRequestContext.builder().userId(creatorUserId).build()

            val workflow =
                workflowFactory<RequestContextCompensationWorkflow>(workflowId, createCtx)

            assertThrows<NonRetryableError> { workflow.executeAndFail() }

            helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.COMPENSATION_COMPLETED)
            helper.waitForExecutionFlowToFinish()

            assertThat(recorder.compensationUserId)
                .describedAs("Compensation handler must see the persisted context userId")
                .isEqualTo(creatorUserId)
        }

        /**
         * Pins the [RequestContextSerde] round-trip end-to-end: the workflow waits, gets persisted
         * (context serialized through the serde), is reloaded by the scheduler when the signal
         * arrives, and resumes on a fresh executor thread where the middleware reinstalls the
         * context from the deserialized payload. Both pre-wait and post-resume captures must
         * observe the same userId — proves the serde and the `beforeExecution` thread-local
         * install are both wired correctly.
         */
        @Test
        fun testWorkflowResumptionAfterWaitPreservesPersistedRequestContext() {
            val creatorUserId = "creator-${UUID.randomUUID()}"
            val createCtx = TestRequestContext.builder().userId(creatorUserId).build()

            val workflow =
                workflowFactory<RequestContextCapturingWorkflow>(workflowId, createCtx)
            helper.expectWaitSignal { workflow.executeAndWait() }
            helper.expectWorkflowToWait()

            // Signal in via the 1-arg form to resume the workflow. The resumed workflow runs
            // its second `captureUserIdAfterResume()` action on a thread that received the context
            // through the storage serde + middleware re-install, not from the original API
            // call's in-memory context.
            val stub = workflowFactory<RequestContextCapturingWorkflow>(workflowId)
            stub.proceed()
            helper.waitForWorkflowToComplete()

            assertThat(recorder.actionUserIdBeforeWait)
                .describedAs("Pre-wait action must see the caller's context userId")
                .isEqualTo(creatorUserId)
            assertThat(recorder.actionUserIdAfterResume)
                .describedAs("Post-resume action must see the same context userId via serde round-trip")
                .isEqualTo(creatorUserId)
        }
    }

    @Nested
    inner class TestRewind : SuiteBase() {
        override fun customizeDeps(deps: TestRuntime) {
            deps.clock = Clock.systemUTC()
            // Immediate checkpointing makes the first action's checkpoint deterministically durable
            // before the second action fails, so it is available to survive the rewind.
            deps.config.defaultCheckpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT
        }

        @BeforeEach
        override fun setUp() {
            super.setUp()
            // The incident is "active": the failing action errors on the first execution.
            RewindActions.shouldFail = true
        }

        @AfterEach
        override fun tearDown() {
            RewindActions.shouldFail = true
            super.tearDown()
        }

        @Test
        fun testRewindRerunsFailingActionAndReachesCompletion() {
            val workflowsService = WorkflowsService(workflowStore, skipperEngine, scheduler)
            val workflow = workflowFactory<RewindWorkflow>(workflowId)

            // 1. First execution: maybeFailingAction throws a non-retryable error, driving the
            //    workflow into a terminal ERROR state.
            val error = assertThrows<NonRetryableError> {
                workflow.rewindWorkflow()
            }
            assertThat(error.message).contains("Incident: maybeFailingAction failed")
            helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.ERROR)
            helper.waitForExecutionFlowToFinish()

            // Both actions ran exactly once: the first succeeded, the second failed.
            verify(testClient, times(1)).action("firstAction")
            verify(testClient, times(1)).action("maybeFailingAction")

            // The failing action left an error checkpoint we can use as the rewind pivot, and the
            // earlier successful checkpoint is present (it must survive the rewind).
            val checkpointsBeforeRewind = workflowStore.getActionCheckpoints(workflowId).toJavaList()
            val pivotCheckpoint =
                checkpointsBeforeRewind.first { it.checkpointTag.checkpointName == "failing-step" }
            assertThat(pivotCheckpoint.result.isLeft).isTrue()
            assertThat(
                checkpointsBeforeRewind.any { it.checkpointTag.checkpointName == "first-step" }
            ).isTrue()

            // 2. The incident is resolved: the action will now succeed.
            RewindActions.shouldFail = false

            // 3. Rewind to the failing action's checkpoint. The pivot (and anything after it) is
            //    deleted, the workflow returns to RUNNING, and it is scheduled for immediate
            //    execution.
            workflowsService.rewindWorkflow(workflowId, "failing-step")

            // 4. On replay the workflow reuses the surviving first-step checkpoint (so firstAction
            //    is NOT re-run) and re-runs the failing action — which now succeeds — driving the
            //    workflow to completion.
            helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.COMPLETED)
            helper.waitForExecutionFlowToFinish()

            verify(testClient, times(1)).action("firstAction")
            verify(testClient, times(2)).action("maybeFailingAction")

            // The completed workflow yields the full result.
            assertThat(workflow.rewindWorkflow()).isEqualTo("first|second")

            // The pivot checkpoint is now recorded as a success.
            val checkpointsAfterRewind = workflowStore.getActionCheckpoints(workflowId).toJavaList()
            val finalPivot =
                checkpointsAfterRewind.first { it.checkpointTag.checkpointName == "failing-step" }
            assertThat(finalPivot.result.isRight).isTrue()
            assertThat(finalPivot.result.get()).isEqualTo("second")
        }
    }

    /**
     * Engine-level integration coverage for `@SignalMethod(persist = true)`, driven end-to-end
     * through the workflow proxy against the configured storage backend (SQLite by default).
     *
     * These exercise the durable-signal lifecycle: a PENDING row is written before the handler
     * runs, the workflow update + EXECUTED flip commit atomically on success, a persisted row can
     * be replayed in place (reusing the same row, no new record), and `persist = false` signals
     * write no rows.
     *
     * NOTE: [SuiteBase.setUp] stubs `featureGate.isEnabled(any()) == true`, which makes
     * `DISABLE_SIGNAL_PERSISTENCE` resolve to true and therefore DISABLES persistence. Every test
     * that expects rows to be written must explicitly opt persistence back on by stubbing
     * `DISABLE_SIGNAL_PERSISTENCE` to false before sending the signal.
     */
    @Nested
    inner class TestPersistedSignals : SuiteBase() {
        override fun customizeDeps(deps: TestRuntime) {
            deps.clock = Clock.systemUTC()
        }

        /** Re-enables signal persistence, which the SuiteBase blanket stub otherwise disables. */
        private fun enableSignalPersistence() {
            whenever(featureGate.isEnabled(FeatureGate.Keys.DISABLE_SIGNAL_PERSISTENCE))
                .thenReturn(false)
        }

        /** Drives a fresh [SampleWorkflow] into the WAITING state and returns the proxy. */
        private fun startWaitingWorkflow(): SampleWorkflow {
            val workflow = workflowFactory<SampleWorkflow>(workflowId)
            helper.expectWaitSignal { workflow.waitingWorkflow("test").join() }
            helper.expectWorkflowToWait()
            return workflow
        }

        @Test
        fun testPersistedSignalSuccess_writesSingleExecutedRowAndWorkflowProceeds() {
            enableSignalPersistence()
            val workflow = startWaitingWorkflow()

            // Persisted signal that flips shouldProceed; on success the workflow resumes and
            // completes, and exactly one persisted-signal row exists in EXECUTED status.
            workflow.updateShouldProceedDurably(true)
            helper.waitForWorkflowToComplete()
            assertEquals("Waiting workflow executed", workflow.waitingWorkflow("test").join())

            // The workflow-update and the EXECUTED mark may not commit atomically on every storage
            // backend, so poll until the persisted-signal row is visible before asserting on it
            // rather than reading immediately after the workflow completes.
            helper.waitForCondition { workflowStore.getPersistedSignals(workflowId).size() == 1 }
            val signals = workflowStore.getPersistedSignals(workflowId).toJavaList()
            assertThat(signals).hasSize(1)
            assertThat(signals[0].signalMethod).isEqualTo("updateShouldProceedDurably")
            assertThat(signals[0].status).isEqualTo(PersistedSignal.Status.EXECUTED)
        }

        /**
         * Pins the engine's handling of a persisted signal whose handler throws.
         *
         * A throwing handler is treated as a FAILURE: the persisted row is marked
         * [PersistedSignal.Status.FAILED] (not EXECUTED), and — the key behavior — the workflow
         * state is NOT advanced. The engine skips the `updateWorkflow` + scheduling path entirely,
         * so the instance stays exactly where it was (still WAITING on the original `waitUntil`).
         * The error is still surfaced to the caller, mirroring the non-persisted [signalThatThrows]
         * test. Recording the row as FAILED keeps it auditable and replayable while guaranteeing the
         * failed signal can never leave the workflow in a half-advanced state.
         */
        @Test
        fun testPersistedSignalThatThrows_surfacesErrorMarksRowFailedAndDoesNotAdvanceState() {
            enableSignalPersistence()
            val workflow = startWaitingWorkflow()

            val e = assertThrows<NonRetryableError> {
                workflow.durableSignalThatThrows()
            }
            assertThat(e.cause).isInstanceOf(ApplicationError::class.java)
            assertThat(e.message).contains("Durable signal that throws")

            // The persisted row records the failure rather than a successful execution.
            val signals = workflowStore.getPersistedSignals(workflowId).toJavaList()
            assertThat(signals).hasSize(1)
            assertThat(signals[0].signalMethod).isEqualTo("durableSignalThatThrows")
            assertThat(signals[0].status).isEqualTo(PersistedSignal.Status.FAILED)

            // The failed signal did not advance the workflow: it remains in its pre-signal WAITING
            // state and has neither proceeded nor completed.
            assertThat(workflow.getWorkflowInstanceView().status)
                .isEqualTo(WorkflowInstanceStatusView.WAITING)
        }

        /**
         * Replaying a persisted signal must reuse the SAME row rather than persisting a new one.
         *
         * We use a workflow that waits in a loop with countLimit = 2 so that a single persisted
         * increment leaves it WAITING (non-terminal) — replaying the persisted signal can then
         * legitimately re-run against the live instance. After the replay the workflow completes,
         * the counter reflects both the original and the replayed increment, and there is still
         * exactly one persisted-signal row (same id) in EXECUTED status.
         */
        @Test
        fun testReplaySignal_reusesSameRowAndDoesNotDuplicate() {
            enableSignalPersistence()
            val workflow = workflowFactory<WaitWorkflow>(workflowId)
            helper.expectWaitSignal { workflow.waitWorkflow(2).join() }
            helper.expectWorkflowToWait()

            // First persisted increment: counter -> 1, workflow advances to the next wait and is
            // still non-terminal. Exactly one EXECUTED row exists.
            workflow.incrementCounterDurably()
            helper.waitForCondition { workflowStore.getPersistedSignals(workflowId).size() == 1 }
            val signals = workflowStore.getPersistedSignals(workflowId).toJavaList()
            assertThat(signals).hasSize(1)
            assertThat(signals[0].signalMethod).isEqualTo("incrementCounterDurably")
            assertThat(signals[0].status).isEqualTo(PersistedSignal.Status.EXECUTED)
            val signalId = signals[0].id!!

            // Replay the SAME persisted row: counter -> 2, workflow completes.
            skipperEngine.replaySignal(workflowId, signalId)
            helper.waitForWorkflowToComplete()
            assertThat(workflow.waitWorkflow(2).join()).isEqualTo(2)

            // Replay reused the existing row (count stays 1, same id, EXECUTED) — no duplicate.
            val afterReplay = workflowStore.getPersistedSignals(workflowId).toJavaList()
            assertThat(afterReplay).hasSize(1)
            assertThat(afterReplay[0].id).isEqualTo(signalId)
            assertThat(afterReplay[0].status).isEqualTo(PersistedSignal.Status.EXECUTED)
        }

        @Test
        fun testNonPersistedSignal_writesNoRows() {
            enableSignalPersistence()
            val workflow = startWaitingWorkflow()

            // updateShouldProceed is a plain (persist = false) signal: it advances the workflow
            // but writes no persisted-signal rows even with persistence enabled.
            workflow.updateShouldProceed(true)
            helper.waitForWorkflowToComplete()
            assertEquals("Waiting workflow executed", workflow.waitingWorkflow("test").join())

            assertThat(workflowStore.getPersistedSignals(workflowId).toJavaList()).isEmpty()
        }
    }
}
