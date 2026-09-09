package com.airbnb.skipper.integtest

import com.airbnb.skipper.Actions
import com.airbnb.skipper.ComponentFactory
import com.airbnb.skipper.Execute
import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.SignalMethod
import com.airbnb.skipper.StateField
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.SkipperSchedulerManager
import com.airbnb.skipper.internal.api.RunRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler
import com.airbnb.skipper.internal.storage.WorkflowStore
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.whenever

/**
 * Deterministic reproduction of the lost-signal race, and the regression guard for its fix.
 *
 * The window: the task handler persists the instance as WAITING, then removes its WORKFLOW task.
 * A signal that lands in between reschedules the task honouring the handler's still-active lease.
 * Before the fix the scheduler returned the leased row untouched, so the handler's versioned
 * `remove` succeeded and deleted the only row: instance stuck in RUNNING, signal state applied,
 * nothing left to run it, caller saw success.
 *
 * The window is frozen by wrapping the scheduler so `remove` of the WORKFLOW task blocks on a
 * latch; the test signals while it is blocked, then releases it. No sleeps, no timing luck.
 */
@Execution(ExecutionMode.SAME_THREAD)
class LostSignalOnHonoredLeaseTest {
    /** Blocks the handler's WORKFLOW-task `remove` until the test says go. Everything else passes through. */
    class PausingScheduler(
        private val delegate: Scheduler,
    ) : Scheduler by delegate {
        val removeReached = CountDownLatch(1)
        val proceed = CountDownLatch(1)

        override fun <T> remove(task: Task<T>) {
            if (task.type == Task.Type.WORKFLOW) {
                removeReached.countDown()
                check(proceed.await(30, TimeUnit.SECONDS)) { "test never released the paused remove" }
            }
            delegate.remove(task)
        }
    }

    open class ApprovalWorkflow : Workflow() {
        @StateField var approvedBy: String? = null

        @WorkflowMethod
        open fun run(): CompletableFuture<String> {
            waitUntil { approvedBy != null }
            return CompletableFuture.completedFuture("approved by $approvedBy")
        }

        @SignalMethod
        open fun approve(who: String) {
            approvedBy = who
        }
    }

    private lateinit var pausing: PausingScheduler
    private lateinit var engine: SkipperEngine
    private lateinit var store: WorkflowStore
    private lateinit var manager: SkipperSchedulerManager
    private val requestContext: Any = TestRequestContext.builder().build()

    private fun start(
        bumpVersionOnHonoredLease: Boolean,
        leaseRenewal: Boolean,
    ) {
        val deps = TestRuntime()
        deps.config.schedulerTaskLeaseDuration = Duration.ofSeconds(3) // short, so lease-expiry fallbacks are visible
        deps.config.scheduler =
            ComponentFactory { config ->
                PausingScheduler(SqliteScheduler.Factory().create(config)).also { pausing = it }
            }
        // Production default: a signal's wake-up goes through the persistent scheduler, not in-process.
        whenever(deps.featureGate.isEnabled(FeatureGate.Keys.FORCE_SIGNAL_WORKFLOW_EXEC_IN_SCHEDULER)).thenReturn(true)
        // Production default: leases are renewed while a task runs (renewal is version-conditioned).
        whenever(deps.featureGate.isEnabled(FeatureGate.Keys.AUTOMATIC_LEASE_RENEWAL)).thenReturn(leaseRenewal)
        whenever(deps.featureGate.isEnabled(FeatureGate.Keys.BUMP_TASK_VERSION_ON_HONORED_LEASE))
            .thenReturn(bumpVersionOnHonoredLease)
        val runtime = deps.runtime
        engine = runtime.skipperEngine.get()
        store = runtime.workflowStore.get()
        manager = runtime.skipperSchedulerManager.get()
        manager.start()
    }

    @AfterEach
    fun tearDown() {
        if (::pausing.isInitialized) pausing.proceed.countDown()
        if (::manager.isInitialized) manager.forceStop()
    }

    @ParameterizedTest(name = "bump task version on honoured lease = {0}")
    @ValueSource(booleans = [true, false])
    fun signalLandingBetweenWaitingWriteAndTaskRemoval(bumpEnabled: Boolean) {
        // Renewal stays off in the pre-fix case: a renewal racing the paused remove would leave the row
        // behind for reasons unrelated to the bug being documented.
        start(bumpEnabled, leaseRenewal = bumpEnabled)
        val id = "approval-${System.nanoTime()}"
        engine.startWorkflow(
            RunRequest.builder()
                .workflowId(id)
                .workflowClass(ApprovalWorkflow::class.java)
                .workflowMethod("run")
                .requestContext(requestContext)
                .extraRequestData(ExtraRequestData())
                .runAsync(true) // hand it to the scheduler so the task handler thread runs it
                .build(),
        )

        // The handler has persisted WAITING and is about to remove its task: the race window.
        assertTrue(pausing.removeReached.await(20, TimeUnit.SECONDS), "handler never reached remove()")
        assertEquals(WorkflowInstance.Status.WAITING, store.getWorkflow(id).get().status)

        // A signal lands in the window. It succeeds from the caller's point of view either way.
        engine.sendSignal(signal(id, ApprovalWorkflow::class.java, "approve", "reviewer"))
        assertEquals("reviewer", store.getWorkflow(id).get().state.get("approvedBy").get())

        pausing.proceed.countDown() // the handler now runs its remove

        val helper = TestHelper(engine, pausing, id)
        if (bumpEnabled) {
            // The bumped version makes the remove fail; the manager sees the lease is still its own and
            // reschedules the surviving row to run now, well inside the 3s lease.
            val started = System.nanoTime()
            val done = helper.waitForWorkflowToReachStatus(setOf(WorkflowInstance.Status.COMPLETED), maxAttempts = 200)
            assertTrue(
                Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3),
                "rerun should not wait for the lease to expire",
            )
            @Suppress("UNCHECKED_CAST")
            assertEquals("approved by reviewer", (done.result.get() as CompletableFuture<String>).get())
        } else {
            // Pre-fix behaviour, kept as documentation of the failure mode: the remove deletes the only
            // task, and the instance is left RUNNING with the approval in its state and nothing to run it.
            helper.waitForCondition { pausing.getTask<Any>(id).isEmpty }
            assertEquals(WorkflowInstance.Status.RUNNING, store.getWorkflow(id).get().status)
            assertFalse(pausing.getTask<Any>(id).isDefined, "no WORKFLOW task remains; the wake-up was lost")
        }
    }

    /**
     * The honour-lease branch is reached for the whole lease, not only the WAITING-to-remove window. A
     * signal during a long-running execution bumps the version while lease renewal is still using the
     * old one; renewal must recognise the lease as still its own rather than cancelling the run, so the
     * workflow never executes concurrently with itself. (A sequential re-execution afterwards is normal:
     * the signal makes the handler's final write stale, and actions are at-least-once.)
     */
    @Test
    fun signalDuringLongExecutionDoesNotCancelOrOverlapIt() {
        start(bumpVersionOnHonoredLease = true, leaseRenewal = true)
        pausing.proceed.countDown() // this test does not need the remove paused
        SlowActions.executions.set(0)
        SlowActions.inFlight.set(0)
        SlowActions.maxInFlight.set(0)
        SlowActions.release = CountDownLatch(1)
        val id = "slow-${System.nanoTime()}"
        engine.startWorkflow(
            RunRequest.builder()
                .workflowId(id)
                .workflowClass(SlowWorkflow::class.java)
                .workflowMethod("run")
                .requestContext(requestContext)
                .extraRequestData(ExtraRequestData())
                .runAsync(true)
                .build(),
        )
        val helper = TestHelper(engine, pausing, id)
        helper.waitForCondition { SlowActions.executions.get() == 1 } // the action is now blocked mid-run

        engine.sendSignal(signal(id, SlowWorkflow::class.java, "poke", "hello")) // bumps the leased row
        // Lease is 3s and the renewal grace period is longer, so renewal is attempted right away and
        // meets the bumped version; give it a couple of ticks (500ms each) to do so.
        Thread.sleep(1500)
        assertEquals(1, SlowActions.executions.get(), "the in-flight execution must not have been cancelled and re-run")
        assertEquals(1, SlowActions.inFlight.get(), "the original execution is still running")

        SlowActions.release.countDown()
        val done = helper.waitForWorkflowToReachStatus(setOf(WorkflowInstance.Status.COMPLETED), maxAttempts = 200)
        @Suppress("UNCHECKED_CAST")
        assertEquals("done", (done.result.get() as CompletableFuture<String>).get())
        assertEquals(1, SlowActions.maxInFlight.get(), "the workflow must never execute concurrently with itself")
        assertEquals("hello", store.getWorkflow(id).get().state.get("poked").get())
    }

    private fun signal(
        id: String,
        workflowClass: Class<out Workflow>,
        method: String,
        input: Any,
    ): RunRequest = RunRequest(id, workflowClass, method, input, requestContext, ExtraRequestData(), null, null, false)

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
