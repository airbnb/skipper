package com.airbnb.skipper.testutils.trace

import com.airbnb.skipper.internal.cluster.BucketRange
import com.airbnb.skipper.internal.scheduler.ScheduleRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.testutils.trace.TraceRecorder.Touched
import io.vavr.collection.List
import io.vavr.control.Option
import java.time.Instant

/** A [Scheduler] that records every call that writes a task row; reads pass straight through. */
internal class TracingScheduler(
    private val delegate: Scheduler,
    private val recorder: TraceRecorder,
) : Scheduler {
    override fun <T> schedule(request: ScheduleRequest<T>): Task<T> =
        recorder.record(
            "schedule",
            mapOf(
                "type" to request.type,
                "runAfter" to request.runAfter?.toEpochMilli(),
                "inMemory" to request.inMemoryExecutionEnabled,
                "honorLease" to request.isHonorActiveLeaseWhenOverwriting,
                "bumpVersion" to request.isBumpVersionWhenHonoringLease,
            ),
            { listOf(touched(request.type, request.id)) },
        ) { delegate.schedule(request) }

    override fun <T> fetch(limit: Int): List<Task<T>> = recordFetch { delegate.fetch(limit) }

    // Java callers pass a null partition for "no filtering" (Scheduler.fetch's contract).
    override fun <T> fetch(
        limit: Int,
        partition: BucketRange?,
    ): List<Task<T>> = recordFetch { delegate.fetch(limit, partition) }

    /** One line per leased task, so a batch reads as the separate leases the model takes. */
    private fun <T> recordFetch(call: () -> List<Task<T>>): List<Task<T>> =
        recorder.record(
            "fetch",
            emptyMap(),
            { tasks -> tasks?.toJavaList()?.map { touched(it.type, it.id) } ?: emptyList() },
            call,
        )

    override fun <T> remove(task: Task<T>) = recordTaskWrite("remove", task, emptyMap()) { delegate.remove(task) }

    override fun <T> markAsFailed(
        task: Task<T>,
        statusMessage: String,
    ) = recordTaskWrite("markAsFailed", task, emptyMap()) { delegate.markAsFailed(task, statusMessage) }

    override fun <T> rescheduleForRetry(
        task: Task<T>,
        runAfter: Instant,
    ) = recordTaskWrite("rescheduleForRetry", task, mapOf("runAfter" to runAfter.toEpochMilli())) {
        delegate.rescheduleForRetry(task, runAfter)
    }

    override fun <T> renewLease(task: Task<T>): Task<T> = recordTaskWrite("renewLease", task, emptyMap()) { delegate.renewLease(task) }

    override fun requeueFailedTask(taskId: String) {
        val task = delegate.getTask<Any>(taskId).orNull
        recorder.record(
            "requeueFailedTask",
            emptyMap(),
            { if (task == null) emptyList() else listOf(touched(task.type, taskId)) },
        ) { delegate.requeueFailedTask(taskId) }
    }

    /** A versioned write: the version the caller holds is what decides whether it lands. */
    private fun <T, R> recordTaskWrite(
        op: String,
        task: Task<T>,
        args: Map<String, Any?>,
        call: () -> R,
    ): R =
        recorder.record(
            op,
            args + mapOf("heldVersion" to task.version, "heldRunAfter" to task.runAfter.toEpochMilli()),
            { listOf(touched(task.type, task.id)) },
            call,
        )

    override fun <T> getTask(taskId: String): Option<Task<T>> = delegate.getTask(taskId)

    override fun <T> getFailedTasks(): List<Task<T>> = delegate.getFailedTasks()

    override fun countBacklog(): Long = delegate.countBacklog()

    override fun realSize(): Long = delegate.realSize()

    companion object {
        /** The workflow a task belongs to, from the id conventions the engine uses per task type. */
        fun touched(
            type: Task.Type,
            taskId: String,
        ): Touched {
            val workflowId =
                when (type) {
                    Task.Type.WORKFLOW -> taskId
                    Task.Type.TIMER -> taskId.substringBeforeLast(':')
                    Task.Type.EXECUTION_TIMEOUT -> taskId.removeSuffix(":timeout")
                    Task.Type.COMPENSATION -> taskId.removeSuffix("-compensation")
                }
            return Touched(workflowId, taskId)
        }
    }
}
