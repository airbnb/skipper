package com.airbnb.skipper.internal.scheduler

import java.time.Clock
import java.time.Duration

/**
 * How a runtime builds the parts of its scheduler that Skipper's own test harness observes: the
 * in-memory queue, which counts the tasks the process has admitted, and the task handlers.
 *
 * Internal to Skipper and not a supported extension point. The scheduler's concurrency limit
 * relies on the queue counting every task until its handler's future completes; a queue or a
 * handler that behaves differently breaks it. The harness substitutes a recording queue and
 * recording handlers, which change nothing, to check real runs against the scheduler model
 * (formal/tla/trace). The default, [None], builds both as they are.
 */
interface SchedulerInstrumentation {
    /** Builds the in-memory execution queue. */
    fun executionQueue(
        scheduler: Scheduler,
        clock: Clock,
        leaseDuration: Duration,
    ): SchedulerExecutionQueue = SchedulerExecutionQueue(scheduler, clock, leaseDuration)

    /** Returns the handler the scheduler manager will call for its task type. */
    fun taskHandler(handler: TaskHandler): TaskHandler = handler

    object None : SchedulerInstrumentation
}
