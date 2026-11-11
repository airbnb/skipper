package com.airbnb.skipper.internal.scheduler

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference

/**
 * A scheduler run of one task taken from [queue], which gives the task's slot back to the queue
 * exactly once.
 *
 * The slot is given back in one place, [done], which Java calls exactly once however the run ends:
 * it finished, it threw, or it was cancelled, even before it started. A `finally` inside the body
 * would miss that last case.
 *
 * The body is handed a function to report the handler's future. What [done] then does:
 * 1. No future was reported (the run never reached the handler, or was cancelled before it did):
 *    the slot is given back straight away.
 * 2. A future was reported: the slot is given back when that future completes, so a handler that
 *    outlives the scheduler's wait (a timeout, an interrupt) keeps its slot until it really finishes.
 */
internal class AdmittedRun private constructor(
    private val queue: SchedulerExecutionQueue,
    private val handled: AtomicReference<CompletableFuture<*>?>,
    body: (holdSlotUntil: (CompletableFuture<*>) -> Unit) -> Unit,
) : FutureTask<Unit>(Callable { body(handled::set) }) {
    constructor(
        queue: SchedulerExecutionQueue,
        body: (holdSlotUntil: (CompletableFuture<*>) -> Unit) -> Unit,
    ) : this(queue, AtomicReference(), body)

    override fun done() {
        (handled.get() ?: FINISHED).whenComplete { _, _ -> queue.markFinished() }
    }

    private companion object {
        val FINISHED: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
    }
}
