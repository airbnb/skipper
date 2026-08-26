package com.airbnb.skipper.statemachine.admin

import java.time.Instant

/** Admin view of a state machine workflow instance with parsed history and visualizations. */
data class StateMachineInstanceView(
    val workflowId: String,
    val workflowClass: String,
    val workflowMethod: String,
    val status: String,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val parentWorkflowId: String?,
    val workflowInput: Any?,
    /** Public workflow state fields, excluding state-machine framework metadata. */
    val stateFields: Map<String, Any?> = emptyMap(),
    val currentState: String?,
    val stateEntryTime: PreciseTimestampView?,
    val stateHistory: List<StateEntry>,
    val eventHistory: List<EventEntry>,
    val afterHookHistory: List<AfterHookEntry>,
    val timeoutHistory: List<TimeoutEntry>,
    val actionHistory: List<ActionEntry>,
    val transitionLog: List<TransitionLogEntryView>,
    val sequenceDiagramMermaid: String,
    val validEvents: Map<String, List<EventSchemaInfo>>,
    val pendingTimers: List<PendingTimerInfo>,
    /** Recursive — children are full instance views, supporting grandchildren etc. */
    val childWorkflows: List<StateMachineInstanceView>,
)

data class PendingTimerInfo(
    val type: String,
    val duration: String,
    val deadline: PreciseTimestampView,
)

data class StateEntry(
    val state: String,
    val timestamp: PreciseTimestampView,
)

data class EventEntry(
    val eventType: String,
    val payload: Any?,
    val timestamp: PreciseTimestampView,
)

data class AfterHookEntry(
    val duration: String,
    val timestamp: PreciseTimestampView,
    val handlerSpan: TimeRangeView?,
)

data class TimeoutEntry(
    val duration: String,
    val timestamp: PreciseTimestampView,
    val handlerSpan: TimeRangeView? = null,
)

data class ActionEntry(
    val actionClass: String,
    val actionMethod: String,
    val iteration: Long,
    val startTime: PreciseTimestampView,
    val endTime: PreciseTimestampView?,
    val successful: Boolean,
    val compensation: Boolean,
    val input: Any?,
    val result: Any?,
    val actionClassName: String = actionClass,
    val transitionIndex: Int? = null,
    val transitionPhase: String? = null,
    val transitionLabel: String? = null,
    /** `EXACT` when time-contained in a phase, `INFERRED` when attached by terminal-transition inference. */
    val transitionScopeKind: String? = null,
)

data class PreciseTimestampView(
    val epochSecond: Long,
    val nano: Int,
)

data class TimeRangeView(
    val start: PreciseTimestampView,
    val end: PreciseTimestampView,
    val durationNanos: Long,
)

data class TransitionLogEntryView(
    val fromState: String?,
    val toState: String?,
    val outcome: String,
    val triggerKind: String,
    val triggerName: String,
    val span: TimeRangeView,
    val handlerSpan: TimeRangeView?,
    val eventIndex: Int?,
    val initialMiddlewareSpan: TimeRangeView?,
    val beforeMiddlewareSpan: TimeRangeView?,
    val onExitSpan: TimeRangeView?,
    val onEntrySpan: TimeRangeView?,
    val afterMiddlewareSpan: TimeRangeView?,
    val terminalMiddlewareSpan: TimeRangeView?,
)

data class EventSchemaInfo(
    val eventClassName: String,
    val eventSimpleName: String,
    val parameters: List<EventParameterInfo>,
)

data class EventParameterInfo(
    val name: String,
    val type: String,
)
