package com.airbnb.skipper.admin

import com.airbnb.skipper.Timer
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.WorkflowsService
import com.airbnb.skipper.internal.CheckpointTag
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.internal.api.PersistedSignal
import com.airbnb.skipper.internal.common.SneakyThrow
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.storage.WorkflowSearchFilter
import com.airbnb.skipper.internal.storage.WorkflowSortDirection
import com.airbnb.skipper.internal.storage.WorkflowSortField
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategy
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.cfg.MapperConfig
import com.fasterxml.jackson.databind.introspect.AnnotatedField
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.google.common.collect.ImmutableList
import io.vavr.control.Option
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.ArrayList
import java.util.Base64
import java.util.Optional
import java.util.stream.Collectors
import javax.inject.Inject
import javax.ws.rs.Consumes
import javax.ws.rs.DefaultValue
import javax.ws.rs.GET
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.Produces
import javax.ws.rs.QueryParam
import javax.ws.rs.WebApplicationException
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

@Path("/skipper/admin")
class AdminResource
    @Inject
    constructor(
        engine: WorkflowStore,
        scheduler: Scheduler,
        skipperEngine: SkipperEngine,
        workflowsService: WorkflowsService,
    ) {
        // Note: the constructor parameter `engine` is assigned to the `store` field, preserving the
        // original Java signature where the first injected parameter was named `engine`.
        private val store: WorkflowStore = engine

        // Every JSON endpoint below serializes its own response and returns it as a String entity, so
        // the wire format is fixed by this resource rather than by whatever JSON provider (and
        // ObjectMapper configuration) the host's JAX-RS stack happens to have. The bundled index.html
        // depends on this format: snake_case keys and ISO-8601 timestamps. A host needs no JSON
        // MessageBodyWriter to serve the admin UI.
        private val objectMapper: ObjectMapper =
            ObjectMapper()
                .registerModule(JavaTimeModule())
                .registerModule(Jdk8Module())
                .setPropertyNamingStrategy(SkipperSnakeCase())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
        private val scheduler: Scheduler = scheduler
        private val skipperEngine: SkipperEngine = skipperEngine
        private val workflowsService: WorkflowsService = workflowsService

        @GET
        @Path("/")
        @Produces(MediaType.TEXT_HTML)
        fun index(): Response {
            // The admin UI is a static single-page app served as-is; it fetches all data at runtime
            // from the JSON endpoints below. It is shipped as a classpath resource and returned
            // directly so this resource stays pure JAX-RS (no dependency on a server-side view/template
            // renderer such as Dropwizard Views + FreeMarker).
            val html: InputStream =
                AdminResource::class.java.getResourceAsStream("index.html")
                    ?: throw WebApplicationException(
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity("Admin UI resource (index.html) not found on classpath")
                            .type(MediaType.TEXT_PLAIN)
                            .build(),
                    )
            return Response.ok(html, MediaType.TEXT_HTML).build()
        }

        @GET
        @Produces(MediaType.APPLICATION_JSON)
        @Path("/workflows/{id}")
        fun getWorkflowInstance(
            @PathParam("id") workflowInstanceId: String
        ): Response {
            val task: Option<Task<Any>> = scheduler.getTask(workflowInstanceId)
            val timers: List<Timer> = store.getTimers(workflowInstanceId).toJavaList()
            return json(
                WorkflowInstanceView(
                    store
                        .getWorkflow(workflowInstanceId)
                        .getOrElseThrow { IllegalArgumentException("Workflow not found") },
                    store.getActionCheckpoints(workflowInstanceId).asJava(),
                    if (task.isDefined) ImmutableList.of(task.get()) else ArrayList(),
                    timers,
                )
            )
        }

        @POST
        @Produces(MediaType.APPLICATION_JSON)
        @Path("/workflows/{id}/cancel")
        fun cancelWorkflowInstance(
            @PathParam("id") workflowInstanceId: String
        ): Response {
            try {
                val updatedInstance: WorkflowInstance =
                    skipperEngine.cancelWorkflow(workflowInstanceId, "Cancelled by admin")
                return json(WorkflowInstanceView(updatedInstance, ArrayList(), ArrayList(), ArrayList()))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable
            ) {
                throw mapError(e)
            }
        }

        @GET
        @Produces(MediaType.APPLICATION_JSON)
        @Path("/scheduler/dlq")
        fun getDeadLetterQueue(): Response {
            return json(TasksView(scheduler.getFailedTasks<Any>().toJavaList()))
        }

        /**
         * @param sortBy the [WorkflowSortField] to sort by, or absent for no sort. Sorting is opt-in
         *     because it is not index-backed and times out on owners with a large stuck-workflow
         *     backlog; the unsorted listing is still deterministic, just not chronological.
         * @param sortDirection the [WorkflowSortDirection] to apply to [sortBy]; ignored when [sortBy]
         *     is absent. Defaults to ascending.
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        @Path("/workflows/exhausted-retries")
        fun getWorkflowsWithExhaustedRetries(
            @QueryParam("sortBy") sortBy: String?,
            @QueryParam("sortDirection") @DefaultValue("ASC") sortDirection: String?,
        ): Response {
            return json(
                WorkflowsView(
                    store
                        .findWorkflowsWithExhaustedRetries(
                            DEFAULT_EXHAUSTED_RETRIES_LIMIT,
                            parseSortField(sortBy),
                            parseSortDirection(sortDirection),
                        )
                        .toJavaList(),
                )
            )
        }

        /** Parsed leniently by name so an unknown value yields a 400 rather than a JAX-RS 404. */
        private fun parseSortField(value: String?): WorkflowSortField? {
            if (value.isNullOrEmpty()) return null
            return try {
                WorkflowSortField.valueOf(value)
            } catch (e: IllegalArgumentException) {
                throw mapBadRequest(
                    // values() rather than entries: the OSS Gradle build pins Kotlin language
                    // version 1.8, which predates the enum-entries API.
                    "Invalid sortBy value: $value. Supported: " +
                        WorkflowSortField.values().joinToString(", "),
                )
            }
        }

        private fun parseSortDirection(value: String?): WorkflowSortDirection {
            if (value.isNullOrEmpty()) return WorkflowSortDirection.ASC
            return try {
                WorkflowSortDirection.valueOf(value)
            } catch (e: IllegalArgumentException) {
                throw mapBadRequest(
                    "Invalid sortDirection value: $value. Supported: " +
                        WorkflowSortDirection.values().joinToString(", "),
                )
            }
        }

        @GET
        @Produces(MediaType.APPLICATION_JSON)
        @Path("/dashboard/stats")
        fun getDashboardStats(): Response {
            // Sort order is irrelevant to a count, so never pay for the sort here.
            val exhaustedRetriesCount: Int =
                store
                    .findWorkflowsWithExhaustedRetries(
                        DASHBOARD_STATS_EXHAUSTED_RETRIES_LIMIT,
                        null,
                        WorkflowSortDirection.ASC,
                    )
                    .size()
            val dlqTasksCount: Int = scheduler.getFailedTasks<Any>().size()
            val schedulerBacklogCount: Long = scheduler.countBacklog()
            return json(DashboardStats(exhaustedRetriesCount, dlqTasksCount, schedulerBacklogCount))
        }

        @GET
        @Produces(MediaType.APPLICATION_JSON)
        @Path("/workflow-types")
        fun listWorkflowTypes(): Response {
            return json(
                store
                    .listDistinctWorkflowTypes()
                    .map { t -> WorkflowType(t._1(), t._2()) }
                    .toJavaList()
            )
        }

        @GET
        @Produces(MediaType.APPLICATION_JSON)
        @Path("/workflows")
        fun searchWorkflows(
            @QueryParam("entryPoint") entryPoints: List<String>?,
            @QueryParam("status") statuses: List<String>?,
            @QueryParam("createdAfter") createdAfter: String?,
            @QueryParam("createdBefore") createdBefore: String?,
            @QueryParam("parentWorkflowId") parentWorkflowId: String?,
            @QueryParam("limit") @DefaultValue("50") limit: Int,
            @QueryParam("cursor") cursor: String?,
        ): Response {
            val filter: WorkflowSearchFilter =
                parseSearchFilter(entryPoints, statuses, createdAfter, createdBefore, parentWorkflowId, cursor)
            val clampedLimit: Int = Math.min(Math.max(limit, MIN_SEARCH_LIMIT), MAX_SEARCH_LIMIT)
            var workflows: List<WorkflowInstance> = store.findWorkflows(filter, clampedLimit + 1).toJavaList()
            val hasMore: Boolean = workflows.size > clampedLimit
            if (hasMore) {
                workflows = workflows.subList(0, clampedLimit)
            }
            var nextCursor: String? = null
            if (hasMore && !workflows.isEmpty()) {
                val last: WorkflowInstance = workflows.get(workflows.size - 1)
                val lastCreatedAt = last.createdAt
                if (lastCreatedAt != null) {
                    nextCursor = encodeCursor(lastCreatedAt, last.workflowId)
                }
            }
            return json(WorkflowSearchResult(workflows, nextCursor))
        }

        private fun parseSearchFilter(
            entryPoints: List<String>?,
            statuses: List<String>?,
            createdAfter: String?,
            createdBefore: String?,
            parentWorkflowId: String?,
            cursor: String?,
        ): WorkflowSearchFilter {
            val filterBuilder: WorkflowSearchFilter.WorkflowSearchFilterBuilder = WorkflowSearchFilter.builder()
            if (entryPoints != null && !entryPoints.isEmpty()) {
                filterBuilder.workflowEntryPoints(
                    entryPoints.stream()
                        .map { ep ->
                            val parts = ep.split("::".toRegex(), limit = 2).toTypedArray()
                            WorkflowSearchFilter.WorkflowEntryPoint(parts[0], if (parts.size > 1) parts[1] else "")
                        }
                        .collect(Collectors.toList()),
                )
            }
            if (statuses != null && !statuses.isEmpty()) {
                try {
                    filterBuilder.statuses(
                        statuses.stream().map { WorkflowInstance.Status.valueOf(it) }.collect(Collectors.toList()),
                    )
                } catch (e: IllegalArgumentException) {
                    throw mapBadRequest("Invalid status value: " + e.message)
                }
            }
            if (createdAfter != null && !createdAfter.isEmpty()) {
                try {
                    filterBuilder.createdAfter(Instant.parse(createdAfter))
                } catch (e: DateTimeParseException) {
                    throw mapBadRequest("Invalid createdAfter format: $createdAfter")
                }
            }
            if (createdBefore != null && !createdBefore.isEmpty()) {
                try {
                    filterBuilder.createdBefore(Instant.parse(createdBefore))
                } catch (e: DateTimeParseException) {
                    throw mapBadRequest("Invalid createdBefore format: $createdBefore")
                }
            }
            if (parentWorkflowId != null && !parentWorkflowId.isEmpty()) {
                filterBuilder.parentWorkflowId(parentWorkflowId)
            }
            if (cursor != null && !cursor.isEmpty()) {
                try {
                    filterBuilder.cursor(decodeCursor(cursor))
                } catch (e: Exception) {
                    throw mapBadRequest("Invalid cursor: " + e.message)
                }
            }
            return filterBuilder.build()
        }

        @POST
        @Path("/scheduler/{id}/requeue")
        fun requeueTask(
            @PathParam("id") taskId: String
        ) {
            try {
                scheduler.requeueFailedTask(taskId)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable
            ) {
                throw mapError(e)
            }
        }

        @POST
        @Path("/scheduler/{id}/dequeue")
        fun dequeueTask(
            @PathParam("id") taskId: String
        ) {
            try {
                val task: Task<Any> =
                    scheduler
                        .getTask<Any>(taskId)
                        .getOrElseThrow { IllegalArgumentException("Task not found") }
                scheduler.remove(task)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable
            ) {
                throw mapError(e)
            }
        }

        @POST
        @Produces(MediaType.APPLICATION_JSON)
        @Consumes(MediaType.APPLICATION_JSON)
        @Path("/workflows/bulk-cancel")
        fun bulkCancelWorkflows(workflowIds: List<String>): Response {
            val successful: List<String> =
                workflowsService.cancelWorkflows(workflowIds, "Cancelled by bulk admin operation")
            val failed: MutableList<String> = ArrayList(workflowIds)
            failed.removeAll(successful)
            return json(BulkOperationResult(successful, failed))
        }

        @POST
        @Produces(MediaType.APPLICATION_JSON)
        @Consumes(MediaType.APPLICATION_JSON)
        @Path("/workflows/bulk-retry")
        fun bulkRetryWorkflows(workflowIds: List<String>): Response {
            val reExecuteResults: Map<String, Optional<Throwable>> =
                workflowsService.reExecuteWorkflows(workflowIds)
            val successful: List<String> =
                reExecuteResults.entries.stream()
                    .filter { entry -> !entry.value.isPresent }
                    .map { entry -> entry.key }
                    .collect(Collectors.toList())
            val failed: List<String> =
                reExecuteResults.entries.stream()
                    .filter { entry -> entry.value.isPresent }
                    .map { entry -> entry.key }
                    .collect(Collectors.toList())
            return json(BulkOperationResult(successful, failed))
        }

        @POST
        @Produces(MediaType.APPLICATION_JSON)
        @Consumes(MediaType.APPLICATION_JSON)
        @Path("/scheduler/bulk-redrive")
        fun bulkRedriveTasks(taskIds: List<String>): Response {
            // Get the tasks from the scheduler
            val tasksToRedrive: MutableList<Task<Any>> = ArrayList()
            val failed: MutableList<String> = ArrayList()
            for (taskId in taskIds) {
                val task: Option<Task<Any>> = scheduler.getTask(taskId)
                if (task.isDefined) {
                    tasksToRedrive.add(task.get())
                } else {
                    failed.add(taskId)
                }
            }
            val successful: List<String> = workflowsService.redriveDeadLetterQueue(tasksToRedrive)
            // Add any tasks that couldn't be found to failed list
            val allRequestedIds: MutableList<String> = ArrayList(taskIds)
            allRequestedIds.removeAll(successful)
            failed.addAll(
                allRequestedIds.stream().filter { id -> !failed.contains(id) }.collect(Collectors.toList()),
            )
            return json(BulkOperationResult(successful, failed))
        }

        @POST
        @Produces(MediaType.APPLICATION_JSON)
        @Consumes(MediaType.APPLICATION_JSON)
        @Path("/scheduler/bulk-remove")
        fun bulkRemoveTasks(taskIds: List<String>): Response {
            // Get the tasks from the scheduler
            val tasksToRemove: MutableList<Task<Any>> = ArrayList()
            val failed: MutableList<String> = ArrayList()
            for (taskId in taskIds) {
                val task: Option<Task<Any>> = scheduler.getTask(taskId)
                if (task.isDefined) {
                    tasksToRemove.add(task.get())
                } else {
                    failed.add(taskId)
                }
            }
            val successful: List<String> = workflowsService.removeFromDeadLetterQueue(tasksToRemove)
            // Add any tasks that couldn't be found to failed list
            val allRequestedIds: MutableList<String> = ArrayList(taskIds)
            allRequestedIds.removeAll(successful)
            failed.addAll(
                allRequestedIds.stream().filter { id -> !failed.contains(id) }.collect(Collectors.toList()),
            )
            return json(BulkOperationResult(successful, failed))
        }

        @POST
        @Produces(MediaType.APPLICATION_JSON)
        @Consumes(MediaType.APPLICATION_JSON)
        @Path("/scheduler/bulk-cancel-workflows")
        fun bulkCancelWorkflowsFromDLQ(taskIds: List<String>): Response {
            // Filter to only WORKFLOW type tasks
            val workflowIds: MutableList<String> = ArrayList()
            val ignoredTasks: MutableList<String> = ArrayList()
            for (taskId in taskIds) {
                val task: Option<Task<Any>> = scheduler.getTask(taskId)
                if (task.isDefined && task.get().type == Task.Type.WORKFLOW) {
                    workflowIds.add(taskId)
                } else {
                    ignoredTasks.add(taskId)
                }
            }
            val successful: List<String> =
                workflowsService.cancelWorkflows(workflowIds, "Cancelled via DLQ bulk admin operation")
            val failed: MutableList<String> = ArrayList(workflowIds)
            failed.removeAll(successful)
            // Add ignored tasks to failed list for reporting
            failed.addAll(ignoredTasks)
            return json(BulkOperationResult(successful, failed))
        }

        @POST
        @Path("/scheduler/{id}/cancel-workflow")
        fun cancelWorkflowFromTask(
            @PathParam("id") taskId: String
        ) {
            try {
                // For WORKFLOW type tasks, cancel the workflow which will also remove the task
                workflowsService.cancelWorkflows(
                    java.util.Arrays.asList(taskId),
                    "Cancelled via DLQ admin interface",
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable
            ) {
                throw mapError(e)
            }
        }

        @POST
        @Produces(MediaType.APPLICATION_JSON)
        @Path("/workflows/{id}/delete")
        fun deleteWorkflowInstance(
            @PathParam("id") workflowInstanceId: String
        ): Response {
            try {
                workflowsService.deleteWorkflow(workflowInstanceId)
                return Response.ok().build()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable
            ) {
                throw mapError(e)
            }
        }

        @POST
        @Produces(MediaType.APPLICATION_JSON)
        @Path("/workflows/{id}/reset-error")
        fun resetWorkflowFromError(
            @PathParam("id") workflowInstanceId: String
        ): Response {
            val workflowIds: List<String> =
                workflowsService.resetWorkflowsFromError(
                    io.vavr.collection.List.of(workflowInstanceId).toJavaList(),
                )
            if (workflowIds.isEmpty()) {
                throw mapError(IllegalArgumentException("Workflow not found or not in error state"))
            }
            // Get the latest workflow instance after reset
            val resetWorkflow: WorkflowInstance =
                store
                    .getWorkflow(workflowInstanceId)
                    .getOrElseThrow { IllegalArgumentException("Workflow not found after reset") }
            // Return updated workflow view
            return json(WorkflowInstanceView(resetWorkflow, ArrayList(), ArrayList(), ArrayList()))
        }

        @GET
        @Produces(MediaType.APPLICATION_JSON)
        @Path("/workflows/{id}/signals")
        fun getPersistedSignals(
            @PathParam("id") workflowInstanceId: String
        ): Response {
            val signals: List<PersistedSignalView> =
                store.getPersistedSignals(workflowInstanceId).map { PersistedSignalView.from(it) }.toJavaList()
            return json(PersistedSignalsView(signals))
        }

        @POST
        @Produces(MediaType.APPLICATION_JSON)
        @Path("/workflows/{id}/signals/{signalId}/replay")
        fun replayPersistedSignal(
            @PathParam("id") workflowInstanceId: String,
            @PathParam("signalId") signalId: Long,
        ): Response {
            try {
                skipperEngine.replaySignal(workflowInstanceId, signalId)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable
            ) {
                throw mapError(e)
            }
            val signal: PersistedSignal =
                store
                    .getPersistedSignal(workflowInstanceId, signalId)
                    .getOrElseThrow { IllegalArgumentException("Persisted signal not found after replay") }
            return json(PersistedSignalView.from(signal))
        }

        /**
         * Rewind a workflow to a pivot checkpoint (incident recovery). Sets the workflow back to
         * RUNNING, deletes the pivot checkpoint and every checkpoint after it, and reschedules
         * execution so it replays from the pivot onward. Destructive and irreversible.
         *
         * The pivot is identified against the workflow's own stored checkpoints: by [checkpointName]
         * when the checkpoint is named, otherwise positionally by [actionClass] (fully-qualified name)
         * + [actionMethod] + [iteration]. Resolving against the persisted tags — rather than rebuilding
         * a [CheckpointTag] from the request — both avoids a `Class.forName` on request input (the tag's
         * actionClass is a `Class<*>`) and guarantees the tag handed to the rewind is one the store will
         * actually match.
         */
        @POST
        @Produces(MediaType.APPLICATION_JSON)
        @Path("/workflows/{id}/rewind")
        fun rewindWorkflow(
            @PathParam("id") workflowInstanceId: String,
            @QueryParam("actionClass") actionClass: String?,
            @QueryParam("actionMethod") actionMethod: String?,
            @QueryParam("iteration") iteration: Long?,
            @QueryParam("checkpointName") checkpointName: String?,
        ): Response {
            val pivot: CheckpointTag =
                resolvePivot(workflowInstanceId, actionClass, actionMethod, iteration, checkpointName)
            try {
                val updated: WorkflowInstance = workflowsService.rewindWorkflow(workflowInstanceId, pivot)
                return json(WorkflowInstanceView(updated, ArrayList(), ArrayList(), ArrayList()))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable
            ) {
                throw mapError(e)
            }
        }

        private fun resolvePivot(
            workflowId: String,
            actionClass: String?,
            actionMethod: String?,
            iteration: Long?,
            checkpointName: String?,
        ): CheckpointTag {
            val tags: List<CheckpointTag> =
                store.getActionCheckpoints(workflowId).asJava().map { it.checkpointTag }
            val match: CheckpointTag? =
                if (!checkpointName.isNullOrEmpty()) {
                    tags.firstOrNull { it.checkpointName == checkpointName }
                } else if (actionClass != null && actionMethod != null && iteration != null) {
                    val cls: String = actionClass
                    val method: String = actionMethod
                    val iter: Long = iteration
                    tags.firstOrNull {
                        it.checkpointName == null &&
                            it.actionClass.name == cls &&
                            it.actionMethod == method &&
                            it.iteration == iter
                    }
                } else {
                    throw mapBadRequest(
                        "Rewind requires either checkpointName or actionClass + actionMethod + iteration",
                    )
                }
            return match
                ?: throw mapBadRequest("No matching checkpoint to rewind to for workflow $workflowId")
        }

        private fun json(value: Any?): Response {
            return Response.ok(objectMapper.writeValueAsString(value), MediaType.APPLICATION_JSON).build()
        }

        /**
         * snake_case for Skipper's own types only. The admin payload embeds user data (workflow input,
         * state, results) whose property names belong to the adopting service's classes; those are
         * left exactly as declared, so the UI shows the fields a developer would recognise.
         */
        private class SkipperSnakeCase : PropertyNamingStrategy() {
            private val snakeCase: PropertyNamingStrategy = SNAKE_CASE

            override fun nameForField(
                config: MapperConfig<*>?,
                field: AnnotatedField,
                defaultName: String,
            ): String =
                if (isSkipperType(field.declaringClass)) {
                    snakeCase.nameForField(config, field, defaultName)
                } else {
                    defaultName
                }

            override fun nameForGetterMethod(
                config: MapperConfig<*>?,
                method: AnnotatedMethod,
                defaultName: String,
            ): String =
                if (isSkipperType(method.declaringClass)) {
                    snakeCase.nameForGetterMethod(config, method, defaultName)
                } else {
                    defaultName
                }

            override fun nameForSetterMethod(
                config: MapperConfig<*>?,
                method: AnnotatedMethod,
                defaultName: String,
            ): String =
                if (isSkipperType(method.declaringClass)) {
                    snakeCase.nameForSetterMethod(config, method, defaultName)
                } else {
                    defaultName
                }

            override fun nameForConstructorParameter(
                config: MapperConfig<*>?,
                ctorParam: AnnotatedParameter,
                defaultName: String,
            ): String =
                if (isSkipperType(ctorParam.declaringClass)) {
                    snakeCase.nameForConstructorParameter(config, ctorParam, defaultName)
                } else {
                    defaultName
                }

            private fun isSkipperType(declaringClass: Class<*>?): Boolean =
                declaringClass != null && declaringClass.name.startsWith("com.airbnb.skipper.")

            companion object {
                private const val serialVersionUID: Long = 1L
            }
        }

        private fun mapBadRequest(message: String): WebApplicationException {
            try {
                val formattedMessage: String = objectMapper.writeValueAsString(ErrorView(message))
                return mapWebApplicationException(formattedMessage, Response.Status.BAD_REQUEST)
            } catch (
                @Suppress("TooGenericExceptionCaught") `$ex`: Throwable
            ) {
                throw SneakyThrow.sneakyThrow(`$ex`)
            }
        }

        private fun mapError(e: Throwable): WebApplicationException {
            try {
                val message: String = objectMapper.writeValueAsString(ErrorView(e.message))
                return mapWebApplicationException(message, Response.Status.INTERNAL_SERVER_ERROR)
            } catch (
                @Suppress("TooGenericExceptionCaught") `$ex`: Throwable
            ) {
                throw SneakyThrow.sneakyThrow(`$ex`)
            }
        }

        private fun mapWebApplicationException(
            message: String,
            responseStatus: Response.Status,
        ): WebApplicationException {
            return WebApplicationException(
                Response.status(responseStatus).entity(message).type(MediaType.APPLICATION_JSON).build(),
            )
        }

        class WorkflowInstanceView internal constructor(
            private val instance: WorkflowInstance,
            private val checkpoints: List<ActionCheckpoint>,
            private val tasks: List<Task<Any>>,
            private val timers: List<Timer>,
        ) {
            fun getInstance(): WorkflowInstance {
                return this.instance
            }

            fun getCheckpoints(): List<ActionCheckpoint> {
                return this.checkpoints
            }

            fun getTasks(): List<Task<Any>> {
                return this.tasks
            }

            fun getTimers(): List<Timer> {
                return this.timers
            }
        }

        class ErrorView internal constructor(private val message: String?) {
            fun getMessage(): String? {
                return this.message
            }
        }

        class PersistedSignalsView internal constructor(private val signals: List<PersistedSignalView>) {
            fun getSignals(): List<PersistedSignalView> {
                return this.signals
            }
        }

        class PersistedSignalView internal constructor(
            private val id: Long?,
            private val workflowId: String,
            private val signalMethod: String,
            private val status: String,
            private val input: String?,
            private val error: String?,
            private val createdAt: Instant?,
            private val updatedAt: Instant?,
        ) {
            fun getId(): Long? {
                return this.id
            }

            fun getWorkflowId(): String {
                return this.workflowId
            }

            fun getSignalMethod(): String {
                return this.signalMethod
            }

            fun getStatus(): String {
                return this.status
            }

            fun getInput(): String? {
                return this.input
            }

            fun getError(): String? {
                return this.error
            }

            fun getCreatedAt(): Instant? {
                return this.createdAt
            }

            fun getUpdatedAt(): Instant? {
                return this.updatedAt
            }

            companion object {
                fun from(signal: PersistedSignal): PersistedSignalView {
                    val signalInput = signal.input
                    return PersistedSignalView(
                        signal.id,
                        signal.workflowId,
                        signal.signalMethod,
                        signal.status.name,
                        if (signalInput != null) signalInput.toString() else null,
                        signal.error,
                        signal.createdAt,
                        signal.updatedAt,
                    )
                }
            }
        }

        class TasksView internal constructor(private val tasks: List<Task<Any>>) {
            fun getTasks(): List<Task<Any>> {
                return this.tasks
            }
        }

        class WorkflowsView internal constructor(private val workflows: List<WorkflowInstance>) {
            fun getWorkflows(): List<WorkflowInstance> {
                return this.workflows
            }
        }

        class BulkOperationResult internal constructor(
            private val successful: List<String>,
            private val failed: List<String>,
        ) {
            fun getSuccessful(): List<String> {
                return this.successful
            }

            fun getFailed(): List<String> {
                return this.failed
            }
        }

        class DashboardStats internal constructor(
            private val exhaustedRetriesCount: Int,
            private val dlqTasksCount: Int,
            private val schedulerBacklogCount: Long,
        ) {
            fun getExhaustedRetriesCount(): Int {
                return this.exhaustedRetriesCount
            }

            fun getDlqTasksCount(): Int {
                return this.dlqTasksCount
            }

            fun getSchedulerBacklogCount(): Long {
                return this.schedulerBacklogCount
            }
        }

        class WorkflowSearchResult internal constructor(
            private val workflows: List<WorkflowInstance>,
            private val nextCursor: String?,
        ) {
            fun getWorkflows(): List<WorkflowInstance> {
                return this.workflows
            }

            fun getNextCursor(): String? {
                return this.nextCursor
            }
        }

        class WorkflowType internal constructor(
            private val workflowClass: String,
            private val workflowMethod: String,
        ) {
            private val label: String

            init {
                val simpleName = workflowClass.substring(workflowClass.lastIndexOf('.') + 1)
                this.label = "$simpleName::$workflowMethod"
            }

            fun getWorkflowClass(): String {
                return this.workflowClass
            }

            fun getWorkflowMethod(): String {
                return this.workflowMethod
            }

            fun getLabel(): String {
                return this.label
            }
        }

        companion object {
            private const val DEFAULT_EXHAUSTED_RETRIES_LIMIT = 500
            private const val DASHBOARD_STATS_EXHAUSTED_RETRIES_LIMIT = 1000
            private const val MIN_SEARCH_LIMIT = 1
            private const val MAX_SEARCH_LIMIT = 500

            private fun encodeCursor(
                createdAt: Instant,
                workflowId: String
            ): String {
                val raw = createdAt.toString() + "|" + workflowId
                return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
            }

            private fun decodeCursor(encoded: String): WorkflowSearchFilter.Cursor {
                val raw = String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
                val parts = raw.split("\\|".toRegex(), limit = 2).toTypedArray()
                if (parts.size < 2) {
                    throw IllegalArgumentException("Invalid cursor format")
                }
                return WorkflowSearchFilter.Cursor(Instant.parse(parts[0]), parts[1])
            }
        }
    }
