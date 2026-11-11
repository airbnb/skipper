package com.airbnb.skipper.testutils.trace

import com.airbnb.skipper.internal.cluster.BucketRange
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.Task
import io.vavr.collection.List

/**
 * A [Scheduler] that records each fetch the scheduler manager's fetch loop makes: how many tasks
 * it asked for, which ones it got, and the limit and fetch size in force. Everything else, and
 * fetches made by anything but the fetch loop, passes straight through.
 */
internal class FetchTracingScheduler(
    private val delegate: Scheduler,
    private val recorder: SchedulerTraceRecorder,
) : Scheduler by delegate {
    override fun <T> fetch(limit: Int): List<Task<T>> = record(limit) { delegate.fetch(limit) }

    override fun <T> fetch(
        limit: Int,
        partition: BucketRange?,
    ): List<Task<T>> = record(limit) { delegate.fetch(limit, partition) }

    private fun <T> record(
        requested: Int,
        fetch: () -> List<Task<T>>,
    ): List<Task<T>> {
        if (!TraceRecorder.calledFromClass(FETCH_LOOP)) {
            return fetch()
        }
        // Read before fetching, as the loop did just before it called.
        val limits = recorder.limits()
        val tasks = fetch()
        recorder.event("fetch", fields = limits + mapOf("requested" to requested, "tasks" to tasks.toJavaList().map { it.id }))
        return tasks
    }

    private companion object {
        const val FETCH_LOOP = "SkipperSchedulerManager"
    }
}
