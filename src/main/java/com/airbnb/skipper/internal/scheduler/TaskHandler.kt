package com.airbnb.skipper.internal.scheduler

import io.vavr.control.Option
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

interface TaskHandler {
    /**
     * Handle the processing of a task.
     *
     * This method should not throw any exceptions. If an exception is thrown, the task will be
     * considered as failed and it **will not be retried**.
     *
     * @param task The task to be processed.
     * @return A future that will complete whenever the task has completed processing. When the
     *     future is completed with an empty `Option`, the task will be considered as successfully
     *     processed and can be safely removed from the queue. When the future is completed with a
     *     non-empty `Option`, it means the task has failed and the task should be rescheduled to be
     *     processed at that instant.
     */
    fun handle(
        task: Task<*>,
        executorService: ExecutorService
    ): CompletableFuture<Option<Instant>>
}
