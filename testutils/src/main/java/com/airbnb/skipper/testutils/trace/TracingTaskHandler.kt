package com.airbnb.skipper.testutils.trace

import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.scheduler.TaskHandler
import io.vavr.control.Option
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

/**
 * A [TaskHandler] that records when the scheduler calls it, when it hands back its future, and
 * when that future completes.
 *
 * The scheduler gets a future derived from the handler's, which completes right after the
 * completion is recorded, with the same outcome. So the line for a handler finishing always comes
 * before the line for its slot being given back.
 */
internal class TracingTaskHandler(
    private val delegate: TaskHandler,
    private val recorder: SchedulerTraceRecorder,
) : TaskHandler {
    override fun handle(
        task: Task<*>,
        executorService: ExecutorService,
    ): CompletableFuture<Option<Instant>> {
        recorder.event("start", task.id)
        val handled = delegate.handle(task, executorService)
        recorder.event("report", task.id)
        return handled.whenComplete { _, _ -> recorder.event("done", task.id) }
    }
}
