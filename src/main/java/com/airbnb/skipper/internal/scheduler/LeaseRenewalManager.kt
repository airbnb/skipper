package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.Metrics
import com.airbnb.skipper.OptimisticLockingError
import com.airbnb.skipper.SkipperAnnotationNames.LEASE_RENEWAL_GRACE_PERIOD
import com.airbnb.skipper.SkipperAnnotationNames.UTC_CLOCK
import io.vavr.control.Option
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Comparator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Manages the leases for tasks that are in flight by renewing leases for tasks that are about to
 * expire. Regular lease acquisition and release is not done by this component.
 */
@Singleton
class LeaseRenewalManager
    @Inject
    constructor(
        private val scheduler: Scheduler,
        @Named(UTC_CLOCK) private val clock: Clock,
        private val metrics: Metrics,
        // The grace period for renewing leases before they expire.
        @Named(LEASE_RENEWAL_GRACE_PERIOD) private val leaseExpireGracePeriod: Duration,
    ) {
        @JvmField
        val tasksInFlight = ConcurrentHashMap<String, TaskInFlight>()

        /**
         * Get the task in flight with the given id.
         *
         * @param id the id of the task
         * @return the task in flight if it exists, otherwise an empty option
         */
        fun getTaskInFlight(id: String): Option<TaskInFlight> = Option.of(tasksInFlight[id])

        /**
         * Register a new task in flight. By doing so, the task will be considered for lease renewal.
         *
         * @param taskInFlight the task to register
         */
        fun addTaskInFlight(taskInFlight: TaskInFlight) {
            tasksInFlight[taskInFlight.task.id] = taskInFlight
        }

        /**
         * Remove a task from the list of tasks in flight. After this, the task will no longer be
         * considered for lease renewal.
         *
         * @param id the id of the task to remove
         */
        fun removeTaskInFlight(id: String) {
            tasksInFlight.remove(id)
        }

        /**
         * Attempt to renew the lease for the first expiring task. If the lease is successfully renewed,
         * the task is updated in the list of tasks in flight. If the lease renewal fails, the task is
         * ignored and the method returns an empty option.
         *
         * @return the renewed task if the lease was successfully renewed, otherwise an empty option
         */
        fun attemptToRenewOneTask(): Option<Task<*>> {
            val taskInFlightToRenew = getFirstExpiringTaskId()
            if (taskInFlightToRenew.isDefined) {
                val taskFuture: Future<*> = taskInFlightToRenew.get().handle
                if (taskFuture.isDone || taskFuture.isCancelled) {
                    // This should never happen, given that the caller is supposed to remove the task
                    // in flight when the task is done or cancelled. But just in case, we will check
                    // here.
                    log.warn(
                        "task {} is done or cancelled, removing it from the list of tasks in flight",
                        taskInFlightToRenew.get().task.id,
                    )
                    removeTaskInFlight(taskInFlightToRenew.get().task.id)
                    return Option.none()
                }
                val taskToRenew: Task<*> = taskInFlightToRenew.get().task
                try {
                    val renewedTask: Task<*> = scheduler.renewLease(taskToRenew)
                    tasksInFlight.computeIfPresent(renewedTask.id) { _, taskInFlight ->
                        TaskInFlight(taskInFlight.handle, renewedTask.runAfter, renewedTask, taskInFlight.rerunRequested)
                    }
                    metrics.counter(METRICS_COMPONENT, "renewLeaseSucceeded").inc()
                    return Option.of(renewedTask)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception
                ) {
                    if (e !is OptimisticLockingError &&
                        e !is IllegalArgumentException &&
                        e !is IllegalStateException
                    ) {
                        throw e
                    }
                    if (e is OptimisticLockingError) {
                        // The version moved, but did the lease? A rerun recorded on our leased row
                        // (ScheduleRequest.isBumpVersionWhenHonoringLease) bumps the version and leaves
                        // run_after alone; a competing fetch always moves run_after. If the lease is still
                        // ours, adopt the new version and keep renewing rather than cancelling the run.
                        val current: Option<Task<*>> = scheduler.getTask<Any>(taskToRenew.id).map { it as Task<*> }
                        if (current.isDefined && current.get().runAfter == taskToRenew.runAfter) {
                            tasksInFlight.computeIfPresent(taskToRenew.id) { _, taskInFlight ->
                                TaskInFlight(taskInFlight.handle, taskInFlight.leaseExpiration, current.get(), rerunRequested = true)
                            }
                            metrics.counter(METRICS_COMPONENT, "renewLeaseVersionAdopted").inc()
                            log.info(
                                "task {} was re-requested while leased; continuing to renew its lease",
                                taskToRenew.id,
                            )
                            return Option.none()
                        }
                    }
                    // Most certainly this means another thread already got a hold of the task.
                    // We should just ignore it and let the other thread handle it.
                    log.warn("failed to renew lease for task: {}", taskToRenew.id, e)
                    metrics.counter(METRICS_COMPONENT, "renewLeaseFailed").inc()
                    // We need to remove the task from the list otherwise it will keep failing
                    // indefinitely.
                    removeTaskInFlight(taskToRenew.id)
                    taskFuture.cancel(true)
                }
            }
            return Option.none()
        }

        private fun getFirstExpiringTaskId(): Option<TaskInFlight> {
            val aboutToExpire = clock.instant().plus(leaseExpireGracePeriod)
            val taskInFlight =
                tasksInFlight.entries.stream()
                    .filter { it.value.leaseExpiration.isBefore(aboutToExpire) }
                    .sorted(
                        java.util.Map.Entry.comparingByValue(
                            Comparator.comparing(TaskInFlight::leaseExpiration),
                        ),
                    )
                    .map { it.value }
                    .findFirst()
            return Option.ofOptional(taskInFlight)
        }

        val totalTasksInFlight: Int
            get() = tasksInFlight.size

        class TaskInFlight
            @JvmOverloads
            constructor(
                val handle: Future<*>,
                val leaseExpiration: Instant,
                val task: Task<*>,
                /**
                 * Set once a rerun request was observed on this task's row while we held its lease (see
                 * `ScheduleRequest.isBumpVersionWhenHonoringLease`). A later successful renewal writes
                 * our version back over the row, erasing that mark from the database, so the owner keeps
                 * it here and reschedules instead of removing when the task finishes.
                 */
                val rerunRequested: Boolean = false,
            ) {
                override fun equals(other: Any?): Boolean {
                    if (other === this) return true
                    if (other !is TaskInFlight) return false
                    if (handle != other.handle) return false
                    if (leaseExpiration != other.leaseExpiration) return false
                    if (task != other.task) return false
                    return true
                }

                override fun hashCode(): Int {
                    val prime = 59
                    var result = 1
                    result = result * prime + handle.hashCode()
                    result = result * prime + leaseExpiration.hashCode()
                    result = result * prime + task.hashCode()
                    return result
                }

                override fun toString(): String =
                    "LeaseRenewalManager.TaskInFlight(handle=$handle" +
                        ", leaseExpiration=$leaseExpiration, task=$task)"
            }

        companion object {
            private val log = LoggerFactory.getLogger(LeaseRenewalManager::class.java)
            private const val METRICS_COMPONENT = "leaseManager"
        }
    }
