package com.airbnb.skipper.internal.scheduler

/** What [Scheduler.scheduleWithOutcome] did with a request. */
data class ScheduleOutcome<T>(
    /** The task row as it stands after the call. */
    val task: Task<T>,
    /**
     * The row was leased and the request honoured that lease
     * ([ScheduleRequest.isHonorActiveLeaseWhenOverwriting]): the row kept its `run_after` and status,
     * and at most its version moved ([ScheduleRequest.isBumpVersionWhenHonoringLease]). Whoever holds
     * that lease runs the task; the caller does not.
     */
    val leaseHonored: Boolean,
)
