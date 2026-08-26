package com.airbnb.skipper.statemachine.admin

import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.api.RunRequest
import com.airbnb.skipper.internal.storage.WorkflowSearchFilter
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.statemachine.ErasedStateMachineMiddleware
import com.airbnb.skipper.statemachine.SkipperStateMachine
import com.airbnb.skipper.statemachine.StateMachineAdminSnapshot
import com.airbnb.skipper.statemachine.StateMachineCheckpoint
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.inject.Inject
import java.time.Instant
import javax.ws.rs.Consumes
import javax.ws.rs.GET
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.Produces
import javax.ws.rs.QueryParam
import javax.ws.rs.WebApplicationException
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import kotlin.reflect.full.primaryConstructor
import org.slf4j.LoggerFactory

/**
 * REST resource for the Skipper state machine admin view.
 *
 * Fetches workflow instances from the Skipper store and queries the single
 * [SkipperStateMachine.getAdminSnapshot] method to build structured debugging data.
 *
 * Only handles workflows that extend [SkipperStateMachine]. Non-SM workflows
 * return 400 with a message pointing to /skipper/admin/.
 */
@Path("/skipper/admin/statemachines")
open class StateMachineAdminResource
    @Inject
    constructor(
        private val store: WorkflowStore,
        private val skipperEngine: SkipperEngine,
    ) {
        @GET
        @Path("/")
        @Produces(MediaType.TEXT_HTML)
        fun index(): StateMachineAdminView = StateMachineAdminView()

        @GET
        @Path("/api/workflows/{id}")
        @Produces(MediaType.APPLICATION_JSON)
        fun getWorkflowInstance(
            @PathParam("id") id: String,
            @QueryParam("includeChildren") includeChildren: Boolean?,
        ): StateMachineInstanceView {
            val instance = fetchStateMachineInstance(id)
            return toInstanceView(instance, includeChildren ?: false)
        }

        /**
         * Returns valid events per state with parameter schemas,
         * derived from the admin snapshot's validEventsPerState.
         */
        @GET
        @Path("/api/workflows/{id}/valid-events")
        @Produces(MediaType.APPLICATION_JSON)
        fun getValidEvents(
            @PathParam("id") id: String,
        ): Map<String, List<EventSchemaInfo>> {
            val instance = fetchStateMachineInstance(id)
            return try {
                val snapshot = queryAdminSnapshot(instance) ?: return emptyMap()
                snapshot.validEventsPerState.entries.associate { (state, classes) ->
                    state to classes.map(::buildEventSchema)
                }
            } catch (e: Exception) {
                throw badRequest("Failed to query valid events: ${e.message}")
            }
        }

        /**
         * Sends an event (signal) to a running state machine workflow.
         *
         * Request body:
         * ```json
         * { "eventClass": "com.airbnb...ResponseCreated", "payload": { ... } }
         * ```
         */
        @POST
        @Path("/api/workflows/{id}/signal")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        fun sendEvent(
            @PathParam("id") id: String,
            body: Map<String, Any?>,
        ): Map<String, String> {
            val instance = fetchStateMachineInstance(id)
            val eventClassName = body["eventClass"] as? String
                ?: throw badRequest("Missing eventClassName in request body for workflowId=${instance.workflowId}")
            val payload = body["payload"]

            if (instance.status.isTerminal()) {
                throw badRequest("Cannot send event to terminal workflow. workflowId=${instance.workflowId}, eventClassName=$eventClassName")
            }

            val snapshot = try {
                queryAdminSnapshot(instance)
            } catch (e: Exception) {
                throw badRequest(
                    "Failed to determine valid events for workflowId=${instance.workflowId}, eventClassName=$eventClassName: ${e.message}"
                )
            }
            val validEventClassNames = snapshot?.validEventsPerState?.values
                ?.flatten()
                ?.map { it.name }
                ?.toSet()
                ?: throw badRequest("Failed to determine valid events for workflowId=${instance.workflowId}, eventClassName=$eventClassName")
            if (eventClassName !in validEventClassNames) {
                throw badRequest("Invalid event class for workflowId=${instance.workflowId}, eventClassName=$eventClassName")
            }

            val eventClass = try {
                // This lookup is allow-list gated by validEventClassNames from the workflow snapshot.
                Class.forName(eventClassName, true, Thread.currentThread().contextClassLoader)
            } catch (e: ClassNotFoundException) {
                throw badRequest("Unknown event class for workflowId=${instance.workflowId}, eventClassName=$eventClassName")
            }

            val kotlinMapper = jacksonObjectMapper()
            val event = try {
                if (payload != null) {
                    kotlinMapper.convertValue(payload, eventClass)
                } else {
                    eventClass.getDeclaredConstructor().newInstance()
                }
            } catch (e: Exception) {
                throw badRequest("Failed to deserialize event for workflowId=${instance.workflowId}, eventClassName=$eventClassName: ${e.message}")
            }

            val request = RunRequest.builder()
                .workflowId(instance.workflowId)
                .workflowClass(instance.workflowClass)
                .workflowMethod("sendEvent")
                .input(event)
                .requestContext(instance.requestContext)
                .extraRequestData(instance.extraRequestData)
                .build()

            skipperEngine.sendSignal(request)
            return mapOf("status" to "ok", "workflowId" to instance.workflowId)
        }

        companion object {
            private val log = LoggerFactory.getLogger(StateMachineAdminResource::class.java)

            /** Max recursion depth for child workflow loading to prevent runaway queries. */
            private const val MAX_CHILD_DEPTH = 5
            private const val MAX_CHILDREN_PER_LEVEL = 50
            private const val INTERNAL_PERSISTED_STATE_FIELD = "persistedState"
            private const val SCOPE_EXACT = "EXACT"
            private const val SCOPE_INFERRED = "INFERRED"

            private val terminalWorkflowStatuses = setOf("COMPLETED", "ERROR", "TIMEOUT", "CANCELLED", "COMPENSATION_COMPLETED")
            private val middlewareActionMethods = setOf(
                ErasedStateMachineMiddleware::onInitialStateEnteredMiddleware.name,
                ErasedStateMachineMiddleware::beforeTransitionMiddleware.name,
                ErasedStateMachineMiddleware::afterTransitionMiddleware.name,
                ErasedStateMachineMiddleware::onInvalidTransitionMiddleware.name,
                ErasedStateMachineMiddleware::onTerminalStateReachedMiddleware.name,
            )
        }

        // ── Instance view construction ──

        private fun toInstanceView(
            instance: WorkflowInstance,
            includeChildren: Boolean,
            depth: Int = 0,
        ): StateMachineInstanceView {
            val snapshot = queryAdminSnapshotOrNull(instance)
            val rawActionHistory = parseActionHistory(instance.workflowId)

            val stateHistory = snapshot?.stateHistory?.map {
                StateEntry(it.state, toPreciseView(it.timestamp))
            } ?: emptyList()

            val eventHistory = snapshot?.eventHistory?.map {
                EventEntry(
                    eventType = it.eventType,
                    payload = it.payload,
                    timestamp = toPreciseView(it.timestamp),
                )
            } ?: emptyList()

            val afterHookHistory = snapshot?.afterHookHistory?.map {
                AfterHookEntry(
                    duration = it.duration.toString(),
                    timestamp = toPreciseView(it.timestamp),
                    handlerSpan = toTimeRangeView(it.handlerSpan),
                )
            } ?: emptyList()

            val timeoutHistory = snapshot?.timeoutHistory?.map {
                TimeoutEntry(
                    duration = it.duration.toString(),
                    timestamp = toPreciseView(it.timestamp),
                    handlerSpan = toTimeRangeView(it.handlerSpan),
                )
            } ?: emptyList()

            val rawTransitionLog = snapshot?.transitionHistory?.mapNotNull { transition ->
                TransitionLogEntryView(
                    fromState = transition.fromState,
                    toState = transition.toState,
                    outcome = transition.outcome.name,
                    triggerKind = transition.triggerKind.name,
                    triggerName = transition.triggerName,
                    span = toTimeRangeView(transition.span) ?: return@mapNotNull null,
                    handlerSpan = toTimeRangeView(transition.handlerSpan),
                    eventIndex = transition.eventIndex,
                    initialMiddlewareSpan = toTimeRangeView(transition.initialMiddlewareSpan),
                    beforeMiddlewareSpan = toTimeRangeView(transition.beforeMiddlewareSpan),
                    onExitSpan = toTimeRangeView(transition.onExitSpan),
                    onEntrySpan = toTimeRangeView(transition.onEntrySpan),
                    afterMiddlewareSpan = toTimeRangeView(transition.afterMiddlewareSpan),
                    terminalMiddlewareSpan = toTimeRangeView(transition.terminalMiddlewareSpan),
                )
            } ?: emptyList()
            val scopedHistory = applyTransitionActionScopes(rawTransitionLog, rawActionHistory, instance.status.name)
            val transitionLog = scopedHistory.transitionLog
            val actionHistory = scopedHistory.actionHistory

            val validEvents = snapshot?.validEventsPerState?.entries?.associate { (state, classes) ->
                state to classes.map(::buildEventSchema)
            } ?: emptyMap()

            val pendingTimers = snapshot?.pendingTimerDeadlines?.map {
                PendingTimerInfo(it.type, it.duration, PreciseTimestampView(it.deadlineEpochSecond, it.deadlineNano))
            } ?: emptyList()

            val stateEntryTime = snapshot?.stateEntryTime?.let(::toPreciseView)

            val childWorkflows = if (includeChildren && depth < MAX_CHILD_DEPTH) {
                discoverChildWorkflows(instance, depth)
            } else {
                emptyList()
            }

            return StateMachineInstanceView(
                workflowId = instance.workflowId,
                workflowClass = instance.workflowClass.simpleName,
                workflowMethod = instance.workflowMethod,
                status = instance.status.name,
                createdAt = instance.createdAt,
                updatedAt = instance.updatedAt,
                parentWorkflowId = instance.parentWorkflowId,
                workflowInput = instance.input,
                stateFields = publicStateFields(instance),
                currentState = snapshot?.currentState,
                stateEntryTime = stateEntryTime,
                stateHistory = stateHistory,
                eventHistory = eventHistory,
                afterHookHistory = afterHookHistory,
                timeoutHistory = timeoutHistory,
                actionHistory = actionHistory,
                transitionLog = transitionLog,
                sequenceDiagramMermaid = MermaidGenerator.generateSequenceDiagram(
                    stateHistory = stateHistory,
                    eventHistory = eventHistory,
                    afterHookHistory = afterHookHistory,
                    timeoutHistory = timeoutHistory,
                    actionHistory = actionHistory,
                    pendingTimers = pendingTimers,
                    transitionLog = transitionLog,
                ),
                validEvents = validEvents,
                pendingTimers = pendingTimers,
                childWorkflows = childWorkflows,
            )
        }

        // ── Snapshot query ──

        private fun queryAdminSnapshot(instance: WorkflowInstance): StateMachineAdminSnapshot.AdminSnapshot? {
            val request = RunRequest.builder()
                .workflowId(instance.workflowId)
                .workflowClass(instance.workflowClass)
                .workflowMethod("getAdminSnapshot")
                .requestContext(instance.requestContext)
                .extraRequestData(instance.extraRequestData)
                .allowQueryOnNonExistentWorkflow(true)
                .build()
            return skipperEngine.invokeQueryMethod(request) as? StateMachineAdminSnapshot.AdminSnapshot
        }

        private fun queryAdminSnapshotOrNull(instance: WorkflowInstance): StateMachineAdminSnapshot.AdminSnapshot? {
            return try {
                queryAdminSnapshot(instance)
            } catch (e: Exception) {
                log.warn(
                    "Failed to query state machine admin snapshot. workflowId={}, workflowClass={}",
                    instance.workflowId,
                    instance.workflowClass.name,
                    e,
                )
                null
            }
        }

        // ── View helpers ──

        private fun toTimeRangeView(range: SkipperStateMachine.PreciseTimeRange?): TimeRangeView? {
            if (range == null) return null
            val durationNanos = (range.end.epochSecond - range.start.epochSecond) * 1_000_000_000L +
                (range.end.nano - range.start.nano)
            return TimeRangeView(
                start = PreciseTimestampView(range.start.epochSecond, range.start.nano),
                end = PreciseTimestampView(range.end.epochSecond, range.end.nano),
                durationNanos = durationNanos,
            )
        }

        private fun toPreciseView(ts: SkipperStateMachine.PreciseTimestamp): PreciseTimestampView = PreciseTimestampView(ts.epochSecond, ts.nano)

        private fun toPreciseView(ts: Instant): PreciseTimestampView = PreciseTimestampView(ts.epochSecond, ts.nano)

        private fun parseActionHistory(workflowId: String): List<ActionEntry> {
            return store.getActionCheckpoints(workflowId).toJavaList()
                .filter { cp -> cp.checkpointTag.actionClass != StateMachineCheckpoint::class.java }
                .map { cp ->
                    ActionEntry(
                        actionClass = cp.checkpointTag.actionClass.simpleName,
                        actionMethod = cp.checkpointTag.actionMethod,
                        iteration = cp.checkpointTag.iteration,
                        startTime = PreciseTimestampView(cp.executionStartTime.epochSecond, cp.executionStartTime.nano),
                        endTime = cp.executionEndTime?.let { PreciseTimestampView(it.epochSecond, it.nano) },
                        successful = cp.isSuccessful,
                        compensation = cp.checkpointTag.actionMethod.contains("compensat", ignoreCase = true),
                        input = cp.input,
                        result = cp.resultOrError,
                        actionClassName = cp.checkpointTag.actionClass.name,
                    )
                }
        }

        private data class ScopedHistory(
            val transitionLog: List<TransitionLogEntryView>,
            val actionHistory: List<ActionEntry>,
        )

        private data class ActionScope(
            val transitionIndex: Int,
            val transitionLabel: String,
            val transitionPhase: String? = null,
            val scopeKind: String,
            val notBeforeEpochNanos: Long? = null,
        )

        private data class TransitionPhaseRange(
            val transitionIndex: Int,
            val transitionLabel: String,
            val phaseLabel: String,
            val range: TimeRangeView,
        )

        private fun applyTransitionActionScopes(
            transitionLog: List<TransitionLogEntryView>,
            actionHistory: List<ActionEntry>,
            workflowStatus: String,
        ): ScopedHistory {
            val phaseRanges = transitionPhaseRanges(transitionLog)
            val terminalTransitionScope = terminalTransitionScope(transitionLog, workflowStatus)

            val scopedActions = actionHistory.map { action ->
                val scope = exactActionScope(action, phaseRanges)
                    ?: inferredTerminalActionScope(action, terminalTransitionScope)
                scope?.let {
                    action.copy(
                        transitionIndex = it.transitionIndex,
                        transitionPhase = it.transitionPhase,
                        transitionLabel = it.transitionLabel,
                        transitionScopeKind = it.scopeKind,
                    )
                } ?: action
            }

            val actionsByTransition = scopedActions
                .filter { it.transitionIndex != null }
                .groupBy { it.transitionIndex!! }
            val expandedTransitions = transitionLog.mapIndexed { index, transition ->
                transition.copy(
                    span = expandTimeRange(
                        base = transition.span,
                        extraRanges = transitionPhaseRanges(transition) +
                            actionsByTransition[index].orEmpty().map(::actionTimeRange),
                    )
                )
            }

            return ScopedHistory(expandedTransitions, scopedActions)
        }

        private fun exactActionScope(
            action: ActionEntry,
            phaseRanges: List<TransitionPhaseRange>,
        ): ActionScope? {
            val actionRange = actionTimeRange(action)
            return phaseRanges.firstOrNull { phaseRange ->
                contains(range = phaseRange.range, candidate = actionRange)
            }?.let { phaseRange ->
                ActionScope(
                    transitionIndex = phaseRange.transitionIndex,
                    transitionLabel = phaseRange.transitionLabel,
                    transitionPhase = phaseRange.phaseLabel,
                    scopeKind = SCOPE_EXACT,
                )
            }
        }

        private fun inferredTerminalActionScope(
            action: ActionEntry,
            terminalTransitionScope: ActionScope?,
        ): ActionScope? {
            if (terminalTransitionScope == null || action.actionMethod in middlewareActionMethods) {
                return null
            }
            val notBeforeEpochNanos = terminalTransitionScope.notBeforeEpochNanos ?: return null
            return terminalTransitionScope.takeIf { toEpochNanos(action.startTime) >= notBeforeEpochNanos }
        }

        private fun terminalTransitionScope(
            transitionLog: List<TransitionLogEntryView>,
            workflowStatus: String,
        ): ActionScope? {
            if (workflowStatus !in terminalWorkflowStatuses || transitionLog.isEmpty()) return null
            val finalTransitionIndex = transitionLog.lastIndex
            val finalTransition = transitionLog[finalTransitionIndex]
            val terminalSpan = finalTransition.terminalMiddlewareSpan ?: return null
            return ActionScope(
                transitionIndex = finalTransitionIndex,
                transitionLabel = transitionLabel(finalTransition),
                scopeKind = SCOPE_INFERRED,
                notBeforeEpochNanos = toEpochNanos(terminalSpan.end),
            )
        }

        private fun transitionLabel(transition: TransitionLogEntryView): String =
            if (transition.fromState != null) {
                "${transition.fromState} → ${transition.toState ?: transition.outcome.lowercase()}"
            } else {
                "→ ${transition.toState ?: transition.outcome.lowercase()}"
            }

        private fun actionTimeRange(action: ActionEntry): TimeRangeView = timeRangeView(action.startTime, action.endTime ?: action.startTime)

        private fun transitionPhaseRanges(transitionLog: List<TransitionLogEntryView>): List<TransitionPhaseRange> =
            transitionLog.flatMapIndexed { transitionIndex, transition ->
                val label = transitionLabel(transition)
                listOfNotNull(
                    transition.handlerSpan?.let { TransitionPhaseRange(transitionIndex, label, "Handler", it) },
                    transition.initialMiddlewareSpan?.let { TransitionPhaseRange(transitionIndex, label, "Initial middleware", it) },
                    transition.beforeMiddlewareSpan?.let { TransitionPhaseRange(transitionIndex, label, "Before middleware", it) },
                    transition.onExitSpan?.let { TransitionPhaseRange(transitionIndex, label, "onExit", it) },
                    transition.onEntrySpan?.let { TransitionPhaseRange(transitionIndex, label, "onEntry", it) },
                    transition.afterMiddlewareSpan?.let { TransitionPhaseRange(transitionIndex, label, "After middleware", it) },
                    transition.terminalMiddlewareSpan?.let { TransitionPhaseRange(transitionIndex, label, "Terminal middleware", it) },
                )
            }

        private fun transitionPhaseRanges(transition: TransitionLogEntryView): List<TimeRangeView> =
            listOfNotNull(
                transition.handlerSpan,
                transition.initialMiddlewareSpan,
                transition.beforeMiddlewareSpan,
                transition.onExitSpan,
                transition.onEntrySpan,
                transition.afterMiddlewareSpan,
                transition.terminalMiddlewareSpan,
            )

        private fun contains(
            range: TimeRangeView,
            candidate: TimeRangeView
        ): Boolean =
            toEpochNanos(candidate.start) >= toEpochNanos(range.start) &&
                toEpochNanos(candidate.end) <= toEpochNanos(range.end)

        private fun expandTimeRange(
            base: TimeRangeView,
            extraRanges: List<TimeRangeView>,
        ): TimeRangeView {
            var start = base.start
            var end = base.end
            extraRanges.forEach { range ->
                if (toEpochNanos(range.start) < toEpochNanos(start)) start = range.start
                if (toEpochNanos(range.end) > toEpochNanos(end)) end = range.end
            }
            return timeRangeView(start, end)
        }

        private fun timeRangeView(
            start: PreciseTimestampView,
            end: PreciseTimestampView,
        ): TimeRangeView =
            TimeRangeView(
                start = start,
                end = end,
                durationNanos = toEpochNanos(end) - toEpochNanos(start),
            )

        private fun toEpochNanos(timestamp: PreciseTimestampView): Long =
            Math.addExact(
                Math.multiplyExact(timestamp.epochSecond, 1_000_000_000L),
                timestamp.nano.toLong(),
            )

        private fun publicStateFields(instance: WorkflowInstance): Map<String, Any?> =
            instance.state.toJavaMap()
                .filterKeys { name -> name != INTERNAL_PERSISTED_STATE_FIELD && !name.startsWith("__") }
                .filterValues { value -> value !is SkipperStateMachine.PersistedStateBlob }

        private fun buildEventSchema(clazz: Class<*>): EventSchemaInfo {
            val params = clazz.kotlin.primaryConstructor?.parameters?.map { p ->
                EventParameterInfo(
                    name = p.name ?: "?",
                    type = p.type.toString().substringAfterLast('.'),
                )
            } ?: emptyList()
            return EventSchemaInfo(
                eventClassName = clazz.name,
                eventSimpleName = clazz.simpleName,
                parameters = params,
            )
        }

        // ── Child workflow discovery ──

        /**
         * Finds child workflows using the store's parentWorkflowId filter.
         * Recursively loads grandchildren up to [MAX_CHILD_DEPTH].
         */
        private fun discoverChildWorkflows(
            parent: WorkflowInstance,
            currentDepth: Int,
        ): List<StateMachineInstanceView> {
            val filter = WorkflowSearchFilter.builder()
                .parentWorkflowId(parent.workflowId)
                .build()
            val children = try {
                store.findWorkflows(filter, MAX_CHILDREN_PER_LEVEL).toJavaList()
            } catch (e: Exception) {
                log.warn("Failed to discover child state machine workflows. parentWorkflowId={}", parent.workflowId, e)
                return emptyList()
            }
            return children
                .filter { SkipperStateMachine::class.java.isAssignableFrom(it.workflowClass) }
                .mapNotNull { child ->
                    try {
                        toInstanceView(child, includeChildren = true, depth = currentDepth + 1)
                    } catch (e: Exception) {
                        log.warn(
                            "Failed to build child state machine admin view. parentWorkflowId={}, childWorkflowId={}",
                            parent.workflowId,
                            child.workflowId,
                            e,
                        )
                        null
                    }
                }
        }

        // ── Helpers ──

        private fun fetchStateMachineInstance(id: String): WorkflowInstance {
            val instance = store.getWorkflow(id)
                .getOrElseThrow { notFound("Workflow not found: $id") }
            if (!SkipperStateMachine::class.java.isAssignableFrom(instance.workflowClass)) {
                throw badRequest(
                    "Workflow $id is not a state machine" +
                        " (${instance.workflowClass.simpleName}). Use /skipper/admin/ instead.",
                )
            }
            return instance
        }

        private fun notFound(msg: String) =
            WebApplicationException(
                Response.status(Response.Status.NOT_FOUND)
                    .entity("""{"error":"$msg"}""")
                    .type(MediaType.APPLICATION_JSON).build(),
            )

        private fun badRequest(msg: String) =
            WebApplicationException(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity("""{"error":"$msg"}""")
                    .type(MediaType.APPLICATION_JSON).build(),
            )
    }
