package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.internal.SkipperSchedulerManager
import java.time.Clock
import java.time.Duration

/**
 * How a runtime builds the parts of its scheduler that a test harness may want to observe: the
 * in-memory queue, which counts the tasks the process has admitted, and the task handlers.
 *
 * The default, [None], builds them as they are. The test harness substitutes a recording queue and
 * recording handlers to check real runs against the scheduler model (formal/tla/trace). Nothing in
 * the engine calls back into an implementation while it runs: it only decides what gets built.
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

    /** Called once with the scheduler manager the runtime built around that queue and those handlers. */
    fun schedulerManager(manager: SkipperSchedulerManager) {}

    object None : SchedulerInstrumentation
}
