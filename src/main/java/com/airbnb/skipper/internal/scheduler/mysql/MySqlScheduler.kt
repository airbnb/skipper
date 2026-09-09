package com.airbnb.skipper.internal.scheduler.mysql

import com.airbnb.skipper.ComponentFactory
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.OptimisticLockingError
import com.airbnb.skipper.SkipperAnnotationNames.SCHEDULER_LEASE_DURATION
import com.airbnb.skipper.SkipperAnnotationNames.TABLE_PREFIX
import com.airbnb.skipper.SkipperAnnotationNames.TENANT
import com.airbnb.skipper.SkipperAnnotationNames.UTC_CLOCK
import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.internal.InternalError
import com.airbnb.skipper.internal.cluster.BucketPartitioner
import com.airbnb.skipper.internal.cluster.BucketRange
import com.airbnb.skipper.internal.common.SneakyThrow
import com.airbnb.skipper.internal.scheduler.ScheduleRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.serde.SimplePojoSerde
import com.airbnb.skipper.internal.storage.EntityAlreadyExists
import com.airbnb.skipper.internal.storage.JdbcTransactionManager
import com.google.common.collect.ImmutableMap
import io.vavr.collection.List as VavrList
import io.vavr.control.Option
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Named
import org.slf4j.LoggerFactory

/**
 * A MySQL backed implementation of the scheduler.
 *
 * The mysql schema expected by this implementation is defined under
 * `src/main/resources/db/migration/`.
 */
class MySqlScheduler
    @Inject
    constructor(
        transactionManager: JdbcTransactionManager,
        @Named(UTC_CLOCK) clock: Clock,
        @Named(TENANT) owner: String,
        metrics: Metrics,
        serde: SimplePojoSerde,
        @Named(SCHEDULER_LEASE_DURATION) leaseDuration: Duration,
        @Named(TABLE_PREFIX) tablePrefix: String,
    ) : Scheduler {
        private val transactionManager: JdbcTransactionManager = transactionManager
        private val clock: Clock = clock
        private val owner: String = owner
        private val metrics: Metrics = metrics
        private val serde: SimplePojoSerde = serde
        private val leaseDuration: Duration = leaseDuration

        // Derived from the configured prefix (the single source of truth shared with the Flyway DDL);
        // every SQL literal below interpolates this instead of hard-coding a fixed table name.
        private val schedulerTasksTable: String = tablePrefix + "scheduler_tasks"

        override fun <T> schedule(request: ScheduleRequest<T>): Task<T> =
            transactionManager.execute { conn ->
                val existingTask: Option<Task<T>> = getTaskForUpdate(request.id)
                if (existingTask.isDefined) {
                    // Update if the task already exists
                    log.info(
                        "task already exists with id: {}, existing task will be overwritten",
                        request.id,
                    )
                    val hasActiveLease =
                        existingTask.get().hasActiveLease(clock.instant(), leaseDuration)
                    val shouldHonorLease =
                        hasActiveLease && request.isHonorActiveLeaseWhenOverwriting
                    var runAfter =
                        (request.runAfter ?: Instant.EPOCH).truncatedTo(ChronoUnit.MILLIS)
                    if (shouldHonorLease) {
                        runAfter = existingTask.get().runAfter
                        log.info(
                            "task with id: {} has an active lease, runAfter is not being overridden." +
                                " runAfter={}",
                            request.id,
                            runAfter,
                        )
                        if (!request.isBumpVersionWhenHonoringLease) {
                            return@execute existingTask.get()
                        }
                        return@execute recordRerunRequest(conn, existingTask.get())
                    }
                    log.info(
                        "task with id: {} is being overwritten. runAfter={}. hasActiveLease={}." +
                            " shouldHonorLease={}",
                        request.id,
                        runAfter,
                        hasActiveLease,
                        shouldHonorLease,
                    )
                    // Reset status and retry count
                    val overwrittenTask: Task<T> =
                        existingTask.get().toBuilder()
                            .runAfter(
                                (request.runAfter ?: Instant.EPOCH).truncatedTo(ChronoUnit.MILLIS),
                            )
                            .payload(request.payload)
                            .executionTimeout(Duration.ofMillis(request.executionTimeout.toMillis()))
                            .status(Task.Status.PENDING)
                            .retryCount(0)
                            .shouldRefreshPayload(true)
                            .version(existingTask.get().version + 1)
                            .build()
                    val updateSql =
                        "UPDATE $schedulerTasksTable SET run_after = ?, payload = ?," +
                            " execution_timeout_secs = ?, status = ?, retry_count = ?," +
                            " should_refresh_payload = ?, version = ? WHERE task_id = ? AND owner = ? AND" +
                            " version = ?"
                    try {
                        conn.prepareStatement(updateSql).use { ps ->
                            metrics.timer(METRICS_COMPONENT, "scheduleUpdateTime").time().use {
                                var i = 0
                                ps.setTimestamp(++i, Timestamp.from(overwrittenTask.runAfter))
                                ps.setString(++i, serde.serialize(overwrittenTask.payload))
                                ps.setLong(++i, overwrittenTask.executionTimeout.seconds)
                                ps.setString(++i, overwrittenTask.status.name)
                                ps.setInt(++i, overwrittenTask.retryCount)
                                ps.setBoolean(++i, overwrittenTask.isShouldRefreshPayload)
                                ps.setInt(++i, overwrittenTask.version)
                                // Conditional params
                                ps.setString(++i, overwrittenTask.id)
                                ps.setString(++i, owner)
                                ps.setInt(++i, existingTask.get().version) // version of the stored task
                                val rowsUpdated = ps.executeUpdate()
                                if (rowsUpdated != 1) {
                                    throw OptimisticLockingError(
                                        "failed to update task with id: " + request.id,
                                    )
                                }
                                return@execute overwrittenTask
                            }
                        }
                    } catch (e: SQLException) {
                        throw InternalError(
                            "unable to update existing task with id: " + request.id,
                            e,
                        )
                    }
                }
                // If no task exists, then create a new one
                val insertSql =
                    "INSERT INTO $schedulerTasksTable (task_id, owner, run_after, payload, type," +
                        " dedup_token, execution_timeout_secs, status, retry_count," +
                        " should_refresh_payload, version,created_at, updated_at) VALUES (?, ?, ?, ?," +
                        " ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
                val runAfter =
                    (request.runAfter ?: Instant.EPOCH).truncatedTo(ChronoUnit.MILLIS)
                try {
                    conn.prepareStatement(insertSql).use { ps ->
                        metrics.timer(METRICS_COMPONENT, "scheduleTime").time().use {
                            var i = 0
                            ps.setString(++i, request.id)
                            ps.setString(++i, owner)
                            ps.setTimestamp(++i, Timestamp.from(runAfter))
                            ps.setString(++i, serde.serialize(request.payload))
                            ps.setString(++i, request.type.name)
                            ps.setString(++i, request.dedupToken)
                            ps.setLong(++i, request.executionTimeout.seconds)
                            ps.setString(++i, Task.Status.PENDING.name)
                            ps.setInt(++i, 0) // retry_count
                            ps.setBoolean(++i, true) // should_refresh_payload
                            ps.setInt(++i, 1) // version
                            ps.setTimestamp(++i, Timestamp.from(now)) // created_at
                            ps.setTimestamp(++i, Timestamp.from(now)) // updated_at
                            val rowsInserted = ps.executeUpdate()
                            if (rowsInserted != 1) {
                                throw InternalError("failed to insert new task with id: " + request.id)
                            }
                            return@execute Task.builder<T>()
                                .id(request.id)
                                .createdAt(now)
                                .runAfter(runAfter)
                                .retryCount(0)
                                .payload(request.payload)
                                .dedupToken(request.dedupToken)
                                .type(request.type)
                                .status(Task.Status.PENDING)
                                .executionTimeout(Duration.ofSeconds(request.executionTimeout.seconds))
                                .shouldRefreshPayload(true)
                                .version(1)
                                .build()
                        }
                    }
                } catch (e: SQLException) {
                    if (e is SQLIntegrityConstraintViolationException) {
                        throw EntityAlreadyExists()
                    }
                    throw InternalError("unable to insert new task with id: " + request.id, e)
                }
            }

        override fun <T> fetch(limit: Int): VavrList<Task<T>> {
            try {
                val querySql =
                    "SELECT * FROM $schedulerTasksTable " +
                        "WHERE owner = ? AND status IN (?, ?) AND run_after <= ? " +
                        "ORDER BY rand() LIMIT ?"
                val updateSql =
                    "UPDATE $schedulerTasksTable SET status = ?, version = ?, run_after = ?, " +
                        "retry_count = retry_count + 1 " +
                        "WHERE owner = ? AND task_id = ? AND version = ?"
                transactionManager.getConnection().use { conn ->
                    conn.autoCommit = true
                    try {
                        conn.prepareStatement(querySql).use { ps ->
                            metrics.timer(METRICS_COMPONENT, "fetchTime").time().use {
                                var i = 0
                                ps.setString(++i, owner)
                                ps.setString(++i, Task.Status.PENDING.name)
                                ps.setString(++i, Task.Status.RUNNING.name)
                                ps.setTimestamp(++i, Timestamp.from(clock.instant()))
                                ps.setInt(++i, limit)
                                val rs = ps.executeQuery()
                                val leasedTasks: MutableList<Task<T>> = ArrayList()
                                while (rs.next()) {
                                    val taskId = rs.getString("task_id")
                                    val version = rs.getInt("version")
                                    conn.prepareStatement(updateSql).use { updatePs ->
                                        var j = 0
                                        val leaseExpire =
                                            clock.instant().plus(leaseDuration).truncatedTo(ChronoUnit.MILLIS)
                                        updatePs.setString(++j, Task.Status.RUNNING.name)
                                        updatePs.setInt(++j, version + 1)
                                        updatePs.setTimestamp(++j, Timestamp.from(leaseExpire))
                                        // Conditional params
                                        updatePs.setString(++j, owner)
                                        updatePs.setString(++j, taskId)
                                        updatePs.setInt(++j, version)
                                        val rowsUpdated = updatePs.executeUpdate()
                                        if (rowsUpdated != 1) {
                                            // Another thread got the lease first, skip this task.
                                            metrics
                                                .counter(
                                                    ImmutableMap.of("source", "fetchGetLease"),
                                                    METRICS_COMPONENT,
                                                    "optimisticLockingError",
                                                )
                                                .inc()
                                        } else {
                                            // We got the lease
                                            @Suppress("UNCHECKED_CAST")
                                            leasedTasks.add(
                                                recordToModel<T>(rs).toBuilder()
                                                    .version(version + 1)
                                                    .status(Task.Status.RUNNING)
                                                    .runAfter(leaseExpire)
                                                    .build() as Task<T>,
                                            )
                                        }
                                    }
                                }
                                return VavrList.ofAll(leasedTasks)
                            }
                        }
                    } catch (e: SQLException) {
                        throw InternalError("Failed to fetch tasks", e)
                    }
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") ex: Throwable
            ) {
                throw SneakyThrow.sneakyThrow(ex)
            }
        }

        override fun <T> fetch(
            limit: Int,
            partition: BucketRange?,
        ): VavrList<Task<T>> {
            try {
                if (partition == null) {
                    return fetch(limit)
                }
                // Use MySQL's CRC32 function to hash task_id into buckets (0-999)
                // CRC32(CONVERT(task_id USING utf8mb4)) % 1000 converts task_id to bucket number
                val bucketSize = BucketPartitioner.TOTAL_BUCKETS.toString()
                val querySql =
                    "SELECT * FROM $schedulerTasksTable " +
                        "WHERE owner = ? AND status IN (?, ?) AND run_after <= ? " +
                        "AND CRC32(CONVERT(task_id USING utf8mb4)) % " +
                        bucketSize +
                        " >= ? " +
                        "AND CRC32(CONVERT(task_id USING utf8mb4)) % " +
                        bucketSize +
                        " < ? " +
                        "ORDER BY rand() LIMIT ?"
                val updateSql =
                    "UPDATE $schedulerTasksTable SET status = ?, version = ?, run_after = ?, " +
                        "retry_count = retry_count + 1 " +
                        "WHERE owner = ? AND task_id = ? AND version = ?"
                transactionManager.getConnection().use { conn ->
                    conn.autoCommit = true
                    try {
                        conn.prepareStatement(querySql).use { ps ->
                            metrics.timer(METRICS_COMPONENT, "fetchPartitionedTime").time().use {
                                var i = 0
                                ps.setString(++i, owner)
                                ps.setString(++i, Task.Status.PENDING.name)
                                ps.setString(++i, Task.Status.RUNNING.name)
                                ps.setTimestamp(++i, Timestamp.from(clock.instant()))
                                ps.setInt(++i, partition.startInclusive)
                                ps.setInt(++i, partition.endExclusive)
                                ps.setInt(++i, limit)
                                val rs = ps.executeQuery()
                                val leasedTasks: MutableList<Task<T>> = ArrayList()
                                while (rs.next()) {
                                    val taskId = rs.getString("task_id")
                                    val version = rs.getInt("version")
                                    conn.prepareStatement(updateSql).use { updatePs ->
                                        var j = 0
                                        val leaseExpire =
                                            clock.instant().plus(leaseDuration).truncatedTo(ChronoUnit.MILLIS)
                                        updatePs.setString(++j, Task.Status.RUNNING.name)
                                        updatePs.setInt(++j, version + 1)
                                        updatePs.setTimestamp(++j, Timestamp.from(leaseExpire))
                                        // Conditional params
                                        updatePs.setString(++j, owner)
                                        updatePs.setString(++j, taskId)
                                        updatePs.setInt(++j, version)
                                        val rowsUpdated = updatePs.executeUpdate()
                                        if (rowsUpdated != 1) {
                                            // Another thread got the lease first, skip this task.
                                            metrics
                                                .counter(
                                                    ImmutableMap.of("source", "fetchPartitionedGetLease"),
                                                    METRICS_COMPONENT,
                                                    "optimisticLockingError",
                                                )
                                                .inc()
                                        } else {
                                            // We got the lease
                                            @Suppress("UNCHECKED_CAST")
                                            leasedTasks.add(
                                                recordToModel<T>(rs).toBuilder()
                                                    .version(version + 1)
                                                    .status(Task.Status.RUNNING)
                                                    .runAfter(leaseExpire)
                                                    .build() as Task<T>,
                                            )
                                        }
                                    }
                                }
                                return VavrList.ofAll(leasedTasks)
                            }
                        }
                    } catch (e: SQLException) {
                        throw InternalError("Failed to fetch partitioned tasks", e)
                    }
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") ex: Throwable
            ) {
                throw SneakyThrow.sneakyThrow(ex)
            }
        }

        // Removed fetchWrapAroundPartition method - no longer needed with bucket-based partitioning

        /**
         * Process ResultSet and attempt to lease tasks, returning the number of successfully leased
         * tasks.
         */
        @Throws(SQLException::class)
        private fun <T> processResultSet(
            rs: ResultSet,
            updateSql: String,
            conn: Connection,
            leasedTasks: MutableList<Task<T>>,
        ): Int {
            var leasedCount = 0
            while (rs.next()) {
                val taskId = rs.getString("task_id")
                val version = rs.getInt("version")
                conn.prepareStatement(updateSql).use { updatePs ->
                    var j = 0
                    val leaseExpire = clock.instant().plus(leaseDuration).truncatedTo(ChronoUnit.MILLIS)
                    updatePs.setString(++j, Task.Status.RUNNING.name)
                    updatePs.setInt(++j, version + 1)
                    updatePs.setTimestamp(++j, Timestamp.from(leaseExpire))
                    // Conditional params
                    updatePs.setString(++j, owner)
                    updatePs.setString(++j, taskId)
                    updatePs.setInt(++j, version)
                    val rowsUpdated = updatePs.executeUpdate()
                    if (rowsUpdated != 1) {
                        // Another thread got the lease first, skip this task.
                        metrics
                            .counter(
                                ImmutableMap.of("source", "fetchWrapAroundGetLease"),
                                METRICS_COMPONENT,
                                "optimisticLockingError",
                            )
                            .inc()
                    } else {
                        // We got the lease
                        @Suppress("UNCHECKED_CAST")
                        leasedTasks.add(
                            recordToModel<T>(rs).toBuilder()
                                .version(version + 1)
                                .status(Task.Status.RUNNING)
                                .runAfter(leaseExpire)
                                .build() as Task<T>,
                        )
                        leasedCount++
                    }
                }
            }
            return leasedCount
        }

        override fun <T> remove(task: Task<T>) {
            val sql =
                "DELETE FROM $schedulerTasksTable WHERE task_id = ? AND owner = ? AND version = ?"
            transactionManager.execute<Void?> { conn ->
                if (!getTaskForUpdate<Any>(task.id).isDefined) {
                    log.warn("unable to remove nonexistent task with id: {}", task.id)
                    return@execute null
                }
                try {
                    conn.prepareStatement(sql).use { ps ->
                        metrics.timer(METRICS_COMPONENT, "removeTime").time().use {
                            ps.setString(1, task.id)
                            ps.setString(2, owner)
                            ps.setInt(3, task.version)
                            val rowsDeleted = ps.executeUpdate()
                            if (rowsDeleted != 1) {
                                metrics
                                    .counter(
                                        ImmutableMap.of("source", "remove"),
                                        METRICS_COMPONENT,
                                        "optimisticLockingError",
                                    )
                                    .inc()
                                throw OptimisticLockingError("failed to delete task with id: " + task.id)
                            }
                            return@execute null
                        }
                    }
                } catch (e: SQLException) {
                    throw InternalError("unable to delete task with id: " + task.id, e)
                }
            }
        }

        override fun <T> markAsFailed(
            task: Task<T>,
            statusMessage: String,
        ) {
            val sql =
                "UPDATE $schedulerTasksTable SET status = ?, version = ?, status_message = ?" +
                    " WHERE task_id = ? AND owner = ? AND version = ?"
            transactionManager.execute<Void?> { conn ->
                if (getTaskForUpdate<Any>(task.id).isEmpty) {
                    throw IllegalArgumentException("task not found with id: " + task.id)
                }
                try {
                    conn.prepareStatement(sql).use { ps ->
                        metrics.timer(METRICS_COMPONENT, "markAsFailedTime").time().use {
                            var i = 0
                            ps.setString(++i, Task.Status.FAILED.name)
                            ps.setInt(++i, task.version + 1)
                            ps.setString(++i, statusMessage)
                            // Conditional params
                            ps.setString(++i, task.id)
                            ps.setString(++i, owner)
                            ps.setInt(++i, task.version)
                            val rowsUpdated = ps.executeUpdate()
                            if (rowsUpdated != 1) {
                                throw OptimisticLockingError(
                                    "failed to mark task as failed with id: " + task.id,
                                )
                            }
                            return@execute null
                        }
                    }
                } catch (e: SQLException) {
                    throw InternalError("unable to mark task as failed with id: " + task.id, e)
                }
            }
        }

        override fun <T> rescheduleForRetry(
            task: Task<T>,
            runAfter: Instant,
        ) {
            val sql =
                "UPDATE $schedulerTasksTable SET run_after = ?, retry_count = retry_count + 1, version =" +
                    " ?, status = ? WHERE task_id = ? AND owner = ? AND version = ?"
            transactionManager.execute<Void?> { conn ->
                if (getTaskForUpdate<Any>(task.id).isEmpty) {
                    throw IllegalArgumentException("task not found with id: " + task.id)
                }
                try {
                    conn.prepareStatement(sql).use { ps ->
                        metrics.timer(METRICS_COMPONENT, "rescheduleForRetryTime").time().use {
                            var i = 0
                            ps.setTimestamp(++i, Timestamp.from(runAfter))
                            ps.setInt(++i, task.version + 1)
                            ps.setString(++i, Task.Status.PENDING.name)
                            // Conditional params
                            ps.setString(++i, task.id)
                            ps.setString(++i, owner)
                            ps.setInt(++i, task.version)
                            val rowsUpdated = ps.executeUpdate()
                            if (rowsUpdated != 1) {
                                throw OptimisticLockingError(
                                    "failed to reschedule task with id: " + task.id,
                                )
                            }
                            return@execute null
                        }
                    }
                } catch (e: SQLException) {
                    throw InternalError("unable to reschedule task with id: " + task.id, e)
                }
            }
        }

        override fun <T> renewLease(task: Task<T>): Task<T> {
            val sql =
                "UPDATE $schedulerTasksTable SET run_after = ?, version = ?" +
                    " WHERE task_id = ? AND owner = ? AND version = ?"
            return transactionManager.execute { conn ->
                val existingTask: Option<Task<T>> = getTaskForUpdate(task.id)
                if (existingTask.isEmpty) {
                    throw IllegalArgumentException("task not found with id: " + task.id)
                }
                // Perform state validation
                if (existingTask.get().status != Task.Status.PENDING &&
                    existingTask.get().status != Task.Status.RUNNING
                ) {
                    throw IllegalStateException(
                        "unable to reschedule task, task is not in pending state with id: " + task.id,
                    )
                }
                try {
                    conn.prepareStatement(sql).use { ps ->
                        metrics.timer(METRICS_COMPONENT, "renewLeaseTime").time().use {
                            var i = 0
                            val leaseExpire =
                                clock.instant().plus(leaseDuration).truncatedTo(ChronoUnit.MILLIS)
                            ps.setTimestamp(++i, Timestamp.from(leaseExpire))
                            ps.setInt(++i, task.version + 1)
                            // Conditional params
                            ps.setString(++i, task.id)
                            ps.setString(++i, owner)
                            ps.setInt(++i, task.version)
                            val rowsUpdated = ps.executeUpdate()
                            if (rowsUpdated != 1) {
                                metrics
                                    .counter(
                                        ImmutableMap.of("source", "renewLease"),
                                        METRICS_COMPONENT,
                                        "optimisticLockingError",
                                    )
                                    .inc()
                                throw OptimisticLockingError(
                                    "failed to renew lease for task with id: " + task.id,
                                )
                            }
                            return@execute task.toBuilder().runAfter(leaseExpire).version(task.version + 1).build()
                        }
                    }
                } catch (e: SQLException) {
                    throw InternalError("unable to renew lease for task with id: " + task.id, e)
                }
            }
        }

        override fun <T> getTask(taskId: String): Option<Task<T>> = getTask(taskId, false)

        private fun <T> getTaskForUpdate(taskId: String): Option<Task<T>> = getTask(taskId, true)

        private fun <T> getTask(
            taskId: String,
            forUpdate: Boolean,
        ): Option<Task<T>> {
            val sql =
                "SELECT * FROM $schedulerTasksTable WHERE owner = ? AND task_id = ?" +
                    (if (forUpdate) " FOR UPDATE" else "")
            return transactionManager.execute { conn ->
                try {
                    conn.prepareStatement(sql).use { ps ->
                        metrics.timer(METRICS_COMPONENT, "getTaskTime").time().use {
                            ps.setString(1, owner)
                            ps.setString(2, taskId)
                            val rs = ps.executeQuery()
                            if (!rs.next()) {
                                return@execute Option.none()
                            }
                            return@execute Option.of(recordToModel(rs))
                        }
                    }
                } catch (e: SQLException) {
                    metrics
                        .counter(ImmutableMap.of("source", "getTask"), METRICS_COMPONENT, "errors")
                        .inc()
                    throw InternalError(e)
                }
            }
        }

        private fun <T> recordToModel(rs: ResultSet): Task<T> {
            try {
                @Suppress("UNCHECKED_CAST")
                val payload = serde.deserialize(rs.getString("payload")) as T
                return Task.builder<T>()
                    .id(rs.getString("task_id"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .runAfter(rs.getTimestamp("run_after").toInstant())
                    .retryCount(rs.getInt("retry_count"))
                    .payload(payload)
                    .dedupToken(rs.getString("dedup_token"))
                    .type(Task.Type.valueOf(rs.getString("type")))
                    .status(Task.Status.valueOf(rs.getString("status")))
                    .executionTimeout(Duration.ofSeconds(rs.getInt("execution_timeout_secs").toLong()))
                    .shouldRefreshPayload(rs.getBoolean("should_refresh_payload"))
                    .version(rs.getInt("version"))
                    .build()
            } catch (
                @Suppress("TooGenericExceptionCaught") ex: Throwable
            ) {
                throw SneakyThrow.sneakyThrow(ex)
            }
        }

        override fun requeueFailedTask(taskId: String) {
            // 1. open transaction, inside the transaction get the task by id.
            // 2. if the task is not found throw illegal argument exception. if the task is not in failed
            // status, do the same.
            // 3. update the task, set the status to pending, retry count to zero and runAfter to now.
            transactionManager.execute<Void?> { conn ->
                val taskOption: Option<Task<Any>> = getTaskForUpdate(taskId)
                if (taskOption.isEmpty) {
                    throw IllegalArgumentException("Task not found with id: $taskId")
                }
                val task = taskOption.get()
                if (task.status != Task.Status.FAILED) {
                    throw IllegalArgumentException("Task is not in failed status with id: $taskId")
                }
                val updateSql =
                    "UPDATE $schedulerTasksTable SET status = ?, retry_count = ?, run_after = ?, version" +
                        " = ? WHERE task_id = ? AND owner = ? AND version = ?"
                try {
                    conn.prepareStatement(updateSql).use { updatePs ->
                        metrics.timer(METRICS_COMPONENT, "requeueFailedTask").time().use {
                            var i = 0
                            updatePs.setString(++i, Task.Status.PENDING.name)
                            updatePs.setInt(++i, 0) // reset retry count
                            updatePs.setTimestamp(++i, Timestamp.from(clock.instant())) // set runAfter to now
                            updatePs.setInt(++i, task.version + 1) // increment version
                            // Conditional params
                            updatePs.setString(++i, taskId)
                            updatePs.setString(++i, owner)
                            updatePs.setInt(++i, task.version)
                            val rowsUpdated = updatePs.executeUpdate()
                            if (rowsUpdated != 1) {
                                throw OptimisticLockingError("failed to update task with id: $taskId")
                            }
                        }
                    }
                } catch (e: SQLException) {
                    throw java.lang.InternalError(
                        "unable to requeue failed task with id: $taskId",
                        e,
                    )
                }
                null
            }
        }

        override fun <T> getFailedTasks(): VavrList<Task<T>> {
            val sql =
                "SELECT * FROM $schedulerTasksTable " +
                    "WHERE owner = ? AND status = ? " +
                    "ORDER BY run_after ASC LIMIT 1000"
            return transactionManager.execute { conn ->
                try {
                    conn.prepareStatement(sql).use { ps ->
                        metrics.timer(METRICS_COMPONENT, "getFailedTasks").time().use {
                            ps.setString(1, owner)
                            ps.setString(2, Task.Status.FAILED.name)
                            val rs = ps.executeQuery()
                            var tasks: VavrList<Task<T>> = VavrList.empty()
                            while (rs.next()) {
                                tasks = tasks.append(recordToModel(rs))
                            }
                            return@execute tasks
                        }
                    }
                } catch (e: SQLException) {
                    throw InternalError(e)
                }
            }
        }

        override fun countBacklog(): Long {
            val sql =
                "SELECT COUNT(*) FROM $schedulerTasksTable " +
                    "WHERE owner = ? AND run_after <= ? AND status IN (?, ?)"
            return transactionManager.execute { conn ->
                try {
                    conn.prepareStatement(sql).use { ps ->
                        ps.setString(1, owner)
                        ps.setTimestamp(2, Timestamp.from(clock.instant()))
                        ps.setString(3, Task.Status.PENDING.name)
                        ps.setString(4, Task.Status.RUNNING.name)
                        val rs = ps.executeQuery()
                        if (!rs.next()) {
                            return@execute 0L
                        }
                        return@execute rs.getLong(1)
                    }
                } catch (e: SQLException) {
                    throw InternalError(e)
                }
            }
        }

        override fun realSize(): Long =
            transactionManager.execute { conn ->
                val sql = "SELECT COUNT(*) FROM $schedulerTasksTable WHERE owner = ?"
                try {
                    conn.prepareStatement(sql).use { ps ->
                        ps.setString(1, owner)
                        val rs = ps.executeQuery()
                        if (!rs.next()) {
                            return@execute 0L
                        }
                        return@execute rs.getLong(1)
                    }
                } catch (e: SQLException) {
                    throw InternalError(e)
                }
            }

        /**
         * Records a rerun request on a task whose lease is being honoured, by bumping only its version.
         * `run_after` and `status` are left alone so the lease holder keeps ownership; the bump is what
         * makes the holder's final versioned `remove` (or `rescheduleForRetry`) fail, so the row is kept
         * and rescheduled instead of being deleted with the request inside it. Consumers of the version
         * tell this bump from a competing fetch by `run_after`, which a fetch always moves.
         *
         * Runs inside the caller's transaction, right after `getTaskForUpdate`, so a zero-row update can
         * only mean a concurrent writer slipped in; that is reported as [OptimisticLockingError] like
         * every other versioned write here.
         */
        private fun <T> recordRerunRequest(
            conn: java.sql.Connection,
            task: Task<T>,
        ): Task<T> {
            val sql = "UPDATE $schedulerTasksTable SET version = ? WHERE task_id = ? AND owner = ? AND version = ?"
            try {
                conn.prepareStatement(sql).use { ps ->
                    ps.setInt(1, task.version + 1)
                    ps.setString(2, task.id)
                    ps.setString(3, owner)
                    ps.setInt(4, task.version)
                    if (ps.executeUpdate() != 1) {
                        metrics
                            .counter(
                                ImmutableMap.of("source", "schedule"),
                                METRICS_COMPONENT,
                                "optimisticLockingError",
                            )
                            .inc()
                        throw OptimisticLockingError(
                            "failed to record rerun request for leased task with id: " + task.id,
                        )
                    }
                }
            } catch (e: SQLException) {
                throw InternalError("unable to record rerun request for task with id: " + task.id, e)
            }
            metrics.counter(METRICS_COMPONENT, "rerunRecordedOnLeasedTask").inc()
            return task.toBuilder().version(task.version + 1).build()
        }

        class Factory : ComponentFactory<Scheduler> {
            override fun create(config: SkipperConfig): Scheduler =
                MySqlScheduler(
                    JdbcTransactionManager.MySqlFactory().create(config),
                    config.utcClock,
                    config.tenant,
                    config.metrics.create(config),
                    config.simplePojoSerde.create(config),
                    config.schedulerTaskLeaseDuration,
                    config.tablePrefix,
                )
        }

        companion object {
            private val log = LoggerFactory.getLogger(MySqlScheduler::class.java)
            private const val METRICS_COMPONENT = "mysqlScheduler"
        }
    }
