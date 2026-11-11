package com.airbnb.skipper.integtest

import com.airbnb.skipper.Actions
import com.airbnb.skipper.Execute
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.SkipperSchedulerManager
import com.airbnb.skipper.internal.api.RunRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.SchedulerExecutionQueue
import com.airbnb.skipper.testutils.TestHelper
import com.airbnb.skipper.testutils.TestRequestContext
import com.airbnb.skipper.testutils.TestRuntime
import com.airbnb.skipper.util.ExtraRequestData
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * A whole runtime with `schedulerMaxConcurrentTasks` set: it fetches no more workflows than it has
 * room for, a workflow started in the process still runs past the limit, and the backlog drains
 * once slots free up.
 */
@Execution(ExecutionMode.SAME_THREAD)
class SchedulerConcurrencyLimitTest {
    private lateinit var engine: SkipperEngine
    private lateinit var scheduler: Scheduler
    private lateinit var queue: SchedulerExecutionQueue
    private lateinit var manager: SkipperSchedulerManager
    private val callers = Executors.newCachedThreadPool()

    @BeforeEach
    fun setUp() {
        GatedActions.reset()
        val deps = TestRuntime()
        deps.config.schedulerMaxConcurrentTasks = LIMIT
        val runtime = deps.runtime
        engine = runtime.skipperEngine.get()
        scheduler = runtime.scheduler.get()
        queue = runtime.schedulerExecutionQueue.get()
        manager = runtime.skipperSchedulerManager.get()
        manager.start()
    }

    @AfterEach
    fun tearDown() {
        GatedActions.release.countDown()
        manager.forceStop()
        callers.shutdownNow()
    }

    @Test
    fun fetchedWorkflowsStayWithinTheLimitAndAnInProcessStartGoesPastIt() {
        val fetched = (1..5).map { start("fetched-$it-${System.nanoTime()}", runAsync = true) }
        val anyHelper = TestHelper(engine, scheduler, fetched.first())
        anyHelper.waitForCondition { GatedActions.inFlight.get() == LIMIT }
        // Give the fetch loop several rounds to over-fetch if it were going to.
        Thread.sleep(2_500)
        assertEquals(LIMIT, GatedActions.started.get(), "no more workflows are fetched while the instance is full")
        assertEquals(LIMIT, queue.admittedCount)

        // A caller in this process is waiting on its workflow, so it is admitted anyway.
        val inProcess = "in-process-${System.nanoTime()}"
        callers.execute { start(inProcess, runAsync = false) }
        anyHelper.waitForCondition { GatedActions.inFlight.get() == LIMIT + 1 }
        assertEquals(LIMIT + 1, queue.admittedCount)

        GatedActions.release.countDown()
        (fetched + inProcess).forEach {
            TestHelper(engine, scheduler, it).waitForWorkflowToReachStatus(setOf(WorkflowInstance.Status.COMPLETED), maxAttempts = 200)
        }
        assertEquals(6, GatedActions.started.get())
        anyHelper.waitForCondition { queue.admittedCount == 0 }
    }

    private fun start(
        id: String,
        runAsync: Boolean,
    ): String {
        engine.startWorkflow(
            RunRequest.builder()
                .workflowId(id)
                .workflowClass(GatedWorkflow::class.java)
                .workflowMethod("run")
                .input(id)
                .requestContext(TestRequestContext.builder().build())
                .extraRequestData(ExtraRequestData())
                .runAsync(runAsync)
                .build(),
        )
        return id
    }

    open class GatedWorkflow : Workflow() {
        private val actions = actions(GatedActions::class.java)

        @WorkflowMethod
        open fun run(id: String): CompletableFuture<String> {
            actions.waitForTheGate(id)
            return CompletableFuture.completedFuture("done")
        }
    }

    open class GatedActions : Actions() {
        @Execute
        open fun waitForTheGate(id: String): String {
            started.incrementAndGet()
            inFlight.incrementAndGet()
            try {
                check(release.await(30, TimeUnit.SECONDS)) { "test never opened the gate" }
            } finally {
                inFlight.decrementAndGet()
            }
            return "ok"
        }

        companion object {
            val started = AtomicInteger()
            val inFlight = AtomicInteger()

            @Volatile var release = CountDownLatch(1)

            fun reset() {
                started.set(0)
                inFlight.set(0)
                release = CountDownLatch(1)
            }
        }
    }

    private companion object {
        const val LIMIT = 2
    }
}
