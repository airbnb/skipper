package com.airbnb.skipper.integtest

import com.airbnb.skipper.Actions
import com.airbnb.skipper.Execute
import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.FixedRetryStrategy
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.RetryStrategy
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.SignalMethod
import com.airbnb.skipper.StateField
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.internal.CheckpointTag
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.SkipperSchedulerManager
import com.airbnb.skipper.internal.api.RunRequest
import com.airbnb.skipper.internal.api.WaitSignal
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.testutils.TestHelper
import com.airbnb.skipper.testutils.TestRequestContext
import com.airbnb.skipper.testutils.TestRuntime
import com.airbnb.skipper.util.ExtraRequestData
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.mockito.kotlin.whenever

@Execution(ExecutionMode.SAME_THREAD)
class SkipperTest {
    private val clock: Clock = Clock.systemUTC()
    private val retryStrategy: RetryStrategy = FixedRetryStrategy(Duration.ofDays(1), 3)
    private val noRetry: RetryStrategy = FixedRetryStrategy(Duration.ZERO, 0)

    private lateinit var workflowStore: WorkflowStore
    private lateinit var skipperSchedulerManager: SkipperSchedulerManager
    private lateinit var skipperEngine: SkipperEngine
    private lateinit var scheduler: Scheduler
    private lateinit var featureGate: FeatureGate

    private val REQUEST_CONTEXT: TestRequestContext = TestRequestContext.builder().build()
    private val LOCAL_REQUEST_CONTEXT: Any = REQUEST_CONTEXT

    @BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        deps.setClock(clock)
        deps.config.defaultRetryStrategy = retryStrategy
        // Register the named retry strategy so GreeterAction's @Inject field is satisfied
        deps.addBinding(RetryStrategy::class.java, noRetry)

        val runtime = deps.runtime
        workflowStore = runtime.workflowStore.get()
        skipperSchedulerManager = runtime.skipperSchedulerManager.get()
        skipperEngine = runtime.skipperEngine.get()
        scheduler = runtime.scheduler.get()
        featureGate = runtime.featureGate.get()

        skipperSchedulerManager.start()
        whenever(featureGate.isEnabled(FeatureGate.Keys.FORCE_SIGNAL_WORKFLOW_EXEC_IN_SCHEDULER))
            .thenReturn(false)
    }

    @AfterEach
    fun tearDown() {
        // Stop the scheduler so its polling threads do not leak into other test classes that share
        // the same JVM and embedded MySQL (they would otherwise steal each other's scheduler tasks).
        skipperSchedulerManager.forceStop()
    }

    @Test
    @Throws(Exception::class)
    fun testSkipper() {
        val result =
            skipperEngine.startWorkflow(
                RunRequest.builder()
                    .workflowMethod("greet")
                    .workflowId("test-1")
                    .input("Ricardo")
                    .workflowClass(Greeter::class.java)
                    .requestContext(LOCAL_REQUEST_CONTEXT)
                    .extraRequestData(ExtraRequestData())
                    .build()
            )
        assertEquals(
            "Message is: Hello Ricardo!",
            result.result.get(16, TimeUnit.SECONDS)
        )
    }

    @Test
    fun testFailingWorkflow() {
        val result =
            skipperEngine.startWorkflow(
                RunRequest.builder()
                    .workflowMethod("failingWorkflow")
                    .workflowId("test-1-1")
                    .workflowClass(Greeter::class.java)
                    .requestContext(LOCAL_REQUEST_CONTEXT)
                    .extraRequestData(ExtraRequestData())
                    .build()
            )
        val error = assertThrows(ExecutionException::class.java) { result.result.get() }
        assertTrue(error.cause is NonRetryableError)
    }

    @Test
    @Throws(Exception::class)
    fun testSkipperAsyncVersion() {
        val result =
            skipperEngine.startWorkflow(
                RunRequest.builder()
                    .workflowMethod("greetAsync")
                    .workflowId("test-2")
                    .input("Ricardo")
                    .workflowClass(Greeter::class.java)
                    .requestContext(LOCAL_REQUEST_CONTEXT)
                    .extraRequestData(ExtraRequestData())
                    .build()
            )
        assertTrue(result.result.get() is CompletableFuture<*>)
        val completableFuture =
            result.result.get(10, TimeUnit.SECONDS) as CompletableFuture<*>
        assertTrue(completableFuture.isDone)
        assertEquals("Message is: Hello Ricardo!", completableFuture.get())
    }

    @Test
    @Throws(Exception::class)
    fun testWaitingMethodWithSignal() {
        val helper = TestHelper(skipperEngine, scheduler, "test-3")
        val instance =
            skipperEngine.startWorkflow(
                RunRequest.builder()
                    .workflowMethod("waitingGreeter")
                    .workflowId("test-3")
                    .workflowClass(Greeter::class.java)
                    .requestContext(LOCAL_REQUEST_CONTEXT)
                    .extraRequestData(ExtraRequestData())
                    .build()
            )
        val error = assertThrows(ExecutionException::class.java) { instance.result.get() }
        assertTrue(error.cause is WaitSignal)

        // Wait for workflow to reach WAITING state before checking checkpoints
        helper.expectWorkflowToWait()

        // Check that the action checkpoints thus far have been persisted in the store
        val checkpoints = workflowStore.getActionCheckpoints("test-3")
        assertEquals(1, checkpoints.size())
        assertEquals(
            CheckpointTag.builder()
                .workflowId("test-3")
                .actionClass(GreeterAction::class.java)
                .actionMethod("sayHello")
                .iteration(0)
                .build(),
            checkpoints.get(0).checkpointTag
        )
        assertEquals("Hello Ricardo!", checkpoints.get(0).generateResult())

        val signalResponse: SkipperEngine.SendSignalResult =
            skipperEngine.sendSignal(
                RunRequest(
                    "test-3",
                    Greeter::class.java,
                    "updateName",
                    "Ricardo",
                    LOCAL_REQUEST_CONTEXT,
                    ExtraRequestData(),
                    null,
                    null,
                    false
                )
            )

        helper.waitForWorkflowToComplete()
        val newInstance = skipperEngine.getWorkflow("test-3").get()
        val resp = newInstance.result.get()
        @Suppress("UNCHECKED_CAST")
        assertEquals("Message is: Ricardo", (resp as CompletableFuture<String>).get())
    }

    @Test
    fun testCloneWorkflow() {
        skipperEngine.startWorkflow(
            RunRequest.builder()
                .workflowMethod("greet")
                .workflowId("test-4")
                .input("Ricardo")
                .workflowClass(Greeter::class.java)
                .requestContext(LOCAL_REQUEST_CONTEXT)
                .extraRequestData(ExtraRequestData())
                .build()
        )
        skipperEngine.cloneWorkflowInstance("test-4", "test-5", LOCAL_REQUEST_CONTEXT)
        val workflow = skipperEngine.getWorkflow("test-5")
        assertTrue(workflow.isDefined)
        assertEquals("Ricardo", workflow.get().input)
        assertEquals(Greeter::class.java, workflow.get().workflowClass)
        assertEquals("greet", workflow.get().workflowMethod)
    }

    class GreeterAction : Actions() {
        @Inject
        override lateinit var retryStrategy: RetryStrategy

        override fun retryStrategyProvider(): RetryStrategy {
            return retryStrategy
        }

        @Execute
        fun sayHello(name: String): String {
            return "Hello $name!"
        }

        @Execute(returnType = String::class)
        fun sayHelloAsync(name: String): CompletableFuture<String> {
            return CompletableFuture.completedFuture("Hello $name!")
        }

        @Execute
        fun failingAction(): String {
            throw RetryableError("This is a test exception")
        }
    }

    class Greeter : Workflow() {
        private val greetActions = actions<GreeterAction>()
        @StateField var updatedName: String? = null

        @WorkflowMethod
        fun greet(name: String): String {
            val message = greetActions.sayHello(name)
            return "Message is: $message"
        }

        @WorkflowMethod
        fun greetAsync(name: String): CompletableFuture<String> {
            return greetActions.sayHelloAsync(name).thenApply { message -> "Message is: $message" }
        }

        @WorkflowMethod
        fun waitingGreeter(): CompletableFuture<String> {
            greetActions.sayHello("Ricardo")
            waitUntil { updatedName != null }
            return CompletableFuture.completedFuture("Message is: $updatedName")
        }

        @WorkflowMethod
        fun failingWorkflow(): CompletableFuture<Void> {
            greetActions.failingAction()
            return CompletableFuture.completedFuture(null)
        }

        @SignalMethod
        fun updateName(name: String) {
            updatedName = name
        }
    }
}
