package com.airbnb.skipper.integtest

import com.airbnb.skipper.Actions
import com.airbnb.skipper.Execute
import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.ResultUnavailable
import com.airbnb.skipper.SignalMethod
import com.airbnb.skipper.StateField
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.SkipperSchedulerManager
import com.airbnb.skipper.internal.api.RunRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.testutils.TestHelper
import com.airbnb.skipper.testutils.TestRequestContext
import com.airbnb.skipper.testutils.TestRuntime
import com.airbnb.skipper.util.ExtraRequestData
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.mockito.kotlin.whenever

/**
 * An in-process signal reaches a workflow whose in-process run is still going. The signal's
 * in-memory schedule finds the run's backstop row leased, so it must leave the task to that run: a
 * copy queued for the signal would be taken by another consumer thread and run the workflow twice
 * at once. The version bump the signal records on the row makes the run's final write fail, and
 * the run is then rescheduled, so the signal is still applied.
 */
@Execution(ExecutionMode.SAME_THREAD)
class InMemoryScheduleOnLeasedTaskTest {
    private lateinit var engine: SkipperEngine
    private lateinit var store: WorkflowStore
    private lateinit var scheduler: Scheduler
    private lateinit var manager: SkipperSchedulerManager
    private val requestContext: Any = TestRequestContext.builder().build()

    @BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        // Longer than the test and the renewal grace period, so no lease lapses or is renewed meanwhile.
        deps.config.schedulerTaskLeaseDuration = Duration.ofSeconds(30)
        // Signals take the in-process path, like startWorkflow without runAsync.
        whenever(deps.featureGate.isEnabled(FeatureGate.Keys.FORCE_SIGNAL_WORKFLOW_EXEC_IN_SCHEDULER)).thenReturn(false)
        whenever(deps.featureGate.isEnabled(FeatureGate.Keys.AUTOMATIC_LEASE_RENEWAL)).thenReturn(true)
        whenever(deps.featureGate.isEnabled(FeatureGate.Keys.BUMP_TASK_VERSION_ON_HONORED_LEASE)).thenReturn(true)
        val runtime = deps.runtime
        engine = runtime.skipperEngine.get()
        store = runtime.workflowStore.get()
        scheduler = runtime.scheduler.get()
        manager = runtime.skipperSchedulerManager.get()
        SlowActions.executions.set(0)
        SlowActions.inFlight.set(0)
        SlowActions.maxInFlight.set(0)
        SlowActions.release = CountDownLatch(1)
        manager.start()
    }

    @AfterEach
    fun tearDown() {
        SlowActions.release.countDown()
        if (::manager.isInitialized) manager.forceStop()
    }

    @Test
    fun inProcessSignalDuringAnInProcessRunQueuesNoSecondCopy() {
        val id = "slow-${System.nanoTime()}"
        engine.startWorkflow(
            RunRequest.builder()
                .workflowId(id)
                .workflowClass(SlowWorkflow::class.java)
                .workflowMethod("run")
                .requestContext(requestContext)
                .extraRequestData(ExtraRequestData())
                .build(), // not runAsync: the run is an in-memory copy
        )
        val helper = TestHelper(engine, scheduler, id)
        helper.waitForCondition { SlowActions.executions.get() == 1 } // the run is now blocked in its action

        val signalled = engine.sendSignal(
            RunRequest(id, SlowWorkflow::class.java, "poke", "hello", requestContext, ExtraRequestData(), null, null, false)
        )
        // A copy queued for the signal would be taken by an idle consumer at once and enter the action.
        Thread.sleep(1500)
        assertEquals(1, SlowActions.executions.get(), "the signal must not start a second run next to the first")
        val error = assertThrows(CompletionException::class.java) { signalled.workflowInstance.result.join() }
        assertTrue(error.cause is ResultUnavailable, "no copy runs for the signal in this process")

        SlowActions.release.countDown()
        val done = helper.waitForWorkflowToReachStatus(setOf(WorkflowInstance.Status.COMPLETED), maxAttempts = 200)
        @Suppress("UNCHECKED_CAST")
        assertEquals("done", (done.result.get() as CompletableFuture<String>).get())
        assertEquals(1, SlowActions.maxInFlight.get(), "the workflow must never execute concurrently with itself")
        assertEquals("hello", store.getWorkflow(id).get().state.get("poked").get())
    }

    open class SlowWorkflow : Workflow() {
        private val actions = actions(SlowActions::class.java)

        @StateField var poked: String? = null

        @WorkflowMethod
        open fun run(): CompletableFuture<String> {
            actions.slowStep()
            return CompletableFuture.completedFuture("done")
        }

        @SignalMethod
        open fun poke(value: String) {
            poked = value
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
}
