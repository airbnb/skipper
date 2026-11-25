package com.airbnb.skipper.internal.scheduler

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A scheduler run of one task taken from [queue], which gives the task's slot back to the queue
 * exactly once.
 *
 * Two places can give the slot back, and one flag decides which of them does:
 * 1. The body started. It gives the slot back when it exits, however it exits. Cancelling the run
 *    only interrupts the body, it doesn't stop it, so a cancel never frees the slot of a body that
 *    is still working (for example one that is about to start the handler).
 * 2. The body never started (the run was cancelled first). [done], which Java calls exactly once
 *    when the run ends, gives the slot back, and the body is skipped if it is reached afterwards.
 *
 * The body is handed a function to report the handler's future. When the body exits:
 * 1. No future was reported (it threw or returned before reaching the handler): the slot is given
 *    back straight away.
 * 2. A future was reported: the slot is given back when that future completes, so a handler that
 *    outlives the scheduler's wait (a timeout, an interrupt) keeps its slot until it really finishes.
 */
internal class AdmittedRun private constructor(
    private val queue: SchedulerExecutionQueue,
    private val releaseClaimed: AtomicBoolean,
    body: (holdSlotUntil: (CompletableFuture<*>) -> Unit) -> Unit,
) : FutureTask<Unit>(
        Callable {
            if (releaseClaimed.compareAndSet(false, true)) {
                var handled: CompletableFuture<*> = FINISHED
                try {
                    body { handled = it }
                } finally {
                    handled.whenComplete { _, _ -> queue.markFinished() }
                }
            }
        },
    ) {
    constructor(
        queue: SchedulerExecutionQueue,
        body: (holdSlotUntil: (CompletableFuture<*>) -> Unit) -> Unit,
    ) : this(queue, AtomicBoolean(), body)

    override fun done() {
        if (releaseClaimed.compareAndSet(false, true)) {
            queue.markFinished()
        }
    }

    private companion object {
        val FINISHED: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
    }
}
