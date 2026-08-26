@file:Suppress("ForbiddenImport")

package com.airbnb.skipper

import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.SkipperSchedulerManager
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.testutils.TestHelper
import com.airbnb.skipper.testutils.TestRequestContext
import com.airbnb.skipper.testutils.TestRuntime
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Integration tests verifying end-to-end Kotlin coroutines support in Skipper.
 *
 * These tests exercise the full execution path: WorkflowFactory proxy ->
 * SkipperEngine -> WorkflowExecutor -> ActionExecutor, with all method types
 * declared as `suspend fun`.
 */
class CoroutineWorkflowTest {
    private lateinit var workflowFactory: IWorkflowFactory
    private lateinit var skipperEngine: SkipperEngine
    private lateinit var skipperSchedulerManager: SkipperSchedulerManager
    private lateinit var scheduler: Scheduler
    private lateinit var workflowStore: com.airbnb.skipper.internal.storage.WorkflowStore

    private val requestContext: TestRequestContext = TestRequestContext.builder().build()

    @BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        deps.clock = Clock.systemUTC()
        val runtime = deps.runtime
        workflowFactory = runtime.workflowFactory.get()
        skipperEngine = runtime.skipperEngine.get()
        skipperSchedulerManager = runtime.skipperSchedulerManager.get()
        scheduler = runtime.scheduler.get()
        workflowStore = runtime.workflowStore.get()

        skipperSchedulerManager.start()
        DelayingActions.actionStarted = CountDownLatch(1)
        DelayingActions.canComplete = CountDownLatch(1)
    }

    @org.junit.jupiter.api.AfterEach
    fun tearDown() {
        skipperSchedulerManager.forceStop()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Workflow definitions used in tests
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Suspend workflow method that calls suspend action methods.
     * Exercises the full suspend path: WorkflowFactory -> WorkflowExecutor ->
     * Workflow.actions() proxy -> ActionExecutor.
     */
    open class SuspendWorkflow : Workflow() {
        private val actions = actions<SuspendActions>()

        @WorkflowMethod
        suspend fun greet(name: String): String {
            val hello = actions.sayHello(name)
            val bye = actions.sayBye(name)
            return "$hello | $bye"
        }

        @WorkflowMethod
        suspend fun greetNoArgs(): String {
            return actions.sayHello("world")
        }

        @StateField var proceeded = false

        @WorkflowMethod
        suspend fun waitingGreet(name: String): String {
            actions.sayHello(name)
            waitUntil({ proceeded }, Duration.ofSeconds(10))
            return "proceeded: $name"
        }

        @SignalMethod
        suspend fun proceed() {
            proceeded = true
        }

        @QueryMethod
        suspend fun hasProceeded(): Boolean = proceeded
    }

    open class SuspendActions : Actions() {
        @Execute
        suspend fun sayHello(name: String): String = "Hello, $name!"

        @Execute
        suspend fun sayBye(name: String): String = "Bye, $name!"

        @Execute
        suspend fun failWithNonRetryable(msg: String): String = throw NonRetryableError(msg)
    }

    /**
     * Mixed workflow: suspend workflow method calling a non-suspend action.
     */
    open class MixedWorkflow : Workflow() {
        private val actions = actions<RegularActions>()

        @WorkflowMethod
        suspend fun run(input: String): String = actions.process(input)
    }

    open class RegularActions : Actions() {
        @Execute
        fun process(input: String): String = "processed: $input"
    }

    /**
     * Suspend workflow with a failing suspend action.
     */
    open class FailingWorkflow : Workflow() {
        private val actions = actions<SuspendActions>()

        @WorkflowMethod
        suspend fun run(): String = actions.failWithNonRetryable("expected failure")
    }

    /**
     * Suspend compensation workflow.
     */
    open class CompensatingWorkflow : Workflow() {
        private val actions = actions<CompensatingActions>()

        @WorkflowMethod
        suspend fun run(input: String): String {
            actions.doWork(input)
            throw NonRetryableError("intentional failure to trigger compensation")
        }
    }

    /**
     * Suspend workflow whose suspend action always throws RetryableError.
     * Used to verify that RetryableError is correctly classified as transient (not non-retryable),
     * putting the workflow into TRANSIENT_ERROR instead of ERROR.
     */
    open class RetryableFailingWorkflow : Workflow() {
        private val actions = actions<RetryableActions>()

        @WorkflowMethod
        suspend fun run(): String = actions.failWithRetryable("retryable failure")
    }

    open class RetryableActions : Actions() {
        @Execute
        suspend fun failWithRetryable(msg: String): String = throw RetryableError(msg)
    }

    open class CompensatingActions : Actions() {
        @Execute
        suspend fun doWork(input: String): String = "worked: $input"

        @Compensate(forExecute = "doWork")
        suspend fun undoWork(input: String) {
            // compensation logic (no-op in test)
        }
    }

    /**
     * Action that truly suspends via kotlinx.coroutines.delay(), causing the JVM method to
     * return COROUTINE_SUSPENDED. Used to test that the workflow executor thread is freed
     * while the action is in progress.
     */
    open class DelayingActions : Actions() {
        companion object {
            /** Latch that the test can await to know the action has started executing. */
            var actionStarted = CountDownLatch(1)

            /** Latch that the test releases to allow the action to complete. */
            var canComplete = CountDownLatch(1)
        }

        @Execute
        suspend fun delayedAction(input: String): String {
            actionStarted.countDown()
            // Wait until the test thread allows completion.
            // Use a spin + delay to truly suspend (delay returns COROUTINE_SUSPENDED).
            while (!canComplete.await(1, TimeUnit.MILLISECONDS)) {
                delay(1)
            }
            return "delayed: $input"
        }
    }

    /**
     * Workflow that calls a truly-suspending action. Used to verify non-blocking behavior.
     */
    open class DelayingWorkflow : Workflow() {
        private val actions = actions<DelayingActions>()

        @WorkflowMethod
        suspend fun run(input: String): String = actions.delayedAction(input)
    }

    /**
     * Result-less suspend workflow, used to verify detached (fire-and-forget) invocation. Shares
     * [DelayingActions] so a test can hold the workflow mid-execution and observe that the detached
     * call already returned.
     */
    open class DetachedWorkflow : Workflow() {
        private val actions = actions<DelayingActions>()

        @WorkflowMethod
        suspend fun run(input: String) {
            actions.delayedAction(input)
        }
    }

    /**
     * Suspend workflow that uses suspend checkpoint blocks.
     * Exercises the suspend overload of Workflow.checkpoint().
     */
    open class SuspendCheckpointWorkflow : Workflow() {
        @StateField var result = ""

        @WorkflowMethod
        suspend fun runWithSuspendCheckpoints(): String {
            checkpointSuspend {
                delay(1) // truly suspends inside checkpoint
                result += "1"
            }
            checkpointSuspend {
                result += "2"
            }
            return result
        }

        @StateField var replayResult = ""
        @StateField var replayWaitDone = false

        @WorkflowMethod
        suspend fun runWithSuspendCheckpointsAndWait(): String {
            checkpointSuspend {
                delay(1)
                replayResult += "A"
            }
            waitUntil({ replayWaitDone }, Duration.ofSeconds(60))
            checkpointSuspend {
                replayResult += "B"
            }
            return replayResult
        }

        @SignalMethod
        suspend fun completeReplayWait() {
            replayWaitDone = true
        }

        @StateField var namedResult = ""

        @WorkflowMethod
        suspend fun runWithNamedSuspendCheckpoints(): String {
            checkpointSuspend(name = "suspend-step-one") {
                delay(1)
                namedResult += "1"
            }
            checkpointSuspend(name = "suspend-step-two") {
                namedResult += "2"
            }
            return namedResult
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `suspend workflow method with suspend actions executes end-to-end`() {
        val workflowId = UUID.randomUUID().toString()
        val helper = TestHelper(skipperEngine, scheduler, workflowId)

        val workflow = workflowFactory<SuspendWorkflow>(workflowId, requestContext)
        // Calling a suspend @WorkflowMethod from non-suspend context via the factory proxy.
        // The proxy returns the blocking result synchronously.
        runBlocking {
            val result = workflow.greet("Alice")
            assertThat(result).isEqualTo("Hello, Alice! | Bye, Alice!")
        }
    }

    @Test
    fun `suspend workflow method with no user args executes correctly`() {
        val workflowId = UUID.randomUUID().toString()
        val workflow = workflowFactory<SuspendWorkflow>(workflowId, requestContext)
        runBlocking {
            val result = workflow.greetNoArgs()
            assertThat(result).isEqualTo("Hello, world!")
        }
    }

    @Test
    fun `mixed workflow - suspend @WorkflowMethod calling regular @Execute`() {
        val workflowId = UUID.randomUUID().toString()
        val workflow = workflowFactory<MixedWorkflow>(workflowId, requestContext)
        runBlocking {
            val result = workflow.run("foo")
            assertThat(result).isEqualTo("processed: foo")
        }
    }

    @Test
    fun `suspend workflow method errors propagate as NonRetryableError`() {
        val workflowId = UUID.randomUUID().toString()
        val workflow = workflowFactory<FailingWorkflow>(workflowId, requestContext)
        val helper = TestHelper(skipperEngine, scheduler, workflowId)

        val thrown = assertThrows<NonRetryableError> {
            runBlocking { workflow.run() }
        }
        assertThat(thrown.message).contains("expected failure")
    }

    @Test
    fun `suspend workflow method with waitUntil and suspend @SignalMethod`() {
        val workflowId = UUID.randomUUID().toString()
        val workflow = workflowFactory<SuspendWorkflow>(workflowId, requestContext)
        val helper = TestHelper(skipperEngine, scheduler, workflowId)

        // Start the workflow asynchronously (runAsync=true equivalent — use CompletableFuture)
        val instance = skipperEngine.startWorkflow(
            com.airbnb.skipper.internal.api.RunRequest(
                workflowId,
                SuspendWorkflow::class.java,
                "waitingGreet",
                "Bob",
                requestContext,
                com.airbnb.skipper.util.ExtraRequestData(),
                null,
                null,
                false,
            )
        )

        // Workflow should enter WAITING state because condition is not met yet
        helper.expectWorkflowToWait()

        // Send a suspend signal to proceed
        skipperEngine.sendSignal(
            com.airbnb.skipper.internal.api.RunRequest(
                workflowId,
                SuspendWorkflow::class.java,
                "proceed",
                null,
                requestContext,
                com.airbnb.skipper.util.ExtraRequestData(),
                null,
                null,
                false,
            )
        )

        // Wait for completion
        val completedWorkflow = helper.waitForWorkflowToComplete()
        assertThat(completedWorkflow.status).isEqualTo(WorkflowInstance.Status.COMPLETED)
        assertThat(completedWorkflow.flattenResult()?.ok).isEqualTo("proceeded: Bob")
    }

    @Test
    fun `detached suspend workflow method returns before the workflow completes`() {
        val workflowId = UUID.randomUUID().toString()
        val workflow = workflowFactory.builder(DetachedWorkflow::class.java, workflowId)
            .requestContext(requestContext)
            .detached()
            .build()
        val helper = TestHelper(skipperEngine, scheduler, workflowId)

        runBlocking { workflow.run("input") }

        // The action is executing and blocked on canComplete, so the workflow cannot be terminal.
        // A non-detached suspend call would still be suspended here waiting for the result.
        assertThat(DelayingActions.actionStarted.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(skipperEngine.getWorkflow(workflowId).get().status.isTerminal()).isFalse()

        // Releasing the action lets the workflow finish on its own — nobody is waiting on it.
        DelayingActions.canComplete.countDown()
        assertThat(helper.waitForWorkflowToComplete().status).isEqualTo(WorkflowInstance.Status.COMPLETED)
    }

    @Test
    fun `detached runAsync invocation still runs the workflow to completion`() {
        val workflowId = UUID.randomUUID().toString()
        val workflow = workflowFactory.builder(DetachedWorkflow::class.java, workflowId)
            .requestContext(requestContext)
            .runAsync()
            .detached()
            .build()
        val helper = TestHelper(skipperEngine, scheduler, workflowId)

        // runAsync hands execution entirely to the persistent scheduler. The detached call neither
        // waits for nor polls for the result, but the instance was persisted before it returned.
        runBlocking { workflow.run("input") }
        DelayingActions.canComplete.countDown()

        assertThat(helper.waitForWorkflowToComplete().status).isEqualTo(WorkflowInstance.Status.COMPLETED)
    }

    @Test
    fun `detached invocation of a result-bearing suspend workflow method is rejected`() {
        val workflowId = UUID.randomUUID().toString()
        val workflow = workflowFactory.builder(SuspendWorkflow::class.java, workflowId)
            .requestContext(requestContext)
            .detached()
            .build()

        val thrown = assertThrows<IllegalArgumentException> {
            runBlocking { workflow.greet("Alice") }
        }
        assertThat(thrown.message).contains("cannot be invoked detached because it returns a result")
    }

    @Test
    fun `suspend @QueryMethod returns current workflow state`() {
        val workflowId = UUID.randomUUID().toString()
        val workflow = workflowFactory<SuspendWorkflow>(workflowId, requestContext)
        val helper = TestHelper(skipperEngine, scheduler, workflowId)

        // Start the workflow so it enters WAITING
        val instance = skipperEngine.startWorkflow(
            com.airbnb.skipper.internal.api.RunRequest(
                workflowId,
                SuspendWorkflow::class.java,
                "waitingGreet",
                "Carol",
                requestContext,
                com.airbnb.skipper.util.ExtraRequestData(),
                null,
                null,
                false,
            )
        )
        helper.expectWorkflowToWait()

        // Query the suspend @QueryMethod — should return false (not proceeded yet)
        val queryResult = skipperEngine.invokeQueryMethod(
            com.airbnb.skipper.internal.api.RunRequest(
                workflowId,
                SuspendWorkflow::class.java,
                "hasProceeded",
                null,
                requestContext,
                com.airbnb.skipper.util.ExtraRequestData(),
                null,
                null,
                false,
            )
        )
        assertThat(queryResult).isEqualTo(false)
    }

    @Test
    fun `suspend action compensation flow triggers correctly`() {
        val workflowId = UUID.randomUUID().toString()
        val workflow = workflowFactory<CompensatingWorkflow>(workflowId, requestContext)
        val helper = TestHelper(skipperEngine, scheduler, workflowId)

        val instance = skipperEngine.startWorkflow(
            com.airbnb.skipper.internal.api.RunRequest(
                workflowId,
                CompensatingWorkflow::class.java,
                "run",
                "test-input",
                requestContext,
                com.airbnb.skipper.util.ExtraRequestData(),
                null,
                null,
                false,
            )
        )

        // Workflow should fail and trigger compensation
        val finalWorkflow = helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.COMPENSATION_COMPLETED)
        // The workflow failed intentionally and compensation must have completed successfully.
        assertThat(finalWorkflow.status).isEqualTo(WorkflowInstance.Status.COMPENSATION_COMPLETED)
    }

    @Test
    fun `suspend action RetryableError is correctly classified as transient`() {
        // Verifies that a RetryableError thrown by a suspend @Execute action is correctly
        // classified as a transient (retryable) error — not a NonRetryableError — putting
        // the workflow into TRANSIENT_ERROR rather than ERROR.
        //
        // This guards against a regression where invokeSuspendFunctionAsync throws exceptions
        // directly (not wrapped in InvocationTargetException), causing ActionErrorMapper
        // to misclassify them as non-retryable "unexpected errors".
        val workflowId = UUID.randomUUID().toString()
        val helper = TestHelper(skipperEngine, scheduler, workflowId)

        skipperEngine.startWorkflow(
            com.airbnb.skipper.internal.api.RunRequest(
                workflowId,
                RetryableFailingWorkflow::class.java,
                "run",
                null,
                requestContext,
                com.airbnb.skipper.util.ExtraRequestData(),
                null,
                null,
                false,
            )
        )

        // A RetryableError must put the workflow into TRANSIENT_ERROR (retry pending),
        // not ERROR (terminal failure).
        val instance = helper.waitForWorkflowToReachStatus(WorkflowInstance.Status.TRANSIENT_ERROR)
        assertThat(instance.status).isEqualTo(WorkflowInstance.Status.TRANSIENT_ERROR)
    }

    @Test
    fun `truly suspending action completes without blocking`() {
        // This test uses an action that truly suspends via delay() — method.invoke() returns
        // COROUTINE_SUSPENDED rather than the result. This exercises the non-blocking path:
        // 1. invokeSuspendFunctionAsync returns an incomplete CompletableFuture
        // 2. ActionExecutor's async CF handling path creates checkpoint in whenComplete
        // 3. Workflow.actions() proxy returns COROUTINE_SUSPENDED via whenCompleteAsync
        // 4. WorkflowExecutor's thenCompose chains on the incomplete future
        //
        // If any part of this chain were blocking (e.g. using runBlocking or .join()), the
        // delay() inside the action would deadlock or behave incorrectly.
        val workflowId = UUID.randomUUID().toString()
        val helper = TestHelper(skipperEngine, scheduler, workflowId)

        skipperEngine.startWorkflow(
            com.airbnb.skipper.internal.api.RunRequest(
                workflowId,
                DelayingWorkflow::class.java,
                "run",
                "test-input",
                requestContext,
                com.airbnb.skipper.util.ExtraRequestData(),
                null,
                null,
                false,
            )
        )

        // Wait for the action to start (proves execution began)
        val started = DelayingActions.actionStarted.await(10, TimeUnit.SECONDS)
        assertThat(started).isTrue()

        // Release the action to complete
        DelayingActions.canComplete.countDown()

        // The workflow should complete successfully
        val completedWorkflow = helper.waitForWorkflowToComplete()
        assertThat(completedWorkflow.status).isEqualTo(WorkflowInstance.Status.COMPLETED)
        assertThat(completedWorkflow.flattenResult()?.ok).isEqualTo("delayed: test-input")
    }

    @Test
    fun `suspend checkpoint executes suspend lambda and produces correct result`() {
        val workflowId = UUID.randomUUID().toString()
        val workflow = workflowFactory<SuspendCheckpointWorkflow>(workflowId, requestContext)
        runBlocking {
            val result = workflow.runWithSuspendCheckpoints()
            assertThat(result).isEqualTo("12")
        }
    }

    @Test
    fun `named suspend checkpoint executes and persists the checkpoint name`() {
        val workflowId = UUID.randomUUID().toString()
        val workflow = workflowFactory<SuspendCheckpointWorkflow>(workflowId, requestContext)
        runBlocking {
            val result = workflow.runWithNamedSuspendCheckpoints()
            assertThat(result).isEqualTo("12")
        }

        val stored = workflowStore.getActionCheckpoints(workflowId)
        val names = stored.map { it.checkpointTag.checkpointName }.toJavaList()
        assertThat(names).contains("suspend-step-one", "suspend-step-two")
    }

    @Test
    fun `suspend checkpoint is not re-executed on replay after waitUntil`() {
        val workflowId = UUID.randomUUID().toString()
        val helper = TestHelper(skipperEngine, scheduler, workflowId)

        skipperEngine.startWorkflow(
            com.airbnb.skipper.internal.api.RunRequest(
                workflowId,
                SuspendCheckpointWorkflow::class.java,
                "runWithSuspendCheckpointsAndWait",
                null,
                requestContext,
                com.airbnb.skipper.util.ExtraRequestData(),
                null,
                null,
                false,
            )
        )

        // First checkpoint executes, then workflow enters WAITING due to waitUntil
        helper.expectWorkflowToWait()

        // Signal the workflow to proceed: this triggers replay, and the first checkpoint should
        // NOT re-execute (it was checkpointed), second checkpoint executes, workflow completes.
        skipperEngine.sendSignal(
            com.airbnb.skipper.internal.api.RunRequest(
                workflowId,
                SuspendCheckpointWorkflow::class.java,
                "completeReplayWait",
                null,
                requestContext,
                com.airbnb.skipper.util.ExtraRequestData(),
                null,
                null,
                false,
            )
        )

        val completedWorkflow = helper.waitForWorkflowToComplete()
        assertThat(completedWorkflow.status).isEqualTo(WorkflowInstance.Status.COMPLETED)
        // "A" from first checkpoint + "B" from second = "AB"
        // If first checkpoint re-executed on replay, result would be "AAB"
        assertThat(completedWorkflow.flattenResult()?.ok).isEqualTo("AB")
    }
}
