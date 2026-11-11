package com.airbnb.skipper.testutils.trace

import com.airbnb.skipper.internal.scheduler.ScheduleRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.SchedulerExecutionQueue
import com.airbnb.skipper.internal.scheduler.Task
import java.time.Clock
import java.time.Duration

/**
 * A [SchedulerExecutionQueue] that records every task added, taken and finished. It changes
 * nothing about how the queue behaves; each step runs as it would, under the recorder's lock.
 */
// Open because tests spy on the queue they get from the harness.
internal open class TracingExecutionQueue(
    scheduler: Scheduler,
    clock: Clock,
    leaseDuration: Duration,
    private val recorder: SchedulerTraceRecorder,
) : SchedulerExecutionQueue(scheduler, clock, leaseDuration) {
    /** Set while this thread is inside [schedule], whose add is an in-memory execution. */
    private val scheduling = ThreadLocal.withInitial { false }

    override fun <T> schedule(request: ScheduleRequest<T>): Task<T> {
        scheduling.set(true)
        try {
            return super.schedule(request)
        } finally {
            scheduling.set(false)
        }
    }

    override fun add(task: Task<*>): Boolean {
        // Anything else adding to the queue is a test doing the fetch loop's job by hand.
        val origin =
            when {
                scheduling.get() -> "inmem"
                TraceRecorder.calledFromClass(FETCH_LOOP) -> "fetched"
                else -> "direct"
            }
        return recorder.event("add", task.id, { added -> mapOf("origin" to origin, "added" to added) }) { super.add(task) }
    }

    override fun <T> take(type: Task.Type): Task<T> {
        // Blocks until a task arrives, so it is recorded once it has one. The task cannot run before
        // this returns it, so the line still lands before any line of its run.
        val task = super.take<T>(type)
        recorder.event("take", task.id)
        return task
    }

    override fun markFinished() = recorder.event("release") { super.markFinished() }

    private companion object {
        const val FETCH_LOOP = "SkipperSchedulerManager"
    }
}
