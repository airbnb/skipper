package com.airbnb.skipper.internal

import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.Knobs
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.OptimisticLockingError
import com.airbnb.skipper.SkipperAnnotationNames.GRACEFUL_SHUTDOWN_TIMEOUT
import com.airbnb.skipper.SkipperAnnotationNames.SCHEDULER_TASK_HANDLER_POOL
import com.airbnb.skipper.SkipperAnnotationNames.SCHEDULER_TASK_MAX_RETRIES
import com.airbnb.skipper.SkipperAnnotationNames.SKIPPER_MAIN_THREAD_POOL
import com.airbnb.skipper.internal.cluster.BucketPartitioner
import com.airbnb.skipper.internal.cluster.BucketRange
import com.airbnb.skipper.internal.cluster.ClusterMembershipManager
import com.airbnb.skipper.internal.common.SneakyThrow
import com.airbnb.skipper.internal.scheduler.LeaseRenewalManager
import com.airbnb.skipper.internal.scheduler.ScheduleRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.SchedulerExecutionQueue
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.scheduler.TaskHandler
import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ImmutableMap
import io.vavr.collection.List
import io.vavr.collection.Map
import io.vavr.control.Option
import java.time.Duration
import java.time.Instant
import java.util.HashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * The SkipperSchedulerManager is responsible for fetching tasks from the scheduler and processing
 * them by delegating to the appropriate task handler.
 *
 * It exposes plain [start] and [stop] lifecycle methods (deliberately framework-agnostic so the
 * engine has no dependency on a specific application/DI framework). Hosts running Dropwizard can
 * register `com.airbnb.skipper.common.ManagedSkipperSchedulerManager`, a thin
 * `io.dropwizard.lifecycle.Managed` adapter that delegates to these methods.
 *
 * Since all of the workflow instance executions come through the scheduler, this component
 * effectively the root of the execution tree for both workflows and actions, therefore it owns the
 * distribution and handling of the underlying execution threads.
 */
@Singleton
class SkipperSchedulerManager
    @Inject
    constructor(
        private val schedulerQueue: SchedulerExecutionQueue,
        private val scheduler: Scheduler,
        @param:Named(SKIPPER_MAIN_THREAD_POOL) private val executor: ExecutorService,
        @param:Named(SCHEDULER_TASK_HANDLER_POOL) private val taskHandlerExecutor: ExecutorService,
        private val taskHandlers: Map<Task.Type, TaskHandler>,
        private val metrics: Metrics,
        @param:Named(SCHEDULER_TASK_MAX_RETRIES) private val maxRetries: Int,
        private val leaseManager: LeaseRenewalManager,
        private val featureGate: FeatureGate,
        private val knobs: Knobs,
        private val clusterMembershipManager: ClusterMembershipManager,
        private val partitioner: BucketPartitioner,
        @param:Named(GRACEFUL_SHUTDOWN_TIMEOUT) private val gracefulShutdownTimeout: Duration,
    ) {
        private val stop = AtomicBoolean(false)

        /** Futures of the long-running loops started by [start]; cancelled by [stop] to unblock them. */
        private val loopFutures = CopyOnWriteArrayList<Future<*>>()
        private val started = AtomicBoolean(false)

        // Widened from package-private to a public @JvmField so the same-package Java test
        // (SkipperSchedulerManagerTest) can read/set it by name; not consumed externally. Additive ABI.
        @JvmField val schedulerBacklogSize = AtomicLong(0)

        /** Starts fetching for tasks from the scheduler that are ready to be processed. */
        @Synchronized
        fun start() {
            if (started.get()) {
                log.warn("skipper scheduler manager already started")
                return
            }
            for (type in List.of(*Task.Type.values())) {
                loopFutures.add(executor.submit { startTakingTasks(type) })
            }
            loopFutures.add(executor.submit { startFetchingTasks() })
            loopFutures.add(executor.submit { renewExpiringLeases() })
            clusterMembershipManager.start()
            registerQueueMonitoring()
            started.set(true)
        }

        /**
         * Starts taking tasks from the scheduler queue and processing them by delegating to the
         * appropriate task handler. This is the scheduler queue consumer thread.
         */
        private fun startTakingTasks(type: Task.Type) {
            log.info("starting skipper scheduler manager.startTakingTasks for task type {}", type)
            while (!stop.get() && !Thread.currentThread().isInterrupted) {
                try {
                    val task: Task<Any> = schedulerQueue.take(type)
                    log.debug("pulled task from queue: {}", task.id)
                    metrics.counter(METRICS_COMPONENT, "takenTasks").inc()
                    val taskFuture: Future<*> =
                        executor.submit {
                            try {
                                handleTask(task)
                            } finally {
                                leaseManager.removeTaskInFlight(task.id)
                            }
                        }
                    if (featureGate.isEnabled(FeatureGate.Keys.AUTOMATIC_LEASE_RENEWAL)) {
                        leaseManager.addTaskInFlight(
                            LeaseRenewalManager.TaskInFlight(taskFuture, task.runAfter, task)
                        )
                    }
                } catch (e: Exception) {
                    if (stop.get()) {
                        // stop() cancels this loop's future, which interrupts the blocking take(); that
                        // surfaces here as a wrapped InterruptedException and is the normal way out.
                        log.info("stopping skipper scheduler manager.startTakingTasks for task type {}", type)
                        return
                    }
                    metrics
                        .counter(
                            ImmutableMap.of(ERROR_TAG, e.javaClass.simpleName),
                            METRICS_COMPONENT,
                            "takenTasksErrors",
                        )
                        .inc()
                    log.error("error while fetching and processing skipper tasks {}", e.stackTrace, e)
                    try {
                        Thread.sleep(1000)
                    } catch (ignored: InterruptedException) {
                    }
                }
            }
        }

        /**
         * Starts fetching tasks from the scheduler that are ready to be processed and adds them to the
         * scheduler queue. This is the scheduler queue producer thread.
         */
        // Faithful port of the Java baseline's `catch (Throwable $ex) { throw
        // SneakyThrow.sneakyThrow($ex); }` (a @SneakyThrows expansion); the broad catch is intentional.
        @Suppress("TooGenericExceptionCaught")
        private fun startFetchingTasks() {
            try {
                log.info("starting skipper scheduler manager.startFetchingTasks")
                while (!stop.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        val tasks = fetchTasksWithOptionalPartitioning()
                        log.debug("fetched {} tasks from scheduler", tasks.size())
                        metrics.counter(METRICS_COMPONENT, "fetchedTasks").inc()
                        tasks.forEach { task ->
                            log.debug("delivering task={}", task)
                            if (!schedulerQueue.add(task)) {
                                log.info(
                                    "attempted to add task to the queue but the task was already" +
                                        " present. task={}",
                                    task,
                                )
                            }
                        }
                        if (tasks.size() < DEFAULT_MAX_FETCH_SIZE) {
                            // A one-second sleep if good enough because persistent scheduler is not a
                            // blocker for the
                            // workflow execution and only used for retries and async workflow (with
                            // waits), so no
                            // need to slam the db with requests more frequently.
                            Thread.sleep(1000)
                        }
                    } catch (e: Exception) {
                        if (stop.get()) {
                            break // stop() interrupted the sleep above; leaving quietly is the normal exit
                        }
                        log.error("Error fetching tasks", e)
                        metrics
                            .counter(
                                ImmutableMap.of(ERROR_TAG, e.javaClass.simpleName),
                                METRICS_COMPONENT,
                                "fetchedTasksErrors",
                            )
                            .inc()
                        Thread.sleep(1000)
                    }
                }
                log.info("stopping skipper scheduler manager.startFetchingTasks")
            } catch (ex: Throwable) {
                throw SneakyThrow.sneakyThrow(ex)
            }
        }

        @VisibleForTesting
        fun getMaxTasks(): Int {
            val fetchSize =
                knobs.getInteger(Knobs.Keys.SCHEDULER_FETCH_COUNT).orElse(DEFAULT_MAX_FETCH_SIZE)
            if (fetchSize == DYNAMIC_FETCH_SIZE_MARKER) {
                return computeDynamicFetchSize()
            }
            return fetchSize
        }

        /**
         * Fetch tasks from the scheduler, using partitioning if enabled and cluster membership is
         * available.
         */
        private fun fetchTasksWithOptionalPartitioning(): List<Task<Any>> {
            val partitioningEnabled =
                featureGate.isEnabled(FeatureGate.Keys.TASK_PARTITIONING) &&
                    clusterMembershipManager.isRegistered
            if (!partitioningEnabled) {
                // Fall back to regular fetch if partitioning is disabled
                return scheduler.fetch(getMaxTasks())
            }
            try {
                val currentMember = clusterMembershipManager.currentMemberId
                val activeMembers: io.vavr.collection.List<String> =
                    clusterMembershipManager.activeMemberIds
                if (activeMembers.isEmpty) {
                    log.debug("No active members found, falling back to regular fetch")
                    return scheduler.fetch(getMaxTasks())
                }
                if (!activeMembers.contains(currentMember)) {
                    // Our own heartbeat has not landed (or has aged out while others' succeed): we cannot
                    // compute a partition, so fetch from the whole queue like the empty-list case rather
                    // than surface it as an error on every fetch.
                    log.debug("Current member {} not in active members, falling back to regular fetch", currentMember)
                    return scheduler.fetch(getMaxTasks())
                }
                // Calculate this member's bucket partition
                val partition = partitioner.getBucketRangeForMember(currentMember, activeMembers)
                log.debug(
                    "Using task partitioning: member={}, partition={}, activeMembers={}",
                    currentMember,
                    partition,
                    activeMembers.size(),
                )
                val tasks = scheduler.fetch<Any>(getMaxTasks(), partition)
                metrics
                    .counter(ImmutableMap.of("partitioned", "true"), METRICS_COMPONENT, "fetchedTasks")
                    .inc()
                return tasks
            } catch (e: Exception) {
                log.warn("Error during partitioned fetch, falling back to regular fetch", e)
                metrics
                    .counter(
                        ImmutableMap.of("error", "partitioning_fallback"),
                        METRICS_COMPONENT,
                        "fetchErrors",
                    )
                    .inc()
                return scheduler.fetch(getMaxTasks())
            }
        }

        /**
         * Computes the fetch size dynamically based on the current backlog size in the scheduler. It
         * uses a square root scaling to minimize contention while maintaining throughput. This is
         * useful when the use-case has processing spikes, and therefore having a static fetch size is
         * not optimal.
         */
        private fun computeDynamicFetchSize(): Int {
            val currentBacklogSize = schedulerBacklogSize.get()
            // Use square root scaling to minimize contention while maintaining throughput
            // Base fetch size of 1 for empty backlog, scaling up with sqrt of backlog size
            val fetchSize = Math.max(1, Math.sqrt((currentBacklogSize / 2).toDouble()).toInt())
            // Cap the fetch size to prevent excessive database load
            return Math.min(fetchSize, MAX_DYNAMIC_FETCH_SIZE)
        }

        private fun registerQueueMonitoring() {
            metrics.gauge(
                {
                    val size = scheduler.countBacklog()
                    schedulerBacklogSize.set(size)
                    size
                },
                METRICS_COMPONENT,
                "backloggedTasksCount",
            )
            metrics.gauge(
                { scheduler.getFailedTasks<Any>().size().toLong() },
                METRICS_COMPONENT,
                "failedTasksCount",
            )
            metrics.gauge({ getMaxTasks().toLong() }, METRICS_COMPONENT, "maxTasks")
            schedulerQueue
                .queueSizes
                .forEach { type, size ->
                    metrics.gauge(
                        { schedulerQueue.queueSizes.get(type)!!.toLong() },
                        ImmutableMap.of("type", type.toString()),
                        METRICS_COMPONENT,
                        "schedulerQueueSize",
                    )
                }
            if (clusterMembershipManager.isRegistered) {
                val currentMember = clusterMembershipManager.currentMemberId
                val clusterName = clusterMembershipManager.clusterName
                // Both gauges report an empty range (0, 0) while this member is absent from the active list,
                // mirroring the fetch fallback above, instead of throwing on the metrics scrape thread.
                metrics.gauge(
                    { currentPartition(currentMember)?.startInclusive?.toLong() ?: 0L },
                    ImmutableMap.of("memberId", currentMember, "clusterName", clusterName),
                    METRICS_COMPONENT,
                    "clusterMemberStartRange",
                )
                metrics.gauge(
                    { currentPartition(currentMember)?.endExclusive?.toLong() ?: 0L },
                    ImmutableMap.of("memberId", currentMember, "clusterName", clusterName),
                    METRICS_COMPONENT,
                    "clusterMemberEndRange",
                )
            } else {
                log.debug("Cluster membership not registered; skipping partition range metrics")
            }
        }

        /** This member's bucket range, or null when it is not (yet) in the active member list. */
        private fun currentPartition(currentMember: String): BucketRange? {
            val activeMembers = clusterMembershipManager.activeMemberIds
            if (!activeMembers.contains(currentMember)) {
                return null
            }
            return partitioner.getBucketRangeForMember(currentMember, activeMembers)
        }

        // Faithful port of the Java baseline's `catch (Throwable $ex) { throw
        // SneakyThrow.sneakyThrow($ex); }` (a @SneakyThrows expansion); the broad catch is intentional.
        @Suppress("TooGenericExceptionCaught")
        private fun renewExpiringLeases() {
            try {
                log.info("starting skipper scheduler manager.renewExpiringLeases")
                while (!stop.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        val renewedTask: Option<Task<*>> = leaseManager.attemptToRenewOneTask()
                        if (renewedTask.isEmpty) {
                            // There were no leases about to expire, sleep for a bit to catch a break.
                            Thread.sleep(500)
                        } else {
                            metrics.counter(METRICS_COMPONENT, "renewedLeases").inc()
                            log.info(
                                "renewed lease for task: {} new lease expires on {}",
                                renewedTask.get().id,
                                renewedTask.get().runAfter,
                            )
                        }
                    } catch (e: Exception) {
                        if (stop.get()) {
                            log.info("stopping skipper scheduler manager.renewExpiringLeases")
                            break // stop() interrupted the sleep above; leaving quietly is the normal exit
                        }
                        log.error("unexpected error while trying to renew leases", e)
                        metrics.counter(METRICS_COMPONENT, "renewLeaseErrors").inc()
                    }
                }
            } catch (ex: Throwable) {
                throw SneakyThrow.sneakyThrow(ex)
            }
        }

        @Throws(Exception::class)
        fun stop() {
            log.info("stopping skipper scheduler manager")
            stop.set(true)
            // Stop cluster membership manager
            try {
                clusterMembershipManager.stop()
                log.info("Stopped cluster membership manager")
            } catch (e: Exception) {
                log.error("Error stopping cluster membership manager", e)
            }
            // Initiate graceful shutdown - stop accepting new tasks
            executor.shutdown()
            taskHandlerExecutor.shutdown()
            // The consumer loops block indefinitely in the in-memory queue's take(); interrupt just those
            // (not the in-flight task wrappers that share the executor) so the executor can actually reach
            // termination within the grace period instead of always timing out and being forced.
            loopFutures.forEach { it.cancel(true) }
            // Wait for in-flight tasks to complete
            try {
                log.info(
                    "Waiting up to {} seconds per executor for in-flight tasks to complete",
                    gracefulShutdownTimeout.seconds,
                )
                val executorTerminated =
                    executor.awaitTermination(gracefulShutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)
                val taskHandlerTerminated =
                    taskHandlerExecutor.awaitTermination(
                        gracefulShutdownTimeout.toMillis(),
                        TimeUnit.MILLISECONDS,
                    )
                if (!executorTerminated || !taskHandlerTerminated) {
                    log.warn(
                        "Graceful shutdown timed out. executor terminated: {}, taskHandlerExecutor" +
                            " terminated: {}. Forcing shutdown of remaining tasks.",
                        executorTerminated,
                        taskHandlerTerminated,
                    )
                    metrics.counter(METRICS_COMPONENT, "forcedShutdown").inc()
                    if (!executorTerminated) {
                        executor.shutdownNow()
                    }
                    if (!taskHandlerTerminated) {
                        taskHandlerExecutor.shutdownNow()
                    }
                } else {
                    log.info("Skipper scheduler manager stopped gracefully")
                }
            } catch (e: InterruptedException) {
                log.warn("Interrupted while waiting for graceful shutdown, forcing shutdown")
                executor.shutdownNow()
                taskHandlerExecutor.shutdownNow()
            }
        }

        /**
         * Performs the final versioned [write] (remove or reschedule-for-retry) for a task we just finished
         * handling, using one consistent snapshot of what we know about it, and deals with the row's
         * version having moved under us.
         *
         * Three things move the version while we hold the lease, and they are told apart as follows:
         * - **Our own renewal** landed between reading our known version and the write: the row's version
         *   equals the (now updated) in-flight version. Just retry the write with it.
         * - **A rerun was recorded on our leased row** (`ScheduleRequest.isBumpVersionWhenHonoringLease`,
         *   the path a signal takes): the version differs but `run_after` is exactly what we know, since
         *   the bump leaves it alone. Or the renewer already adopted that bump and flagged the in-flight
         *   entry, in which case the row no longer disagrees with us and the flag is the only trace.
         *   Either way the request is served by [scheduleRerunNow] instead of removing the row.
         * - **Another worker fetched the task** after our lease lapsed: a fetch always re-leases the row to
         *   `now + leaseDuration`, so `run_after` differs. The row is theirs now; leave it.
         *
         * [resultTag] is recorded on success; `null` keeps whatever the caller already set.
         */
        private fun finishTask(
            task: Task<*>,
            tags: MutableMap<String, String>,
            resultTag: String?,
            write: (Task<*>) -> Unit,
        ) {
            repeat(MAX_FINISH_ATTEMPTS) {
                val known = snapshot(task)
                if (known.rerunRequested) {
                    scheduleRerunNow(task, tags)
                    return
                }
                try {
                    write(known.task)
                    resultTag?.let { tags[RESULT_TAG] = it }
                    return
                } catch (e: OptimisticLockingError) {
                    log.debug("version of task {} moved before its final write; working out why", task.id)
                }
                val current = scheduler.getTask<Any>(task.id)
                if (current.isEmpty) {
                    tags[RESULT_TAG] = "removedElsewhere"
                    log.info("task {} was removed by another worker before its final write", task.id)
                    return
                }
                val afterwards = snapshot(task)
                when {
                    afterwards.rerunRequested -> {
                        scheduleRerunNow(task, tags)
                        return
                    }
                    current.get().version == afterwards.task.version -> {
                        // Our own renewal raced the write; the snapshot now carries the renewed version.
                    }
                    current.get().runAfter == afterwards.task.runAfter -> {
                        scheduleRerunNow(task, tags)
                        return
                    }
                    else -> {
                        tags[RESULT_TAG] = "leaseLost"
                        log.warn(
                            "task {} was re-leased by another worker before its final write; leaving it to them." +
                                " known={}, current={}",
                            task.id,
                            afterwards.task,
                            current.get(),
                        )
                        return
                    }
                }
            }
            tags[RESULT_TAG] = "leaseContended"
            log.warn("task {} kept moving during its final write; leaving it to run when its lease expires", task.id)
        }

        /** What we know about [task]: the version we hold, and whether a rerun was requested, read together. */
        private fun snapshot(task: Task<*>): Snapshot =
            leaseManager
                .getTaskInFlight(task.id)
                .map { Snapshot(it.task, it.rerunRequested) }
                .getOrElse(Snapshot(task, false))

        private data class Snapshot(
            val task: Task<*>,
            val rerunRequested: Boolean,
        )

        /**
         * Serves a rerun requested while we held the lease: overwrite the row (no lease honoured, since
         * ours is the lease) to PENDING with `retry_count` reset and an immediate `run_after`, so the
         * next fetch picks it up instead of waiting for the lease to lapse. If the overwrite loses its
         * own race the row still survives and runs at lease expiry.
         *
         * Only WORKFLOW tasks are rerun this way: their handler hydrates the payload from the store, so
         * the stale stored payload can be dropped. Other task types need their payload and are left to
         * re-run at lease expiry, where the row still carries it.
         */
        private fun scheduleRerunNow(
            task: Task<*>,
            tags: MutableMap<String, String>,
        ) {
            tags[RESULT_TAG] = "rerunRequested"
            if (task.type != Task.Type.WORKFLOW) {
                log.info("task {} ({}) was re-requested while leased; it will run when its lease expires", task.id, task.type)
                return
            }
            try {
                scheduler.schedule(
                    ScheduleRequest.builder<Any>()
                        .id(task.id)
                        .dedupToken(task.dedupToken)
                        .type(task.type)
                        .payload(null) // WorkflowExecutionTaskHandler hydrates by id; the stored payload would be stale
                        .runAfter(Instant.EPOCH)
                        .inMemoryExecutionEnabled(false)
                        .honorActiveLeaseWhenOverwriting(false)
                        .build(),
                )
                metrics.counter(METRICS_COMPONENT, "rerunRequestedWhileLeased").inc()
                log.info("task {} was re-requested while leased; rescheduled to run now", task.id)
            } catch (e: OptimisticLockingError) {
                log.info(
                    "task {} was re-requested while leased and moved again; it will run when its lease expires",
                    task.id,
                )
            }
        }

        /** Immediately stops the scheduler without waiting for in-flight tasks. For test use only. */
        fun forceStop() {
            stop.set(true)
            executor.shutdownNow()
            taskHandlerExecutor.shutdownNow()
        }

        // Catches Throwable as the last line of defense (faithful to the original Java); narrowing
        // would change behavior, so the detekt rule is suppressed instead.
        @Suppress("TooGenericExceptionCaught")
        @VisibleForTesting
        fun handleTask(task: Task<*>) {
            if (task.retryCount > maxRetries) {
                log.warn("task has reached max retries. marking task as failed. task={}", task)
                scheduler.markAsFailed(task, "max retries reached")
                metrics.counter(METRICS_COMPONENT, "failedTasks").inc()
                return
            }
            val tags = HashMap<String, String>()
            tags["taskType"] = task.type.toString()
            if (taskHandlers.containsKey(task.type)) {
                val handler = taskHandlers.get(task.type).get()
                log.debug("handling '{}' task: {}", handler, task.id)
                try {
                    val rescheduleTime: Option<Instant> =
                        handler
                            .handle(task, taskHandlerExecutor)
                            .get(task.executionTimeout.seconds, TimeUnit.SECONDS)
                    if (rescheduleTime.isDefined) {
                        log.info("rescheduling task: {} to {}", task.id, rescheduleTime.get())
                        try {
                            finishTask(task, tags, "reschedule") { scheduler.rescheduleForRetry(it, rescheduleTime.get()) }
                        } catch (e: IllegalArgumentException) {
                            // The task has already been dequeued; let whoever did that handle it.
                            tags[RESULT_TAG] = "rescheduleFailed"
                            log.warn("failed to reschedule task, another thread already processed it: {}", task.id, e)
                        }
                    } else {
                        finishTask(task, tags, "remove") { scheduler.remove(it) }
                    }
                } catch (e: ExecutionException) {
                    // If option is empty, it means the task has been processed and should be dequeued.
                    // Error while trying to process the task. e.getCause() will contain the actual
                    // underlying error.
                    // An error at this stage is considered an unexpected error and will not be retried,
                    // hence we should dequeue.
                    tags[RESULT_TAG] = "error"
                    tags[ERROR_TAG] = e.cause!!.javaClass.simpleName
                    log.warn("task processing threw unexpected error. task={}", task, e)
                    finishTask(task, tags, resultTag = null) { scheduler.remove(it) }
                } catch (e: InterruptedException) {
                    tags[RESULT_TAG] = "error"
                    tags[ERROR_TAG] = e.javaClass.simpleName
                    log.warn("task execution interrupted. task={}", task, e)
                } catch (e: TimeoutException) {
                    tags[RESULT_TAG] = "error"
                    tags[ERROR_TAG] = e.javaClass.simpleName
                    log.warn("task execution timed out. task={}", task, e)
                } catch (e: Throwable) {
                    // This is the last line of defense in case scheduler failed to remove or reschedule
                    // the task. We will treat that the same way we'd treat a crash and let the
                    // scheduler handle it, but we will log it.
                    tags[RESULT_TAG] = "internalError"
                    tags[ERROR_TAG] = e.javaClass.simpleName
                    log.warn("task processing threw unexpected internal error. task={}", task, e)
                } finally {
                    metrics.counter(tags, METRICS_COMPONENT, "handledTasks").inc()
                }
            } else {
                log.error("no handler found for task type {}. task={}", task, task.type)
                tags[RESULT_TAG] = "error"
                tags[ERROR_TAG] = "unhandledTaskType"
                finishTask(task, tags, resultTag = null) { scheduler.remove(it) }
                metrics.counter(tags, METRICS_COMPONENT, "handledTasks").inc()
            }
        }

        companion object {
            private val log = org.slf4j.LoggerFactory.getLogger(SkipperSchedulerManager::class.java)

            private const val DEFAULT_MAX_FETCH_SIZE = 5
            private const val DYNAMIC_FETCH_SIZE_MARKER = -1
            private const val MAX_DYNAMIC_FETCH_SIZE = 20

            /** Attempts [finishTask] makes before leaving a contended row to lease expiry. */
            private const val MAX_FINISH_ATTEMPTS = 3
            private const val METRICS_COMPONENT = "schedulerManager"
            private const val RESULT_TAG = "result"
            private const val ERROR_TAG = "error"
        }
    }
