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
import java.util.concurrent.ConcurrentHashMap
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
        val deps = TestRuntime()
        deps.config.schedulerTaskLeaseDuration = LEASE
        // Renew well before the lease runs out, but not on every check.
        deps.config.leaseRenewalGracePeriod = Duration.ofSeconds(2)
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
        SlowActions.probes.values.forEach { it.release.countDown() }
        if (::manager.isInitialized) manager.forceStop()
    }

    @Test
    fun stepLongerThanItsLeaseRunsOnceWithinTheTimeout() {
        start(executionTimeout = Duration.ofSeconds(30))
        val id = startSlowWorkflow()
        val probe = SlowActions.probes.getValue(id)
        val helper = TestHelper(engine, scheduler, id)
        helper.waitForCondition { probe.executions.get() == 1 }

        // Wait until renewals push the lease expiry more than two leases past where it stood when the step
        // started. By then the step has outlived its original lease by a full lease.
        val firstExpiry = scheduler.getTask<Any>(id).get().runAfter
        helper.waitForCondition { scheduler.getTask<Any>(id).get().runAfter > firstExpiry.plus(LEASE.multipliedBy(2)) }
        assertEquals(1, probe.executions.get(), "the step must not run again while it is within the timeout")

        probe.release.countDown()
        helper.waitForWorkflowToReachStatus(setOf(WorkflowInstance.Status.COMPLETED), maxAttempts = 200)
        assertEquals(1, probe.executions.get())
        assertEquals(1, probe.maxInFlight.get())
    }

    @Test
    fun stepOutlastingTheTimeoutRunsAgain() {
        start(executionTimeout = Duration.ofSeconds(1))
        val id = startSlowWorkflow()
        val probe = SlowActions.probes.getValue(id)
        val helper = TestHelper(engine, scheduler, id)

        // The second run starts while the first is still running.
        helper.waitForCondition { probe.maxInFlight.get() == 2 }
        assertEquals(2, probe.executions.get())
    }

    private fun startSlowWorkflow(): String {
        val id = "slow-${System.nanoTime()}"
        SlowActions.probes[id] = SlowActions.Probe()
        engine.startWorkflow(
            RunRequest.builder()
                .workflowId(id)
                .workflowClass(SlowWorkflow::class.java)
                .workflowMethod("run")
                .input(id)
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
        open fun run(id: String): CompletableFuture<String> {
            actions.slowStep(id)
            return CompletableFuture.completedFuture("done")
        }
    }

    open class SlowActions : Actions() {
        @Execute
        open fun slowStep(id: String): String {
            val probe = probes.getValue(id)
            probe.executions.incrementAndGet()
            probe.maxInFlight.accumulateAndGet(probe.inFlight.incrementAndGet(), ::maxOf)
            try {
                check(probe.release.await(30, TimeUnit.SECONDS)) { "test never released the slow action" }
            } finally {
                probe.inFlight.decrementAndGet()
            }
            return "ok"
        }

        /** Counters for one workflow, so runs left over from an earlier test cannot touch them. */
        class Probe {
            val executions = AtomicInteger()
            val inFlight = AtomicInteger()
            val maxInFlight = AtomicInteger()
            val release = CountDownLatch(1)
        }

        companion object {
            val probes = ConcurrentHashMap<String, Probe>()
        }
    }

    private companion object {
        val LEASE: Duration = Duration.ofSeconds(3)
    }
}
