package com.airbnb.skipper.internal.storage.sqlite

import com.airbnb.skipper.Actions
import com.airbnb.skipper.ComponentFactory
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.OptimisticLockingError
import com.airbnb.skipper.RequestContextSerde
import com.airbnb.skipper.SkipperAnnotationNames.TABLE_PREFIX
import com.airbnb.skipper.SkipperAnnotationNames.TENANT
import com.airbnb.skipper.SkipperAnnotationNames.UTC_CLOCK
import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.SkipperError
import com.airbnb.skipper.Timer
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowCallbackHandler
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.CheckpointTag
import com.airbnb.skipper.internal.InternalError
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.internal.api.PersistedSignal
import com.airbnb.skipper.internal.common.SneakyThrow
import com.airbnb.skipper.internal.serde.Serde
import com.airbnb.skipper.internal.serde.SimplePojoSerde
import com.airbnb.skipper.internal.storage.ClassNameCompat
import com.airbnb.skipper.internal.storage.EntityAlreadyExists
import com.airbnb.skipper.internal.storage.JdbcTransactionManager
import com.airbnb.skipper.internal.storage.TimerCreationRequest
import com.airbnb.skipper.internal.storage.WorkflowCreationRequest
import com.airbnb.skipper.internal.storage.WorkflowSearchFilter
import com.airbnb.skipper.internal.storage.WorkflowSortDirection
import com.airbnb.skipper.internal.storage.WorkflowSortField
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.internal.storage.WorkflowUpdateRequest
import com.airbnb.skipper.util.ExtraRequestData
import com.google.common.collect.ImmutableMap
import io.vavr.Tuple2
import io.vavr.Tuple3
import io.vavr.collection.HashMap
import io.vavr.collection.List
import io.vavr.control.Either
import io.vavr.control.Option
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import javax.inject.Inject
import javax.inject.Named
import org.sqlite.SQLiteException

/**
 * A [WorkflowStore] implementation that uses SQLite as the backing store.
 *
 * This mirrors `MySqlWorkflowStore` method-for-method, including its raw-JDBC design and its
 * `version`-based optimistic locking (every `updatedRows != 1 ⇒ OptimisticLockingError` check is
 * preserved). Only SQL-dialect details differ:
 *
 * - Backtick identifier quoting is removed; SQLite uses bare identifiers.
 * - Duplicate-key detection uses SQLite's `SQLITE_CONSTRAINT` (primary result code 19, e.g.
 *   extended `SQLITE_CONSTRAINT_PRIMARYKEY`) rather than MySQL's
 *   [java.sql.SQLIntegrityConstraintViolationException]. See [isUniqueConstraintViolation]. As in
 *   MySQL, [EntityAlreadyExists] is thrown from exactly two places — [createWorkflow] and
 *   [createTimer].
 * - Generated-key retrieval ([Statement.RETURN_GENERATED_KEYS] +
 *   [PreparedStatement.getGeneratedKeys]) is used only by [persistSignal]; the
 *   `INTEGER PRIMARY KEY AUTOINCREMENT` column aliases SQLite's `rowid` so the generated id is
 *   returned correctly.
 *
 * Behavior is otherwise identical to the MySQL implementation for every [WorkflowStore] method.
 */
class SqliteWorkflowStore
    @Inject
    constructor(
        sqliteTransactionManager: JdbcTransactionManager,
        metrics: Metrics,
        serdeStrategy: Serde,
        simpleSerde: SimplePojoSerde,
        requestContextSerde: RequestContextSerde,
        @param:Named(UTC_CLOCK) clock: Clock,
        @param:Named(TENANT) owner: String,
        @param:Named(TABLE_PREFIX) tablePrefix: String
    ) : WorkflowStore {
        private val metrics: Metrics = metrics
        private val transactionManager: JdbcTransactionManager = sqliteTransactionManager
        private val serdeStrategy: Serde = serdeStrategy
        private val simpleSerde: SimplePojoSerde = simpleSerde
        private val requestContextSerde: RequestContextSerde = requestContextSerde
        private val clock: Clock = clock
        private val owner: String = owner

        // Table names are derived from the configured prefix (the single source of truth shared with
        // the Flyway DDL); every SQL literal below interpolates these instead of hard-coding `tempo_`.
        private val workflowInstancesTable: String = tablePrefix + "workflow_instances"
        private val actionCheckpointsTable: String = tablePrefix + "action_checkpoints"
        private val timersTable: String = tablePrefix + "timers"
        private val persistedSignalsTable: String = tablePrefix + "persisted_signals"

        override fun createWorkflow(request: WorkflowCreationRequest): WorkflowInstance {
            try {
                val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
                val workflowInstance =
                    WorkflowInstance.builder()
                        .workflowMethod(request.workflowMethod)
                        .workflowClass(request.workflowClass)
                        .input(request.input)
                        .requestContext(request.requestContext)
                        .extraRequestData(request.extraRequestData)
                        .version(1)
                        .status(WorkflowInstance.Status.RUNNING)
                        .workflowId(request.workflowId)
                        .state(HashMap.empty<String, Any?>())
                        .result(CompletableFuture<Any?>())
                        .callbackHandler(request.callbackHandler)
                        .timeoutTime(request.timeoutTime)
                        .createdAt(now)
                        .updatedAt(now)
                        .parentWorkflowId(request.parentWorkflowId)
                        .build()
                // Skipper persists the request context as opaque bytes produced by the host-supplied
                // RequestContextSerde. A host deployment can plug a custom serde to persist its own
                // request-context format; deployments that wire RequestContextSerde.NOOP (the default)
                // leave the column empty.
                val serializedRequestContext = requestContextSerde.serialize(workflowInstance.requestContext)
                val serializedInput = serializeWorkflowInput(workflowInstance.input)
                val serializedState = serializeWorkflowState(workflowInstance.state)
                val sql =
                    (
                        "INSERT INTO $workflowInstancesTable (owner, workflow_id, workflow_class," +
                            " workflow_method, input, state, result_value,result_error, result_is_error," +
                            " result_is_done, result_is_async, callback_handler,status, request_context," +
                            " extra_request_data, timeout_time, created_at, updated_at,version," +
                            " parent_workflow_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                    )
                val insertedRows =
                    transactionManager.execute { conn ->
                        try {
                            conn.prepareStatement(sql).use { ps ->
                                metrics.timer(METRICS_COMPONENT, "createWorkflow").time().use { _ ->
                                    var i = 0
                                    ps.setString(++i, owner)
                                    ps.setString(++i, workflowInstance.workflowId)
                                    ps.setString(++i, workflowInstance.workflowClass.name)
                                    ps.setString(++i, workflowInstance.workflowMethod)
                                    ps.setString(++i, serializedInput)
                                    ps.setString(++i, serializedState)
                                    // Result
                                    var serializedResult: String? = null
                                    var serializedError: String? = null
                                    var resultIsError = false
                                    var resultIsDone = false
                                    val flattenedResult = workflowInstance.flattenResult()
                                    if (flattenedResult != null) {
                                        // Result is available
                                        resultIsDone = true
                                        if (flattenedResult.isError()) {
                                            // Result is error
                                            serializedError = serdeStrategy.serialize(flattenedResult.error)
                                            resultIsError = true
                                        } else {
                                            serializedResult = serdeStrategy.serialize(flattenedResult.ok)
                                        }
                                    }
                                    ps.setString(++i, serializedResult)
                                    ps.setString(++i, serializedError)
                                    ps.setBoolean(++i, resultIsError)
                                    ps.setBoolean(++i, resultIsDone)
                                    ps.setBoolean(++i, workflowInstance.isResultAsync())
                                    ps.setString(++i, workflowInstance.callbackHandler?.name)
                                    ps.setString(++i, workflowInstance.status.name)
                                    ps.setString(++i, String(serializedRequestContext, StandardCharsets.UTF_8))
                                    ps.setString(++i, simpleSerde.serialize(workflowInstance.extraRequestData))
                                    ps.setTimestamp(
                                        ++i,
                                        if (workflowInstance.timeoutTime != null) {
                                            Timestamp.from(workflowInstance.timeoutTime)
                                        } else {
                                            null
                                        }
                                    )
                                    ps.setTimestamp(++i, Timestamp.from(workflowInstance.createdAt))
                                    ps.setTimestamp(++i, Timestamp.from(workflowInstance.updatedAt))
                                    ps.setInt(++i, workflowInstance.version)
                                    ps.setString(++i, workflowInstance.parentWorkflowId)
                                    ps.executeUpdate()
                                }
                            }
                        } catch (e: SQLException) {
                            if (isUniqueConstraintViolation(e)) {
                                throw EntityAlreadyExists()
                            }
                            metrics
                                .counter(ImmutableMap.of("source", "createWorkflow"), METRICS_COMPONENT, "errors")
                                .inc()
                            throw InternalError(e)
                        }
                    }
                if (insertedRows != 1) {
                    throw NonRetryableError("Failed to insert workflow instance")
                }
                // Track payload size in bytes of workflow input and state at creation
                if (serializedInput != null) {
                    val inputBytes = serializedInput.toByteArray(StandardCharsets.UTF_8).size
                    metrics
                        .histogram(
                            ImmutableMap.of(OPERATION_TAG, OPERATION_CREATE, FIELD_TAG, FIELD_INPUT),
                            METRICS_COMPONENT,
                            METRIC_PAYLOAD_SIZE_BYTES
                        )
                        .update(inputBytes)
                }
                if (serializedState != null) {
                    val stateBytes = serializedState.toByteArray(StandardCharsets.UTF_8).size
                    metrics
                        .histogram(
                            ImmutableMap.of(OPERATION_TAG, OPERATION_CREATE, FIELD_TAG, FIELD_STATE),
                            METRICS_COMPONENT,
                            METRIC_PAYLOAD_SIZE_BYTES
                        )
                        .update(stateBytes)
                }
                return workflowInstance
            } catch (
                @Suppress("TooGenericExceptionCaught") ex: Throwable
            ) {
                throw SneakyThrow.sneakyThrow(ex)
            }
        }

        override fun updateWorkflow(request: WorkflowUpdateRequest): WorkflowInstance {
            val updatedInstance =
                request
                    .computeWorkflowInstanceAfterUpdate()
                    .toBuilder()
                    .version(request.workflowInstance.version + 1)
                    .updatedAt(clock.instant())
                    .build()
            val serializedState = serializeWorkflowState(updatedInstance.state)
            val flattenedResult = updatedInstance.flattenResult()
            val serializedResult: String? =
                if (flattenedResult != null && !flattenedResult.isError()) {
                    serdeStrategy.serialize(flattenedResult.ok)
                } else {
                    null
                }
            val sql =
                (
                    "UPDATE $workflowInstancesTable SET status = ?, state = ?, result_value = ?, " +
                        "result_is_done = ?, result_is_async = ?, result_is_error = ?, result_error = ?, " +
                        "version = version + 1, updated_at = ? " +
                        "WHERE owner = ? AND workflow_id = ? AND version = ?"
                )
            val updatedRows =
                transactionManager.execute { conn ->
                    try {
                        conn.prepareStatement(sql).use { ps ->
                            metrics.timer(METRICS_COMPONENT, "updateWorkflow.latency").time().use { _ ->
                                var i = 0
                                ps.setString(++i, updatedInstance.status.name)
                                ps.setString(++i, serializedState)
                                // Result
                                var serializedError: String? = null
                                var resultIsError = false
                                var resultIsDone = false
                                if (flattenedResult != null) {
                                    // Result is available
                                    resultIsDone = true
                                    if (flattenedResult.isError()) {
                                        // Result is error
                                        serializedError = serdeStrategy.serialize(flattenedResult.error)
                                        resultIsError = true
                                    }
                                }
                                ps.setString(++i, serializedResult)
                                ps.setBoolean(++i, resultIsDone)
                                ps.setBoolean(++i, updatedInstance.isResultAsync())
                                ps.setBoolean(++i, resultIsError)
                                ps.setString(++i, serializedError)
                                ps.setTimestamp(++i, Timestamp.from(updatedInstance.updatedAt))
                                // Conditional args
                                ps.setString(++i, owner)
                                ps.setString(++i, updatedInstance.workflowId)
                                ps.setInt(++i, request.workflowInstance.version)
                                ps.executeUpdate()
                            }
                        }
                    } catch (e: SQLException) {
                        metrics
                            .counter(ImmutableMap.of("source", "updateWorkflow"), METRICS_COMPONENT, "errors")
                            .inc()
                        throw InternalError(e)
                    }
                }
            if (updatedRows != 1) {
                throw OptimisticLockingError("unable to update workflow due to stale version")
            }
            // Track payload size in bytes of workflow state and result at update
            if (serializedState != null) {
                val stateBytes = serializedState.toByteArray(StandardCharsets.UTF_8).size
                metrics
                    .histogram(
                        ImmutableMap.of(OPERATION_TAG, OPERATION_UPDATE, FIELD_TAG, FIELD_STATE),
                        METRICS_COMPONENT,
                        METRIC_PAYLOAD_SIZE_BYTES
                    )
                    .update(stateBytes)
            }
            if (serializedResult != null) {
                val resultBytes = serializedResult.toByteArray(StandardCharsets.UTF_8).size
                metrics
                    .histogram(
                        ImmutableMap.of(OPERATION_TAG, OPERATION_UPDATE, FIELD_TAG, FIELD_RESULT),
                        METRICS_COMPONENT,
                        METRIC_PAYLOAD_SIZE_BYTES
                    )
                    .update(resultBytes)
            }
            return updatedInstance
        }

        override fun getWorkflow(workflowId: String): Option<WorkflowInstance> {
            val sql = "SELECT * FROM $workflowInstancesTable WHERE workflow_id = ? AND owner = ?"
            return transactionManager.execute { conn ->
                try {
                    conn.prepareStatement(sql).use { ps ->
                        metrics.timer(METRICS_COMPONENT, "getWorkflow.latency").time().use { _ ->
                            ps.setString(1, workflowId)
                            ps.setString(2, owner)
                            val resultSet = ps.executeQuery()
                            if (!resultSet.next()) {
                                Option.none<WorkflowInstance>()
                            } else {
                                recordToWorkflowInstance(resultSet)
                            }
                        }
                    }
                } catch (e: SQLException) {
                    metrics
                        .counter(ImmutableMap.of("source", "getWorkflow"), METRICS_COMPONENT, "errors")
                        .inc()
                    throw InternalError(e)
                }
            }
        }

        override fun countWorkflowsByStatus(statuses: List<WorkflowInstance.Status>): Long {
            val sql =
                (
                    "SELECT COUNT(*) FROM $workflowInstancesTable WHERE owner = ? AND status IN (" +
                        java.lang.String.join(",", statuses.map { "?" }.toJavaList()) +
                        ")"
                )
            return transactionManager
                .execute<Number> { conn ->
                    try {
                        conn.prepareStatement(sql).use { ps ->
                            metrics.timer(METRICS_COMPONENT, "countWorkflowsByStatus.latency").time().use { _ ->
                                ps.setString(1, owner)
                                for (i in 0 until statuses.size()) {
                                    ps.setString(i + 2, statuses[i].name)
                                }
                                val rs = ps.executeQuery()
                                if (rs.next()) {
                                    rs.getLong(1)
                                } else {
                                    0
                                }
                            }
                        }
                    } catch (e: SQLException) {
                        metrics
                            .counter(
                                ImmutableMap.of("source", "countWorkflowsByStatus"),
                                METRICS_COMPONENT,
                                "errors"
                            )
                            .inc()
                        throw InternalError(e)
                    }
                }
                .toLong()
        }

        override fun findWorkflowsWithExhaustedRetries(
            limit: Int,
            sortField: WorkflowSortField?,
            sortDirection: WorkflowSortDirection?
        ): List<WorkflowInstance> {
            // No index covers (owner, status, updated_at), so the ORDER BY costs a sort over the whole
            // matching set. Callers that don't need a sort get the index order instead.
            // The `when` is exhaustive on purpose: adding a WorkflowSortField won't compile until it
            // is mapped to a column here.
            val orderByClause =
                if (sortField == null) {
                    ""
                } else {
                    val column =
                        when (sortField) {
                            WorkflowSortField.UPDATED_AT -> "updated_at"
                        }
                    val direction = if (sortDirection == WorkflowSortDirection.DESC) "DESC" else "ASC"
                    "ORDER BY $column $direction "
                }
            val sql =
                String.format(
                    "SELECT * FROM $workflowInstancesTable WHERE owner = ? AND status IN ('%s', '%s') " +
                        "${orderByClause}LIMIT ?",
                    WorkflowInstance.Status.RETRIES_EXHAUSTED.name,
                    WorkflowInstance.Status.COMPENSATION_ERROR.name
                )
            return transactionManager.execute { conn ->
                try {
                    conn.prepareStatement(sql).use { ps ->
                        metrics
                            .timer(METRICS_COMPONENT, "findWorkflowsWithExhaustedRetries.latency")
                            .time()
                            .use { _ ->
                                ps.setString(1, owner)
                                ps.setInt(2, limit)
                                val rs = ps.executeQuery()
                                val workflows = java.util.ArrayList<WorkflowInstance>()
                                while (rs.next()) {
                                    val wf = recordToWorkflowInstance(rs)
                                    if (wf.isDefined) {
                                        workflows.add(wf.get())
                                    }
                                }
                                List.ofAll(workflows)
                            }
                    }
                } catch (e: SQLException) {
                    metrics
                        .counter(
                            ImmutableMap.of("source", "findWorkflowsWithExhaustedRetries"),
                            METRICS_COMPONENT,
                            "errors"
                        )
                        .inc()
                    throw InternalError(e)
                }
            }
        }

        /**
         * Uses `SELECT DISTINCT` with all statuses explicitly listed in the WHERE clause so the query can
         * use the composite index (owner, status, workflow_class, workflow_method) — one range scan per
         * status value, deduplicating across all of them. Without the status IN clause the engine would
         * skip the status column in the index prefix and fall back to a less efficient scan.
         */
        override fun listDistinctWorkflowTypes(): List<Tuple2<String, String>> {
            val statusPlaceholders =
                java.lang.String.join(
                    ", ",
                    java.util.Collections.nCopies(WorkflowInstance.Status.values().size, "?")
                )
            val sql =
                (
                    "SELECT DISTINCT workflow_class, workflow_method FROM $workflowInstancesTable" +
                        " WHERE owner = ? AND status IN (" +
                        statusPlaceholders +
                        ")"
                )
            return transactionManager.execute { conn ->
                try {
                    conn.prepareStatement(sql).use { ps ->
                        metrics.timer(METRICS_COMPONENT, "listDistinctWorkflowTypes.latency").time().use { _ ->
                            ps.setString(1, owner)
                            var idx = 2
                            for (s in WorkflowInstance.Status.values()) {
                                ps.setString(idx++, s.name)
                            }
                            val rs = ps.executeQuery()
                            val types = java.util.ArrayList<Tuple2<String, String>>()
                            while (rs.next()) {
                                types.add(Tuple2(rs.getString("workflow_class"), rs.getString("workflow_method")))
                            }
                            List.ofAll(types)
                        }
                    }
                } catch (e: SQLException) {
                    metrics
                        .counter(
                            ImmutableMap.of("source", "listDistinctWorkflowTypes"),
                            METRICS_COMPONENT,
                            "errors"
                        )
                        .inc()
                    throw InternalError(e)
                }
            }
        }

        /**
         * All filters are pushed into the SQL query. The composite index (owner, status, workflow_class,
         * workflow_method) is used when status and/or entry point filters are set. Date range and
         * parentWorkflowId filters require scanning within the matched rows since those columns are not
         * indexed. Cursor-based pagination uses `ORDER BY created_at DESC, workflow_id DESC`.
         */
        override fun findWorkflows(
            filter: WorkflowSearchFilter,
            limit: Int
        ): List<WorkflowInstance> {
            val sql = StringBuilder("SELECT * FROM $workflowInstancesTable WHERE owner = ?")
            val params = java.util.ArrayList<Any>()
            params.add(owner)
            val workflowEntryPoints = filter.workflowEntryPoints
            if (workflowEntryPoints != null && !workflowEntryPoints.isEmpty()) {
                sql.append(" AND (")
                for (i in 0 until workflowEntryPoints.size) {
                    if (i > 0) sql.append(" OR ")
                    sql.append("(workflow_class = ? AND workflow_method = ?)")
                }
                sql.append(")")
                for (ep in workflowEntryPoints) {
                    params.add(ep.workflowClass)
                    params.add(ep.workflowMethod)
                }
            }
            val statuses = filter.statuses
            if (statuses != null && !statuses.isEmpty()) {
                sql.append(" AND status IN (")
                for (i in 0 until statuses.size) {
                    sql.append(if (i > 0) ", ?" else "?")
                }
                sql.append(")")
                for (s in statuses) {
                    params.add(s.name)
                }
            }
            if (filter.createdAfter != null) {
                sql.append(" AND created_at >= ?")
                params.add(Timestamp.from(filter.createdAfter))
            }
            if (filter.createdBefore != null) {
                sql.append(" AND created_at <= ?")
                params.add(Timestamp.from(filter.createdBefore))
            }
            val parentWorkflowId = filter.parentWorkflowId
            if (parentWorkflowId != null && !parentWorkflowId.isEmpty()) {
                sql.append(" AND parent_workflow_id = ?")
                params.add(parentWorkflowId)
            }
            val cursor = filter.cursor
            if (cursor != null) {
                sql.append(" AND (created_at < ? OR (created_at = ? AND workflow_id < ?))")
                params.add(Timestamp.from(cursor.createdAt))
                params.add(Timestamp.from(cursor.createdAt))
                params.add(cursor.workflowId)
            }
            sql.append(" ORDER BY created_at DESC, workflow_id DESC LIMIT ?")
            params.add(limit)
            val finalSql = sql.toString()
            return transactionManager.execute { conn ->
                try {
                    conn.prepareStatement(finalSql).use { ps ->
                        metrics.timer(METRICS_COMPONENT, "findWorkflows.latency").time().use { _ ->
                            for (i in 0 until params.size) {
                                val param = params[i]
                                if (param is String) {
                                    ps.setString(i + 1, param)
                                } else if (param is Int) {
                                    ps.setInt(i + 1, param)
                                } else if (param is Timestamp) {
                                    ps.setTimestamp(i + 1, param)
                                }
                            }
                            val rs = ps.executeQuery()
                            val workflows = java.util.ArrayList<WorkflowInstance>()
                            while (rs.next()) {
                                val wf = recordToWorkflowInstance(rs)
                                if (wf.isDefined) {
                                    workflows.add(wf.get())
                                }
                            }
                            List.ofAll(workflows)
                        }
                    }
                } catch (e: SQLException) {
                    metrics
                        .counter(ImmutableMap.of("source", "findWorkflows"), METRICS_COMPONENT, "errors")
                        .inc()
                    throw InternalError(e)
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun recordToWorkflowInstance(rs: ResultSet): Option<WorkflowInstance> {
            try {
                val builder = WorkflowInstance.builder()
                try {
                    val workflowClass: Class<out Workflow>
                    val workflowClassString = rs.getString("workflow_class")
                    try {
                        workflowClass = Class.forName(workflowClassString) as Class<out Workflow>
                    } catch (e: ClassNotFoundException) {
                        throw IllegalArgumentException("Workflow class not found: $workflowClassString")
                    }
                    var callbackHandler: Class<out WorkflowCallbackHandler>? = null
                    val callbackHandlerString = rs.getString("callback_handler")
                    if (callbackHandlerString != null) {
                        try {
                            callbackHandler = Class.forName(callbackHandlerString) as Class<out WorkflowCallbackHandler>
                        } catch (e: ClassNotFoundException) {
                            throw IllegalArgumentException(
                                "WorkflowCallbackHandler class not found: $callbackHandlerString"
                            )
                        }
                    }
                    val requestContext =
                        requestContextSerde.deserialize(
                            rs.getString("request_context").toByteArray(StandardCharsets.UTF_8)
                        )
                    val extraRequestData =
                        simpleSerde.deserialize(rs.getString("extra_request_data")) as ExtraRequestData
                    builder.workflowId(rs.getString("workflow_id"))
                    builder.workflowClass(workflowClass)
                    builder.workflowMethod(rs.getString("workflow_method"))
                    builder.input(deserializeWorkflowInput(rs.getString("input")))
                    builder.state(deserializeWorkflowState(rs.getString("state")))
                    builder.callbackHandler(callbackHandler)
                    builder.status(WorkflowInstance.Status.valueOf(rs.getString("status")))
                    builder.requestContext(requestContext)
                    builder.extraRequestData(extraRequestData)
                    val timeoutTime = rs.getTimestamp("timeout_time")
                    builder.timeoutTime(if (timeoutTime != null) timeoutTime.toInstant() else null)
                    builder.createdAt(rs.getTimestamp("created_at").toInstant())
                    builder.updatedAt(rs.getTimestamp("updated_at").toInstant())
                    builder.version(rs.getInt("version"))
                    builder.parentWorkflowId(rs.getString("parent_workflow_id"))
                    // Result
                    var result = CompletableFuture<Any?>()
                    if (rs.getBoolean("result_is_done")) {
                        if (rs.getBoolean("result_is_error")) {
                            val error = serdeStrategy.deserialize(rs.getString("result_error")) as SkipperError
                            result.completeExceptionally(error)
                        } else {
                            result.complete(serdeStrategy.deserialize(rs.getString("result_value")))
                        }
                        if (rs.getBoolean("result_is_async")) {
                            result = CompletableFuture.completedFuture<Any?>(result)
                        }
                    }
                    builder.result(result)
                    return Option.of(builder.build())
                } catch (e: SQLException) {
                    throw NonRetryableError(e.message)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") ex: Throwable
            ) {
                throw SneakyThrow.sneakyThrow(ex)
            }
        }

        private fun deserializeWorkflowInput(input: String?): Any? {
            var deserializedInput: Any? = null
            if (input != null) {
                deserializedInput = serdeStrategy.deserialize(input)
            }
            return deserializedInput
        }

        private fun deserializeWorkflowState(state: String): io.vavr.collection.Map<String, Any?> {
            try {
                val typeFactory = simpleSerde.objectMapper.typeFactory
                val mapType =
                    typeFactory.constructMapType(
                        java.util.HashMap::class.java,
                        String::class.java,
                        String::class.java
                    )
                val stateData: Map<String, String> = simpleSerde.objectMapper.readValue(state, mapType)
                return HashMap.ofAll(stateData).mapValues { serdeStrategy.deserialize(it) }
            } catch (
                @Suppress("TooGenericExceptionCaught") ex: Throwable
            ) {
                throw SneakyThrow.sneakyThrow(ex)
            }
        }

        override fun getActionCheckpoints(workflowId: String): List<ActionCheckpoint> {
            val sql =
                (
                    "SELECT * FROM $actionCheckpointsTable " +
                        "WHERE workflow_id = ? AND owner = ? ORDER BY id ASC"
                )
            return transactionManager.execute { conn ->
                try {
                    conn.prepareStatement(sql).use { ps ->
                        metrics.timer(METRICS_COMPONENT, "getActionCheckpoints.latency").time().use { _ ->
                            ps.setString(1, workflowId)
                            ps.setString(2, owner)
                            val rs = ps.executeQuery()
                            val checkpoints = java.util.ArrayList<ActionCheckpoint>()
                            while (rs.next()) {
                                checkpoints.add(checkpointRsToModel(rs))
                            }
                            List.ofAll(checkpoints)
                        }
                    }
                } catch (e: SQLException) {
                    metrics
                        .counter(ImmutableMap.of("source", "getActionCheckpoints"), METRICS_COMPONENT, "errors")
                        .inc()
                    throw InternalError(e)
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        @Throws(SQLException::class)
        private fun checkpointRsToModel(rs: ResultSet): ActionCheckpoint {
            val clazz: Class<out Actions>
            try {
                clazz = ClassNameCompat.forName(rs.getString("action_class")) as Class<out Actions>
            } catch (e: ClassNotFoundException) {
                throw NonRetryableError(
                    String.format("unable to deserialize class %s", rs.getString("action_class")),
                    e
                )
            }
            val actionInput = rs.getString("input")
            return ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId(rs.getString("workflow_id"))
                        .actionClass(clazz)
                        .actionMethod(rs.getString("action_method"))
                        .iteration(rs.getInt("iteration").toLong())
                        .checkpointName(rs.getString("checkpoint_name"))
                        .build()
                )
                .executionStartTime(rs.getTimestamp("execution_start_time").toInstant())
                .executionEndTime(
                    if (rs.getTimestamp("execution_end_time") != null) {
                        rs.getTimestamp("execution_end_time").toInstant()
                    } else {
                        null
                    }
                )
                .result(
                    if (rs.getBoolean("is_success")) {
                        Either.right<Throwable, Any?>(serdeStrategy.deserialize(rs.getString("result")))
                    } else {
                        Either.left<Throwable, Any?>(
                            serdeStrategy.deserialize(rs.getString("error")) as Throwable
                        )
                    }
                )
                .resultIsAsync(rs.getBoolean("result_is_async"))
                .isTransient(rs.getBoolean("is_transient"))
                .input(deserializeWorkflowInput(actionInput))
                .build()
        }

        override fun persistSignal(signal: PersistedSignal): PersistedSignal {
            val now = clock.instant()
            // Mirror createWorkflow's handling of the request context: serialize via the host-supplied
            // RequestContextSerde and store the opaque bytes as a UTF-8 string so a replay can restore the
            // exact context that was active when the signal was sent.
            val serializedRequestContext =
                String(requestContextSerde.serialize(signal.requestContext), StandardCharsets.UTF_8)
            val serializedInput = serializeWorkflowInput(signal.input)
            // The error is a plain stack-trace string kept for operator reference only, so it is stored
            // verbatim rather than serialized as an exception.
            val serializedError = signal.error
            val sql =
                (
                    "INSERT INTO $persistedSignalsTable (owner, workflow_id, signal_method, input, " +
                        "request_context, status, error, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                )
            val generatedId =
                transactionManager.execute<Long> { conn ->
                    try {
                        conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
                            metrics.timer(METRICS_COMPONENT, "persistSignal").time().use { _ ->
                                var i = 0
                                ps.setString(++i, owner)
                                ps.setString(++i, signal.workflowId)
                                ps.setString(++i, signal.signalMethod)
                                ps.setString(++i, serializedInput)
                                ps.setString(++i, serializedRequestContext)
                                ps.setString(++i, signal.status.name)
                                ps.setString(++i, serializedError)
                                ps.setTimestamp(++i, Timestamp.from(now))
                                ps.setTimestamp(++i, Timestamp.from(now))
                                val insertedRows = ps.executeUpdate()
                                if (insertedRows != 1) {
                                    throw InternalError("failed to persist signal")
                                }
                                ps.generatedKeys.use { keys ->
                                    if (!keys.next()) {
                                        throw InternalError("failed to obtain persisted signal id")
                                    }
                                    keys.getLong(1)
                                }
                            }
                        }
                    } catch (e: SQLException) {
                        metrics
                            .counter(ImmutableMap.of("source", "persistSignal"), METRICS_COMPONENT, "errors")
                            .inc()
                        throw InternalError(e)
                    }
                }
            return PersistedSignal(
                signal.workflowId,
                signal.signalMethod,
                signal.status,
                signal.input,
                signal.requestContext,
                signal.error,
                generatedId,
                now,
                now
            )
        }

        override fun updateWorkflowAndMarkSignal(
            request: WorkflowUpdateRequest,
            signalId: Long,
            status: PersistedSignal.Status
        ): Tuple2<WorkflowInstance, PersistedSignal> {
            return transactionManager.execute { conn ->
                val updatedInstance = updateWorkflow(request)
                val updatedSignal =
                    updateSignalStatus(request.workflowInstance.workflowId, signalId, status, null)
                Tuple2(updatedInstance, updatedSignal)
            }
        }

        override fun updateSignalStatus(
            workflowId: String,
            signalId: Long,
            status: PersistedSignal.Status,
            error: String?
        ): PersistedSignal {
            val now = clock.instant()
            val sql =
                (
                    "UPDATE $persistedSignalsTable SET status = ?, error = ?, updated_at = ? " +
                        "WHERE owner = ? AND workflow_id = ? AND id = ?"
                )
            val updatedRows =
                transactionManager.execute { conn ->
                    try {
                        conn.prepareStatement(sql).use { ps ->
                            metrics.timer(METRICS_COMPONENT, "updateSignalStatus").time().use { _ ->
                                var i = 0
                                ps.setString(++i, status.name)
                                ps.setString(++i, error)
                                ps.setTimestamp(++i, Timestamp.from(now))
                                ps.setString(++i, owner)
                                ps.setString(++i, workflowId)
                                ps.setLong(++i, signalId)
                                ps.executeUpdate()
                            }
                        }
                    } catch (e: SQLException) {
                        metrics
                            .counter(
                                ImmutableMap.of("source", "updateSignalStatus"),
                                METRICS_COMPONENT,
                                "errors"
                            )
                            .inc()
                        throw InternalError(e)
                    }
                }
            if (updatedRows != 1) {
                throw NonRetryableError(
                    String.format("persisted signal %d for workflow %s not found", signalId, workflowId)
                )
            }
            return getPersistedSignal(workflowId, signalId)
                .getOrElseThrow {
                    NonRetryableError(
                        String.format(
                            "persisted signal %d for workflow %s not found after update",
                            signalId,
                            workflowId
                        )
                    )
                }
        }

        override fun getPersistedSignals(workflowId: String): List<PersistedSignal> {
            val sql =
                (
                    "SELECT * FROM $persistedSignalsTable " +
                        "WHERE workflow_id = ? AND owner = ? ORDER BY id ASC"
                )
            return transactionManager.execute { conn ->
                try {
                    conn.prepareStatement(sql).use { ps ->
                        metrics.timer(METRICS_COMPONENT, "getPersistedSignals.latency").time().use { _ ->
                            ps.setString(1, workflowId)
                            ps.setString(2, owner)
                            val rs = ps.executeQuery()
                            val signals = java.util.ArrayList<PersistedSignal>()
                            while (rs.next()) {
                                signals.add(signalRsToModel(rs))
                            }
                            List.ofAll(signals)
                        }
                    }
                } catch (e: SQLException) {
                    metrics
                        .counter(ImmutableMap.of("source", "getPersistedSignals"), METRICS_COMPONENT, "errors")
                        .inc()
                    throw InternalError(e)
                }
            }
        }

        override fun getPersistedSignal(
            workflowId: String,
            signalId: Long
        ): Option<PersistedSignal> {
            val sql = "SELECT * FROM $persistedSignalsTable WHERE workflow_id = ? AND owner = ? AND id = ?"
            return transactionManager.execute { conn ->
                try {
                    conn.prepareStatement(sql).use { ps ->
                        metrics.timer(METRICS_COMPONENT, "getPersistedSignal.latency").time().use { _ ->
                            ps.setString(1, workflowId)
                            ps.setString(2, owner)
                            ps.setLong(3, signalId)
                            val rs = ps.executeQuery()
                            if (!rs.next()) {
                                Option.none<PersistedSignal>()
                            } else {
                                Option.of(signalRsToModel(rs))
                            }
                        }
                    }
                } catch (e: SQLException) {
                    metrics
                        .counter(ImmutableMap.of("source", "getPersistedSignal"), METRICS_COMPONENT, "errors")
                        .inc()
                    throw InternalError(e)
                }
            }
        }

        @Throws(SQLException::class)
        private fun signalRsToModel(rs: ResultSet): PersistedSignal {
            val requestContext = rs.getString("request_context")
            return PersistedSignal(
                rs.getString("workflow_id"),
                rs.getString("signal_method"),
                PersistedSignal.Status.valueOf(rs.getString("status")),
                deserializeWorkflowInput(rs.getString("input")),
                if (requestContext != null) {
                    requestContextSerde.deserialize(requestContext.toByteArray(StandardCharsets.UTF_8))
                } else {
                    null
                },
                rs.getString("error"),
                rs.getLong("id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
            )
        }

        override fun storeActionCheckpoints(
            workflowId: String,
            checkpoints: List<ActionCheckpoint>
        ): List<ActionCheckpoint> {
            val now = clock.instant()
            val sql =
                (
                    "INSERT INTO $actionCheckpointsTable (owner, workflow_id, action_class, action_method, " +
                        "iteration, execution_start_time, execution_end_time, is_success, result, " +
                        "result_is_async, error, is_transient, created_at, input, checkpoint_name) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                )
            transactionManager.execute<Any?> { conn ->
                try {
                    conn.prepareStatement(sql).use { ps ->
                        metrics.timer(METRICS_COMPONENT, "storeActionCheckpoints").time().use { _ ->
                            for (checkpoint in checkpoints) {
                                var i = 0
                                ps.setString(++i, owner)
                                ps.setString(++i, workflowId)
                                ps.setString(++i, checkpoint.checkpointTag.actionClass.name)
                                ps.setString(++i, checkpoint.checkpointTag.actionMethod)
                                ps.setLong(++i, checkpoint.checkpointTag.iteration.toLong())
                                ps.setTimestamp(++i, Timestamp.from(checkpoint.executionStartTime))
                                ps.setTimestamp(
                                    ++i,
                                    if (checkpoint.executionEndTime != null) {
                                        Timestamp.from(checkpoint.executionEndTime)
                                    } else {
                                        null
                                    }
                                )
                                ps.setBoolean(++i, checkpoint.isSuccessful)
                                ps.setString(
                                    ++i,
                                    if (checkpoint.isSuccessful) {
                                        serdeStrategy.serialize(checkpoint.resultOrError)
                                    } else {
                                        null
                                    }
                                )
                                ps.setBoolean(++i, checkpoint.isResultIsAsync)
                                ps.setString(
                                    ++i,
                                    if (!checkpoint.isSuccessful) {
                                        serdeStrategy.serialize(checkpoint.resultOrError)
                                    } else {
                                        null
                                    }
                                )
                                ps.setBoolean(++i, checkpoint.isTransient)
                                ps.setTimestamp(++i, Timestamp.from(now))
                                ps.setString(++i, serializeWorkflowInput(checkpoint.input))
                                ps.setString(++i, checkpoint.checkpointTag.checkpointName)
                                ps.addBatch()
                            }
                            val counts = ps.executeBatch()
                            if (counts.size != checkpoints.size()) {
                                throw InternalError("failed to insert all action checkpoints")
                            }
                            // Check that all int in counts are exactly 1 (i.e. all inserts were successful) else
                            // fail
                            if (java.util.Arrays.stream(counts).anyMatch { it != 1 }) {
                                throw InternalError("one or more checkpoints failed to insert")
                            }
                        }
                    }
                } catch (e: SQLException) {
                    metrics
                        .counter(
                            ImmutableMap.of("source", "storeActionCheckpoints"),
                            METRICS_COMPONENT,
                            "errors"
                        )
                        .inc()
                    throw InternalError(e)
                }
                null
            }
            return checkpoints
        }

        override fun updateWorkflowAndStoreCheckpointsAndTimers(
            request: WorkflowUpdateRequest,
            checkpoints: List<ActionCheckpoint>,
            timers: List<Timer>
        ): Tuple3<WorkflowInstance, List<ActionCheckpoint>, List<Timer>> {
            return transactionManager.execute { conn ->
                // Update workflow
                val updatedInstance = updateWorkflow(request)
                // Store action checkpoints
                val newCheckpoints = storeActionCheckpoints(updatedInstance.workflowId, checkpoints)
                // Store timers
                val createdTimers = java.util.ArrayList<Timer>()
                for (timer in timers) {
                    val existingTimer = getTimer(timer.workflowId, timer.id)
                    if (existingTimer.isEmpty) {
                        val newTimer =
                            createTimer(
                                TimerCreationRequest.builder()
                                    .workflowId(timer.workflowId)
                                    .timerId(timer.id)
                                    .duration(timer.duration)
                                    .expiresAt(timer.expiresAt)
                                    .build()
                            )
                        // createTimer always inserts an ACTIVE row. A dirty timer can already be in a
                        // terminal state — e.g. a waitUntil whose condition was met before it ever
                        // suspended cancels its timer within the same execution — so transition the
                        // freshly created timer to that status; otherwise it would be stuck ACTIVE with
                        // no later update to correct it.
                        if (newTimer.status != timer.status &&
                            newTimer.status.canTransitionTo(timer.status)
                        ) {
                            updateTimer(timer.workflowId, timer.id, timer.status, newTimer.version)
                            createdTimers.add(timer)
                        } else {
                            createdTimers.add(newTimer)
                        }
                    } else {
                        // Update timer's status
                        if (existingTimer.get().status.canTransitionTo(timer.status)) {
                            if (updateTimer(
                                    timer.workflowId,
                                    timer.id,
                                    timer.status,
                                    existingTimer.get().version
                                )
                            ) {
                                createdTimers.add(timer)
                            }
                        } else {
                            log.warn(
                                "timer {} cannot transition to status {}",
                                existingTimer.get(),
                                timer.status
                            )
                        }
                    }
                }
                Tuple3(updatedInstance, newCheckpoints, List.ofAll(createdTimers))
            }
        }

        override fun createTimer(request: TimerCreationRequest): Timer {
            val sql =
                (
                    "INSERT INTO $timersTable (owner, timer_id, workflow_id, expires_at, status," +
                        " duration_in_secs, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?," +
                        " ?)"
                )
            val now = clock.instant()
            val newTimer =
                com.airbnb.skipper.Timer(
                    request.workflowId,
                    request.timerId,
                    now,
                    request.duration,
                    request.expiresAt,
                    com.airbnb.skipper.Timer.Status.ACTIVE,
                    1
                )
            transactionManager.execute<Any?> { conn ->
                try {
                    conn.prepareStatement(sql).use { ps ->
                        metrics.timer(METRICS_COMPONENT, "createTimer").time().use { _ ->
                            var i = 0
                            ps.setString(++i, owner)
                            ps.setString(++i, newTimer.id)
                            ps.setString(++i, newTimer.workflowId)
                            ps.setTimestamp(++i, Timestamp.from(newTimer.expiresAt))
                            ps.setString(++i, newTimer.status.name)
                            ps.setInt(++i, newTimer.duration.seconds.toInt())
                            ps.setTimestamp(++i, Timestamp.from(newTimer.createdAt))
                            ps.setTimestamp(++i, Timestamp.from(newTimer.createdAt))
                            ps.setInt(++i, newTimer.version)
                            val insertedRows = ps.executeUpdate()
                            if (insertedRows != 1) {
                                throw InternalError("failed to insert timer")
                            }
                        }
                    }
                } catch (e: SQLException) {
                    if (isUniqueConstraintViolation(e)) {
                        throw EntityAlreadyExists()
                    }
                    metrics
                        .counter(ImmutableMap.of("source", "createTimer"), METRICS_COMPONENT, "errors")
                        .inc()
                    throw InternalError(e)
                }
                null
            }
            return newTimer
        }

        override fun expireTimer(
            workflowId: String,
            timerId: String
        ): Boolean {
            return updateTimer(workflowId, timerId, Timer.Status.EXPIRED, null)
        }

        override fun cancelTimer(
            workflowId: String,
            timerId: String
        ): Boolean {
            return updateTimer(workflowId, timerId, Timer.Status.CANCELLED, null)
        }

        fun updateTimer(
            workflowId: String,
            timerId: String,
            newStatus: Timer.Status,
            version: Int?
        ): Boolean {
            val updateSql =
                (
                    "UPDATE $timersTable SET status = ?, updated_at = ?, version = version + 1 " +
                        "WHERE owner = ? AND workflow_id = ? AND timer_id = ? AND version = ?"
                )
            val now = clock.instant()
            return transactionManager.execute { conn ->
                try {
                    conn.prepareStatement(updateSql).use { ps ->
                        metrics.timer(METRICS_COMPONENT, "updateTimer").time().use { _ ->
                            val effectiveVersion: Int
                            if (version == null) {
                                val timerData = fetchTimerVersionAndStatus(conn, workflowId, timerId)
                                if (timerData.isEmpty) {
                                    throw IllegalArgumentException(
                                        "Timer not found for workflowId: $workflowId and timerId: $timerId"
                                    )
                                }
                                val data = timerData.get()
                                effectiveVersion = data._1
                                if (data._2 == newStatus) {
                                    return@use true
                                } else if (!data._2.canTransitionTo(newStatus)) {
                                    return@use false
                                }
                            } else {
                                effectiveVersion = version
                            }
                            var i = 0
                            ps.setString(++i, newStatus.name)
                            ps.setTimestamp(++i, Timestamp.from(now))
                            // Conditional params
                            ps.setString(++i, owner)
                            ps.setString(++i, workflowId)
                            ps.setString(++i, timerId)
                            ps.setInt(++i, effectiveVersion)
                            val updatedRows = ps.executeUpdate()
                            updatedRows == 1
                        }
                    }
                } catch (e: SQLException) {
                    metrics
                        .counter(ImmutableMap.of("source", "updateTimer"), METRICS_COMPONENT, "errors")
                        .inc()
                    throw InternalError(e)
                }
            }
        }

        @Throws(SQLException::class)
        private fun fetchTimerVersionAndStatus(
            conn: Connection,
            workflowId: String,
            timerId: String
        ): Option<Tuple2<Int, Timer.Status>> {
            val fetchSql =
                (
                    "SELECT version, status FROM $timersTable WHERE owner = ? AND workflow_id = ? AND timer_id" +
                        " = ?"
                )
            conn.prepareStatement(fetchSql).use { fetchPs ->
                fetchPs.setString(1, owner)
                fetchPs.setString(2, workflowId)
                fetchPs.setString(3, timerId)
                val rs = fetchPs.executeQuery()
                if (!rs.next()) {
                    return Option.none()
                }
                val version = rs.getInt("version")
                val status = Timer.Status.valueOf(rs.getString("status"))
                return Option.of(Tuple2(version, status))
            }
        }

        override fun getTimers(workflowId: String): List<Timer> {
            val sql =
                "SELECT * FROM $timersTable WHERE owner = ? AND workflow_id = ? " + "ORDER BY timer_id ASC"
            return transactionManager.execute { conn ->
                try {
                    conn.prepareStatement(sql).use { ps ->
                        metrics.timer(METRICS_COMPONENT, "getTimers").time().use { _ ->
                            ps.setString(1, owner)
                            ps.setString(2, workflowId)
                            val rs = ps.executeQuery()
                            val timers = java.util.ArrayList<Timer>()
                            while (rs.next()) {
                                mapResultSetToTimer(rs).forEach { timers.add(it) }
                            }
                            List.ofAll(timers)
                        }
                    }
                } catch (e: SQLException) {
                    metrics
                        .counter(ImmutableMap.of("source", "getTimers"), METRICS_COMPONENT, "errors")
                        .inc()
                    throw InternalError(e)
                }
            }
        }

        override fun getTimer(
            workflowId: String,
            timerId: String
        ): Option<Timer> {
            val sql = "SELECT * FROM $timersTable WHERE owner = ? AND workflow_id = ? AND timer_id = ?"
            return transactionManager.execute { conn ->
                try {
                    conn.prepareStatement(sql).use { ps ->
                        metrics.timer(METRICS_COMPONENT, "getTimer").time().use { _ ->
                            ps.setString(1, owner)
                            ps.setString(2, workflowId)
                            ps.setString(3, timerId)
                            val rs = ps.executeQuery()
                            if (!rs.next()) {
                                Option.none<Timer>()
                            } else {
                                mapResultSetToTimer(rs)
                            }
                        }
                    }
                } catch (e: SQLException) {
                    metrics
                        .counter(ImmutableMap.of("source", "getTimer"), METRICS_COMPONENT, "errors")
                        .inc()
                    throw InternalError(e)
                }
            }
        }

        @Throws(SQLException::class)
        private fun mapResultSetToTimer(rs: ResultSet): Option<Timer> {
            val timer =
                Timer(
                    rs.getString("workflow_id"),
                    rs.getString("timer_id"),
                    rs.getTimestamp("created_at").toInstant(),
                    Duration.ofSeconds(rs.getInt("duration_in_secs").toLong()),
                    rs.getTimestamp("expires_at").toInstant(),
                    Timer.Status.valueOf(rs.getString("status")),
                    rs.getInt("version")
                )
            return Option.of(timer)
        }

        private fun serializeWorkflowInput(input: Any?): String? {
            var serializedInput: String? = null
            if (input != null) {
                serializedInput = serdeStrategy.serialize(input)
            }
            return serializedInput
        }

        private fun serializeWorkflowState(instanceState: io.vavr.collection.Map<String, Any?>): String {
            try {
                val stateData = instanceState.mapValues { serdeStrategy.serialize(it) }.toJavaMap()
                return simpleSerde.objectMapper.writeValueAsString(stateData)
            } catch (
                @Suppress("TooGenericExceptionCaught") ex: Throwable
            ) {
                throw SneakyThrow.sneakyThrow(ex)
            }
        }

        override fun deleteWorkflow(workflowId: String) {
            transactionManager.execute<Any?> { conn ->
                try {
                    metrics.timer(METRICS_COMPONENT, "deleteWorkflow").time().use { _ ->
                        // Verify workflow exists
                        val workflowOpt = getWorkflow(workflowId)
                        if (workflowOpt.isEmpty) {
                            throw IllegalArgumentException("Workflow not found: $workflowId")
                        }
                        // 1. Delete action checkpoints
                        val deleteCheckpointsSql =
                            "DELETE FROM $actionCheckpointsTable WHERE owner = ? AND workflow_id = ?"
                        conn.prepareStatement(deleteCheckpointsSql).use { ps ->
                            ps.setString(1, owner)
                            ps.setString(2, workflowId)
                            ps.executeUpdate()
                        }
                        // 2. Delete timers
                        val deleteTimersSql = "DELETE FROM $timersTable WHERE owner = ? AND workflow_id = ?"
                        conn.prepareStatement(deleteTimersSql).use { ps ->
                            ps.setString(1, owner)
                            ps.setString(2, workflowId)
                            ps.executeUpdate()
                        }
                        // 3. Delete persisted signals
                        val deleteSignalsSql =
                            "DELETE FROM $persistedSignalsTable WHERE owner = ? AND workflow_id = ?"
                        conn.prepareStatement(deleteSignalsSql).use { ps ->
                            ps.setString(1, owner)
                            ps.setString(2, workflowId)
                            ps.executeUpdate()
                        }
                        // 4. Delete workflow instance
                        val deleteWorkflowSql =
                            "DELETE FROM $workflowInstancesTable WHERE owner = ? AND workflow_id = ?"
                        conn.prepareStatement(deleteWorkflowSql).use { ps ->
                            ps.setString(1, owner)
                            ps.setString(2, workflowId)
                            val deletedRows = ps.executeUpdate()
                            if (deletedRows != 1) {
                                throw IllegalStateException("Failed to delete workflow: $workflowId")
                            }
                        }
                    }
                } catch (e: SQLException) {
                    metrics
                        .counter(ImmutableMap.of("source", "deleteWorkflow"), METRICS_COMPONENT, "errors")
                        .inc()
                    throw InternalError(e)
                }
                null
            }
        }

        override fun resetWorkflowFromError(workflowId: String): Tuple2<WorkflowInstance, Option<ActionCheckpoint>> {
            return transactionManager.execute { conn ->
                try {
                    metrics.timer(METRICS_COMPONENT, "resetWorkflowFromError").time().use { _ ->
                        // First, verify workflow exists and is in ERROR status
                        val workflowOpt = getWorkflow(workflowId)
                        if (workflowOpt.isEmpty) {
                            throw IllegalArgumentException("Workflow not found: $workflowId")
                        }
                        val workflow = workflowOpt.get()
                        if (workflow.status != WorkflowInstance.Status.ERROR) {
                            throw IllegalStateException(
                                "Workflow " +
                                    workflowId +
                                    " is not in ERROR status. Current status: " +
                                    workflow.status
                            )
                        }
                        // First, get the latest non-transient error checkpoint before deleting
                        var removedCheckpoint: ActionCheckpoint? = null
                        val selectCheckpointSql =
                            (
                                "SELECT * FROM $actionCheckpointsTable WHERE owner = ? AND workflow_id = ? AND" +
                                    " is_transient = 0 AND is_success = 0 ORDER BY" +
                                    " execution_start_time DESC LIMIT 1"
                            )
                        var idToRemove: Long? = null
                        conn.prepareStatement(selectCheckpointSql).use { selectPs ->
                            selectPs.setString(1, owner)
                            selectPs.setString(2, workflowId)
                            val rs = selectPs.executeQuery()
                            if (rs.next()) {
                                idToRemove = rs.getLong("id")
                                removedCheckpoint = checkpointRsToModel(rs)
                            }
                        }
                        // Note: It's okay if no error checkpoint exists - we still reset the workflow
                        if (idToRemove != null) {
                            val deleteCheckpointSql = "DELETE FROM $actionCheckpointsTable WHERE id = ?"
                            conn.prepareStatement(deleteCheckpointSql).use { deletePs ->
                                deletePs.setLong(1, idToRemove!!)
                                deletePs.executeUpdate()
                            }
                        }
                        // Update workflow status to RUNNING and reset result
                        val updateWorkflowSql =
                            (
                                "UPDATE $workflowInstancesTable SET status = ?, result_value = NULL," +
                                    " result_error = NULL, result_is_error = 0, result_is_done = 0," +
                                    " result_is_async = 0, updated_at = ?, version = version + 1 WHERE owner = ?" +
                                    " AND workflow_id = ? AND version = ? AND status = ?"
                            )
                        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
                        val newResult = CompletableFuture<Any?>()
                        conn.prepareStatement(updateWorkflowSql).use { updatePs ->
                            var i = 0
                            updatePs.setString(++i, WorkflowInstance.Status.RUNNING.name)
                            updatePs.setTimestamp(++i, Timestamp.from(now))
                            // WHERE conditions
                            updatePs.setString(++i, owner)
                            updatePs.setString(++i, workflowId)
                            updatePs.setInt(++i, workflow.version)
                            updatePs.setString(++i, WorkflowInstance.Status.ERROR.name)
                            val updatedRows = updatePs.executeUpdate()
                            if (updatedRows == 0) {
                                throw OptimisticLockingError(
                                    "Failed to update workflow " +
                                        workflowId +
                                        ". It may have been modified by another process."
                                )
                            }
                        }
                        // Return updated workflow instance and the removed checkpoint (if any)
                        val updatedWorkflow = getWorkflow(workflowId).get()
                        Tuple2(updatedWorkflow, Option.of(removedCheckpoint))
                    }
                } catch (e: SQLException) {
                    metrics
                        .counter(
                            ImmutableMap.of("source", "resetWorkflowFromError"),
                            METRICS_COMPONENT,
                            "errors"
                        )
                        .inc()
                    throw InternalError(e)
                }
            }
        }

        override fun rewindWorkflow(
            workflowId: String,
            pivot: CheckpointTag
        ): Tuple2<WorkflowInstance, List<ActionCheckpoint>> {
            return transactionManager.execute { conn ->
                try {
                    metrics.timer(METRICS_COMPONENT, "rewindWorkflow").time().use { _ ->
                        // Verify the workflow exists. Unlike resetWorkflowFromError, there is no status
                        // precondition: rewinding is allowed regardless of whether the workflow is currently in
                        // a terminal status or not.
                        val workflowOpt = getWorkflow(workflowId)
                        if (workflowOpt.isEmpty) {
                            throw IllegalArgumentException("Workflow not found: $workflowId")
                        }
                        val workflow = workflowOpt.get()
                        // Locate the pivot checkpoint. Matching follows CheckpointTag#matches: name-based when
                        // the pivot carries a checkpoint name, positional (action class + method + iteration)
                        // otherwise.
                        var pivotId: Long? = null
                        if (pivot.checkpointName != null) {
                            val selectSql =
                                (
                                    "SELECT id FROM $actionCheckpointsTable WHERE owner = ? AND workflow_id = ?" +
                                        " AND checkpoint_name = ? ORDER BY id ASC LIMIT 1"
                                )
                            conn.prepareStatement(selectSql).use { ps ->
                                ps.setString(1, owner)
                                ps.setString(2, workflowId)
                                ps.setString(3, pivot.checkpointName)
                                val rs = ps.executeQuery()
                                if (rs.next()) {
                                    pivotId = rs.getLong("id")
                                }
                            }
                        } else {
                            val selectSql =
                                (
                                    "SELECT id FROM $actionCheckpointsTable WHERE owner = ? AND workflow_id = ?" +
                                        " AND action_class = ? AND action_method = ? AND iteration = ?" +
                                        " ORDER BY id ASC LIMIT 1"
                                )
                            conn.prepareStatement(selectSql).use { ps ->
                                ps.setString(1, owner)
                                ps.setString(2, workflowId)
                                ps.setString(3, pivot.actionClass.name)
                                ps.setString(4, pivot.actionMethod)
                                ps.setLong(5, pivot.iteration.toLong())
                                val rs = ps.executeQuery()
                                if (rs.next()) {
                                    pivotId = rs.getLong("id")
                                }
                            }
                        }
                        if (pivotId == null) {
                            throw IllegalArgumentException(
                                "Pivot checkpoint not found for workflow $workflowId: $pivot"
                            )
                        }
                        // Read the checkpoints that will be deleted (the pivot and everything after it) so we
                        // can return them to the caller, ordered by execution order.
                        val deletedCheckpoints = java.util.ArrayList<ActionCheckpoint>()
                        val selectDeletedSql =
                            (
                                "SELECT * FROM $actionCheckpointsTable WHERE owner = ? AND workflow_id = ?" +
                                    " AND id >= ? ORDER BY id ASC"
                            )
                        conn.prepareStatement(selectDeletedSql).use { ps ->
                            ps.setString(1, owner)
                            ps.setString(2, workflowId)
                            ps.setLong(3, pivotId!!)
                            val rs = ps.executeQuery()
                            while (rs.next()) {
                                deletedCheckpoints.add(checkpointRsToModel(rs))
                            }
                        }
                        // Delete the pivot checkpoint and every checkpoint that happened after it.
                        val deleteSql =
                            (
                                "DELETE FROM $actionCheckpointsTable WHERE owner = ? AND workflow_id = ?" +
                                    " AND id >= ?"
                            )
                        conn.prepareStatement(deleteSql).use { ps ->
                            ps.setString(1, owner)
                            ps.setString(2, workflowId)
                            ps.setLong(3, pivotId!!)
                            ps.executeUpdate()
                        }
                        // Set the workflow status to RUNNING and reset its result. Optimistic locking is on the
                        // version only (no status precondition) so terminal workflows can be rewound.
                        val updateWorkflowSql =
                            (
                                "UPDATE $workflowInstancesTable SET status = ?, result_value = NULL," +
                                    " result_error = NULL, result_is_error = 0, result_is_done = 0," +
                                    " result_is_async = 0, updated_at = ?, version = version + 1 WHERE owner = ?" +
                                    " AND workflow_id = ? AND version = ?"
                            )
                        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
                        conn.prepareStatement(updateWorkflowSql).use { updatePs ->
                            var i = 0
                            updatePs.setString(++i, WorkflowInstance.Status.RUNNING.name)
                            updatePs.setTimestamp(++i, Timestamp.from(now))
                            // WHERE conditions
                            updatePs.setString(++i, owner)
                            updatePs.setString(++i, workflowId)
                            updatePs.setInt(++i, workflow.version)
                            val updatedRows = updatePs.executeUpdate()
                            if (updatedRows == 0) {
                                throw OptimisticLockingError(
                                    "Failed to update workflow " +
                                        workflowId +
                                        ". It may have been modified by another process."
                                )
                            }
                        }
                        val updatedWorkflow = getWorkflow(workflowId).get()
                        Tuple2(updatedWorkflow, List.ofAll(deletedCheckpoints))
                    }
                } catch (e: SQLException) {
                    metrics
                        .counter(ImmutableMap.of("source", "rewindWorkflow"), METRICS_COMPONENT, "errors")
                        .inc()
                    throw InternalError(e)
                }
            }
        }

        /**
         * Builds a [SqliteWorkflowStore] from a [SkipperConfig].
         *
         * With no [path], the database is the one described by [SkipperConfig.sqliteDataSource], or the
         * ephemeral shared in-memory default when that is `null`. Passing a [path] (for example
         * `"skipper.db"`) opens a durable on-disk database at that file instead; use the same path for
         * [com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler.Factory] so both live in one file.
         */
        class Factory
            @JvmOverloads
            constructor(
                private val path: String? = null,
            ) : ComponentFactory<WorkflowStore> {
                override fun create(config: SkipperConfig): WorkflowStore {
                    val dataSource = path?.let { JdbcTransactionManager.SqliteFactory.fileDataSource(it) }
                    return SqliteWorkflowStore(
                        JdbcTransactionManager.SqliteFactory(dataSource).create(config),
                        config.metrics.create(config),
                        config.serde.create(config),
                        config.simplePojoSerde.create(config),
                        config.requestContextSerde.create(config),
                        config.utcClock,
                        config.tenant,
                        config.tablePrefix
                    )
                }
            }

        companion object {
            private val log = org.slf4j.LoggerFactory.getLogger(SqliteWorkflowStore::class.java)

            private const val METRICS_COMPONENT = "sqliteWorkflowStore"
            private const val METRIC_PAYLOAD_SIZE_BYTES = "payload_size_bytes"
            private const val OPERATION_TAG = "operation"
            private const val FIELD_TAG = "field"
            private const val OPERATION_CREATE = "create"
            private const val OPERATION_UPDATE = "update"
            private const val FIELD_INPUT = "input"
            private const val FIELD_STATE = "state"
            private const val FIELD_RESULT = "result"

            /** SQLite primary result code for a constraint violation (`SQLITE_CONSTRAINT`). */
            private const val SQLITE_CONSTRAINT = 19

            /**
             * Returns `true` if the given [SQLException] represents a SQLite uniqueness/primary-key
             * constraint violation (`SQLITE_CONSTRAINT`, primary result code [SQLITE_CONSTRAINT]).
             *
             * This is the SQLite analogue of MySQL's `instanceof SQLIntegrityConstraintViolationException`.
             * The xerial driver throws [SQLiteException] whose [SQLiteException.getResultCode] carries the
             * (possibly extended) result code, e.g. `SQLITE_CONSTRAINT_PRIMARYKEY`. Extended codes are
             * `primary | (sub << 8)`, so masking the low byte recovers the primary `SQLITE_CONSTRAINT`
             * code. We additionally fall back to [SQLException.getErrorCode] for robustness.
             */
            private fun isUniqueConstraintViolation(e: SQLException): Boolean {
                if (e is SQLiteException) {
                    val resultCode = e.resultCode.code
                    if ((resultCode and 0xFF) == SQLITE_CONSTRAINT) {
                        return true
                    }
                }
                return (e.errorCode and 0xFF) == SQLITE_CONSTRAINT
            }
        }
    }
