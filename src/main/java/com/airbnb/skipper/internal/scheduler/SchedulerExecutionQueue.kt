package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.SkipperAnnotationNames.SCHEDULER_LEASE_DURATION
import com.airbnb.skipper.SkipperAnnotationNames.UTC_CLOCK
import com.airbnb.skipper.internal.common.UniqueBlockingQueue
import com.google.common.annotations.VisibleForTesting
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * A series of blocking queues that hold tasks to be executed by the scheduler.
 *
 * This queue enables tasks to be executed immediately without going through the persistence layer,
 * which is very useful for short-lived tasks that might contain Futures that the caller is waiting
 * on.
 */
@Singleton
open class SchedulerExecutionQueue
    @Inject
    constructor(
        private val persistentScheduler: Scheduler,
        @Named(UTC_CLOCK) private val now: Clock,
        /**
         * This represents the time that the task will be leased for before being re-delivered if not
         * removed from the scheduler.
         */
        @Named(SCHEDULER_LEASE_DURATION)
        @JvmField
        val leaseDuration: Duration,
    ) {
        private val queues: MutableMap<Task.Type, UniqueBlockingQueue<Task<*>>> = HashMap()

        init {
            for (type in Task.Type.values()) {
                log.info("creating queue for type: '{}'", type)
                queues.putIfAbsent(type, UniqueBlockingQueue())
            }
        }

        @VisibleForTesting
        open fun getQueue(type: Task.Type): UniqueBlockingQueue<Task<*>> = queues[type] ?: throw IllegalArgumentException("Unknown task type: $type")

        /**
         * Schedule a task for execution.
         *
         * If the request.runAfter is in the past, which means the task should be executed immediately,
         * the task will be added to the execution queue, which will cause it to be executed as soon as
         * possible. This task will also be scheduled in the persistent scheduler to be delivered in the
         * future in case the task execution fails, and in order to comply with the at-least-once
         * delivery guarantee. If the task is to be executed in the future, the behavior will be no
         * different from a regular persistent scheduler.
         *
         * Being able to process the task immediately without going through the persistence layer brings
         * important benefits like being able to wait on Future contained in the task payload, which
         * would be impossible through a regular scheduler.
         *
         * @param request The request to schedule a task.
         * @return The scheduled task.
         */
        open fun <T> schedule(request: ScheduleRequest<T>): Task<T> {
            val shouldExecuteInTheFuture =
                request.runAfter != null && request.runAfter.isAfter(now.instant())
            if (!request.inMemoryExecutionEnabled || shouldExecuteInTheFuture) {
                // If the task is to be executed in the future or the request specifically wants to
                // avoid in-memory queue, no need to go through the in-memory queue.
                log.info(
                    "task scheduled for the future or in-memory execution is disabled or execution " +
                        "is in the future, not adding to the in-memory queue. taskId={}. runAfter={}",
                    request.id,
                    request.runAfter,
                )
                return persistentScheduler.schedule(
                    // We are going to clear the payload to avoid serialization issues and because the
                    // handler can dereference the payload by the id and hydrate it.
                    // The payload will have to anyways be refreshed by the handler because it could
                    // be stale.
                    request.toBuilder().payload(null).build(),
                )
            }
            var newTask: Task<T> =
                // This task will go directly to the blocking queue, so let's not have the handler
                // refresh the payload right away since it's already up-to-date. Only refresh when
                // the item has been in the persistent queue for a while and hence it is possible that
                // the payload is stale.
                Task.builder<T>()
                    .id(request.id)
                    .executionTimeout(Duration.ofMinutes(5))
                    .runAfter(Instant.EPOCH)
                    .createdAt(now.instant())
                    .dedupToken(request.dedupToken)
                    .payload(request.payload)
                    .type(request.type)
                    .retryCount(0)
                    .status(Task.Status.PENDING)
                    .shouldRefreshPayload(false)
                    .build()
            if (!getQueue(newTask.type).contains(newTask.id)) {
                val persistedTask: Task<T> =
                    persistentScheduler.schedule(
                        // We are going to clear the payload to avoid serialization issues and because
                        // the handler can dereference the payload by the id and hydrate it.
                        // The payload will have to anyways be refreshed by the handler because it could
                        // be stale.
                        request.toBuilder()
                            .runAfter(now.instant().plus(leaseDuration))
                            .payload(null)
                            .build(),
                    )
                // We need the task to contain the version so that updates or removals down the line
                // don't fail with optimistic locking errors.
                newTask = // for lease renewal purposes
                    newTask.toBuilder()
                        .version(persistedTask.version)
                        .runAfter(persistedTask.runAfter)
                        .build()
                add(newTask)
            }
            return newTask
        }

        open fun add(task: Task<*>): Boolean = getQueue(task.type).add(task, task.id)

        open fun <T> take(type: Task.Type): Task<T> {
            @Suppress("UNCHECKED_CAST")
            return getQueue(type).take() as Task<T>
        }

        open val queueSizes: Map<Task.Type, Int>
            get() {
                val sizes = HashMap<Task.Type, Int>()
                for (type in Task.Type.values()) {
                    sizes[type] = getQueue(type).size()
                }
                return sizes
            }

        companion object {
            private val log = LoggerFactory.getLogger(SchedulerExecutionQueue::class.java)
        }
    }
