package com.airbnb.skipper.integtest

import com.airbnb.skipper.ComponentFactory
import com.airbnb.skipper.StateField
import com.airbnb.skipper.Timer
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.SkipperSchedulerManager
import com.airbnb.skipper.internal.api.RunRequest
import com.airbnb.skipper.internal.scheduler.ScheduleRequest
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
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Reproduction of the orphaned-timer bug, and the regression guard for its fix.
 *
 * Persisting a timer and scheduling its TIMER task are two writes: the timer row commits with the
 * WAITING instance, then the task is scheduled. If scheduling fails, the execution is retried. The
 * retry finds the timer already ACTIVE, so `waitUntil` creates nothing and the persist schedules
 * nothing. Before the fix the instance stayed WAITING forever: an ACTIVE timer, past due, with no
 * task to fire it.
 *
 * The failure is injected by wrapping the scheduler so the first TIMER `schedule` throws, which is
 * what a transient store outage between the two writes does in production.
 */
@Execution(ExecutionMode.SAME_THREAD)
class OrphanedTimerOnSchedulerFailureTest {
    /** Throws on the first TIMER `schedule`. Everything else passes through. */
    class FailFirstTimerScheduleScheduler(
        private val delegate: Scheduler,
    ) : Scheduler by delegate {
        val timerScheduleAttempts = AtomicInteger()

        override fun <T> schedule(request: ScheduleRequest<T>): Task<T> {
            if (request.type == Task.Type.TIMER && timerScheduleAttempts.incrementAndGet() == 1) {
                throw IllegalStateException("injected scheduler outage while scheduling ${request.id}")
            }
            return delegate.schedule(request)
        }
    }

    open class DeadlineWorkflow : Workflow() {
        @StateField var approved = false

        @WorkflowMethod
        open fun run(): CompletableFuture<String> {
            val approvedInTime = waitUntil({ approved }, TIMEOUT, TIMER_ID)
            return CompletableFuture.completedFuture(if (approvedInTime) "approved" else "timed out")
        }
    }

    private lateinit var scheduler: FailFirstTimerScheduleScheduler
    private lateinit var engine: SkipperEngine
    private lateinit var store: WorkflowStore
    private lateinit var manager: SkipperSchedulerManager

    @AfterEach
    fun tearDown() {
        if (::manager.isInitialized) manager.forceStop()
    }

    @Test
    fun timerTaskIsRearmedWhenSchedulingItFailsAfterTheTimerCommitted() {
        val deps = TestRuntime()
        deps.config.scheduler =
            ComponentFactory { config ->
                FailFirstTimerScheduleScheduler(SqliteScheduler.Factory().create(config)).also { scheduler = it }
            }
        val runtime = deps.runtime
        engine = runtime.skipperEngine.get()
        store = runtime.workflowStore.get()
        manager = runtime.skipperSchedulerManager.get()
        manager.start()

        val id = "deadline-${System.nanoTime()}"
        engine.startWorkflow(
            RunRequest.builder()
                .workflowId(id)
                .workflowClass(DeadlineWorkflow::class.java)
                .workflowMethod("run")
                .requestContext(TestRequestContext.builder().build())
                .extraRequestData(ExtraRequestData())
                .runAsync(true) // hand it to the scheduler so the task handler thread runs it
                .build(),
        )
        val helper = TestHelper(engine, scheduler, id)

        // First execution: the timer commits with the WAITING instance, then scheduling its task throws
        // and the execution is retried. Wait until that retry has run and dequeued the workflow task.
        helper.waitForCondition { scheduler.timerScheduleAttempts.get() >= 1 }
        helper.waitForWorkflowToReachStatus(setOf(WorkflowInstance.Status.WAITING))

        val timer = store.getTimers(id).single()
        assertEquals(Timer.Status.ACTIVE, timer.status)
        assertTrue(
            scheduler.getTask<Timer>(timer.getUniqueId()).isDefined,
            "timer ${timer.getUniqueId()} is ACTIVE but has no TIMER task: nothing will ever fire it",
        )

        // The re-armed task fires at the deadline and the workflow resumes down its timeout branch.
        val done = helper.waitForWorkflowToReachStatus(setOf(WorkflowInstance.Status.COMPLETED), maxAttempts = 100)
        @Suppress("UNCHECKED_CAST")
        assertEquals("timed out", (done.result.get() as CompletableFuture<String>).get())
        assertEquals(Timer.Status.EXPIRED, store.getTimers(id).single().status)
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(2)
        const val TIMER_ID = "approval-deadline"
    }
}
