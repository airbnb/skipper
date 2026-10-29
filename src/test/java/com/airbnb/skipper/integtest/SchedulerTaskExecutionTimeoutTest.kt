package com.airbnb.skipper.integtest

import com.airbnb.skipper.Actions
import com.airbnb.skipper.Execute
import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.SkipperSchedulerManager
import com.airbnb.skipper.internal.api.RunRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.testutils.TestHelper
import com.airbnb.skipper.testutils.TestRequestContext
import com.airbnb.skipper.testutils.TestRuntime
import com.airbnb.skipper.util.ExtraRequestData
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.mockito.kotlin.whenever

/**
 * A step that outlives its lease runs only one time while it stays within
 * `schedulerTaskExecutionTimeout`. When it runs past the timeout, a second run starts alongside the first.
 */
@Execution(ExecutionMode.SAME_THREAD)
class SchedulerTaskExecutionTimeoutTest {
    private lateinit var engine: SkipperEngine
    private lateinit var scheduler: Scheduler
    private lateinit var manager: SkipperSchedulerManager

    private fun start(executionTimeout: Duration) {
        SlowActions.executions.set(0)
        SlowActions.inFlight.set(0)
        SlowActions.maxInFlight.set(0)
        SlowActions.release = CountDownLatch(1)
        val deps = TestRuntime()
        deps.config.schedulerTaskLeaseDuration = LEASE
        deps.config.schedulerTaskExecutionTimeout = executionTimeout
        whenever(deps.featureGate.isEnabled(FeatureGate.Keys.AUTOMATIC_LEASE_RENEWAL)).thenReturn(true)
        val runtime = deps.runtime
        engine = runtime.skipperEngine.get()
        scheduler = runtime.scheduler.get()
        manager = runtime.skipperSchedulerManager.get()
        manager.start()
    }

    @AfterEach
    fun tearDown() {
        SlowActions.release.countDown()
        if (::manager.isInitialized) manager.forceStop()
    }

    @Test
    fun stepLongerThanItsLeaseRunsOnceWithinTheTimeout() {
        start(executionTimeout = Duration.ofSeconds(30))
        val helper = TestHelper(engine, scheduler, startSlowWorkflow())
        helper.waitForCondition { SlowActions.executions.get() == 1 }

        Thread.sleep(LEASE.multipliedBy(2).plusSeconds(1).toMillis())
        assertEquals(1, SlowActions.executions.get(), "the step must not run again while it is within the timeout")

        SlowActions.release.countDown()
        helper.waitForWorkflowToReachStatus(setOf(WorkflowInstance.Status.COMPLETED), maxAttempts = 200)
        assertEquals(1, SlowActions.executions.get())
        assertEquals(1, SlowActions.maxInFlight.get())
    }

    @Test
    fun stepOutlastingTheTimeoutRunsAgain() {
        start(executionTimeout = Duration.ofSeconds(1))
        val helper = TestHelper(engine, scheduler, startSlowWorkflow())

        // The second run starts while the first is still running.
        helper.waitForCondition({ SlowActions.maxInFlight.get() == 2 }, maxAttempts = 200)
        assertEquals(2, SlowActions.executions.get())
    }

    private fun startSlowWorkflow(): String {
        val id = "slow-${System.nanoTime()}"
        engine.startWorkflow(
            RunRequest.builder()
                .workflowId(id)
                .workflowClass(SlowWorkflow::class.java)
                .workflowMethod("run")
                .requestContext(TestRequestContext.builder().build())
                .extraRequestData(ExtraRequestData())
                .runAsync(true)
                .build(),
        )
        return id
    }

    open class SlowWorkflow : Workflow() {
        private val actions = actions(SlowActions::class.java)

        @WorkflowMethod
        open fun run(): CompletableFuture<String> {
            actions.slowStep()
            return CompletableFuture.completedFuture("done")
        }
    }

    open class SlowActions : Actions() {
        @Execute
        open fun slowStep(): String {
            executions.incrementAndGet()
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            try {
                check(release.await(30, TimeUnit.SECONDS)) { "test never released the slow action" }
            } finally {
                inFlight.decrementAndGet()
            }
            return "ok"
        }

        companion object {
            val executions = AtomicInteger()
            val inFlight = AtomicInteger()
            val maxInFlight = AtomicInteger()

            @Volatile var release = CountDownLatch(1)
        }
    }

    private companion object {
        val LEASE: Duration = Duration.ofSeconds(2)
    }
}
