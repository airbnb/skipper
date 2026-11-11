package com.airbnb.skipper.internal.scheduler

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger

/**
 * The admission of one task taken from a [SchedulerExecutionQueue], given back to the queue exactly
 * once, when the last part of the task that holds it lets go.
 *
 * The scheduler's run of the task holds it from the start. The run can also make it wait for the
 * handler's future with [holdUntil], so a handler that outlives the scheduler's wait (a timeout, an
 * interrupt) keeps its slot until it really finishes.
 */
internal class Admission(
    private val queue: SchedulerExecutionQueue,
) {
    private val holders = AtomicInteger(1)

    /**
     * Keeps the admission until [future] completes, as well as until every other holder lets go.
     *
     * Does nothing once the admission has been given back: the run was cancelled while it was
     * running, and its slot is already free.
     */
    fun holdUntil(future: CompletableFuture<*>) {
        val held = holders.getAndUpdate { if (it == 0) 0 else it + 1 } > 0
        if (held) {
            future.whenComplete { _, _ -> release() }
        }
    }

    /** Lets go of one hold; the last one gives the admission back to the queue. */
    fun release() {
        if (holders.decrementAndGet() == 0) {
            queue.markFinished()
        }
    }
}

/**
 * A scheduler run of an admitted task that lets go of its [admission] when it is done.
 *
 * [done] runs exactly once however the run ends: it finished, it threw, or it was cancelled, even
 * before it started. A `finally` inside [body] would miss that last case.
 */
internal class AdmittedRun(
    private val admission: Admission,
    body: Callable<Unit>,
) : FutureTask<Unit>(body) {
    override fun done() = admission.release()
}
