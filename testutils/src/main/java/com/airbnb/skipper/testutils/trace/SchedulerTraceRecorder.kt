package com.airbnb.skipper.testutils.trace

import com.airbnb.skipper.internal.SkipperSchedulerManager
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.SchedulerExecutionQueue
import com.airbnb.skipper.internal.scheduler.SchedulerInstrumentation
import com.airbnb.skipper.internal.scheduler.TaskHandler
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Writes what one runtime's scheduler does in memory as JSON lines, for trace validation against
 * the scheduler model (formal/tla/SkipperScheduler.tla; see formal/tla/trace/README.md).
 *
 * The events are the model's steps for one instance: a fetch coming back, a task added to the
 * in-memory queue, taken from it, its handler called, the handler's future reported, the handler
 * finishing, and a slot given back. Each line carries the queue's admitted count after the step.
 *
 * Every step that changes the count runs under one lock together with its line, so the order of
 * the lines is the order the steps happened and each count is exact.
 *
 * Nothing reaches the file until the runtime builds its scheduler manager on the traced queue and
 * handlers. A test that drives a manager of its own, with handlers the recorder never sees, leaves
 * no trace: its lines are held in memory, up to [MAX_HELD], and dropped.
 */
internal class SchedulerTraceRecorder(
    private val file: Path,
) : SchedulerInstrumentation {
    /** The test frames at install time, for when the first event comes from an engine thread. */
    private val installedFrom: List<String> = TraceRecorder.origin()
    private val lock = ReentrantLock()
    private var seq = 0L
    private var writer: BufferedWriter? = null
    private var queue: SchedulerExecutionQueue? = null

    /** Lines recorded before the runtime's scheduler manager exists; null once they are written or dropped. */
    private var held: MutableList<Map<String, Any?>>? = ArrayList()
    private var dropped = false

    @Volatile private var manager: SkipperSchedulerManager? = null

    override fun executionQueue(
        scheduler: Scheduler,
        clock: Clock,
        leaseDuration: Duration,
    ): SchedulerExecutionQueue = TracingExecutionQueue(scheduler, clock, leaseDuration, this).also { queue = it }

    override fun taskHandler(handler: TaskHandler): TaskHandler = TracingTaskHandler(handler, this)

    /** The runtime built its scheduler manager on the traced queue and handlers: start writing. */
    override fun schedulerManager(manager: SkipperSchedulerManager): SkipperSchedulerManager {
        this.manager = manager
        lock.withLock {
            held?.forEach(::write)
            held = null
        }
        return manager
    }

    /** Runs [step] and records it as one line, under the lock. */
    fun <T> event(
        op: String,
        taskId: String? = null,
        fields: (T) -> Map<String, Any?> = { emptyMap() },
        step: () -> T,
    ): T =
        lock.withLock {
            val result = step()
            val line = linkedMapOf<String, Any?>("seq" to seq++, "op" to op, "thread" to Thread.currentThread().name)
            if (taskId != null) line["task"] = taskId
            line.putAll(fields(result))
            line["admitted"] = queue?.admittedCount
            record(line)
            result
        }

    fun event(
        op: String,
        taskId: String? = null,
        fields: Map<String, Any?> = emptyMap(),
    ) = event(op, taskId, { fields }) { }

    /** The concurrency limit and fetch size in force, read from the manager as its fetch loop reads them. */
    fun limits(): Map<String, Any?> {
        val manager = manager ?: return emptyMap()
        return mapOf(
            "limit" to maxOf(SkipperSchedulerManager.NO_LIMIT, manager.effectiveMaxConcurrentTasks()),
            "fetchSize" to manager.getMaxTasks(),
        )
    }

    private fun record(line: Map<String, Any?>) {
        val early = held
        when {
            dropped -> Unit
            early == null -> write(line)
            early.size < MAX_HELD -> early.add(line)
            else -> {
                held = null
                dropped = true
            }
        }
    }

    private fun write(line: Map<String, Any?>) {
        val out = writer ?: open()
        out.write(Json.encode(line))
        out.newLine()
        out.flush()
    }

    private fun open(): BufferedWriter {
        Files.createDirectories(file.parent)
        val out = Files.newBufferedWriter(file)
        writer = out
        out.write(Json.encode(linkedMapOf("op" to "header", "origin" to TraceRecorder.origin().ifEmpty { installedFrom })))
        out.newLine()
        return out
    }

    private companion object {
        const val MAX_HELD = 10_000
    }
}
