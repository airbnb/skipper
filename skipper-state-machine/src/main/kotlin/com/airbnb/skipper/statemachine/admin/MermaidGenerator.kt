package com.airbnb.skipper.statemachine.admin

import com.airbnb.skipper.statemachine.ErasedStateMachineMiddleware
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Generates Mermaid sequenceDiagram markup from state machine instance history.
 *
 * The diagram shows up to four participants (only included when they have entries):
 * - **Timers** (left) — after() hooks, timeout firings, and pending timer deadlines
 * - **Events** — external signals arriving at the state machine
 * - **State Machine** (center) — state entries shown as notes with durations
 * - **Actions** (right) — checkpointed side effects with iteration and duration, including
 *   framework middleware checkpoints when action history contains them
 *
 * Framework middleware also appears as state-machine notes using transition-scoped spans.
 *
 * All entries include timestamps. When the workflow spans multiple calendar days (UTC),
 * timestamps include the date (MM-DD HH:mm:ss); otherwise just time (HH:mm:ss).
 *
 * Example output:
 * ```
 * sequenceDiagram
 *     participant Ext as Events
 *     participant SM as State Machine
 *     participant Act as Actions
 *     Note over SM: → OPEN (10:00:00) [2h 15m]
 *     Ext->>SM: ResponseCreated (10:00:01)
 *     Note right of Ext: {listingId=123}
 *     SM->>Act: updateStatus() #0 (10:00:02)
 *     Act-->>SM: ok [150ms]
 *     Timer->>SM: timeout(PT336H) (10:14:00)
 *     Note over SM: → EXPIRED (10:14:00)
 * ```
 */
object MermaidGenerator {
    private const val PAYLOAD_MAX_CHARS = 60
    private val INITIAL_STATE_MIDDLEWARE = ErasedStateMachineMiddleware::onInitialStateEnteredMiddleware.name
    private val BEFORE_TRANSITION_MIDDLEWARE = ErasedStateMachineMiddleware::beforeTransitionMiddleware.name
    private val AFTER_TRANSITION_MIDDLEWARE = ErasedStateMachineMiddleware::afterTransitionMiddleware.name
    private val INVALID_TRANSITION_MIDDLEWARE = ErasedStateMachineMiddleware::onInvalidTransitionMiddleware.name
    private val TERMINAL_STATE_MIDDLEWARE = ErasedStateMachineMiddleware::onTerminalStateReachedMiddleware.name

    private val TIME_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("UTC"))
    private val DATE_TIME_FORMAT =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.of("UTC"))

    fun generateSequenceDiagram(
        stateHistory: List<StateEntry>,
        eventHistory: List<EventEntry>,
        afterHookHistory: List<AfterHookEntry>,
        timeoutHistory: List<TimeoutEntry> = emptyList(),
        actionHistory: List<ActionEntry> = emptyList(),
        pendingTimers: List<PendingTimerInfo> = emptyList(),
        transitionLog: List<TransitionLogEntryView> = emptyList(),
    ): String {
        val timeline = buildTimeline(
            stateHistory,
            eventHistory,
            afterHookHistory,
            timeoutHistory,
            actionHistory,
            pendingTimers,
            transitionLog,
        )
        if (timeline.isEmpty()) return ""

        val useDate = needsDatePrefix(timeline)
        val hasIgnoredEvents = timeline.any { it is TimelineEntry.IgnoredEvent }
        val hasEvents = eventHistory.isNotEmpty() || hasIgnoredEvents
        val hasActions = actionHistory.isNotEmpty()
        val hasTimers = afterHookHistory.isNotEmpty() ||
            timeoutHistory.isNotEmpty() ||
            pendingTimers.isNotEmpty()

        val stateTimelineEntries = timeline.filterIsInstance<TimelineEntry.State>()
        val stateDurations = precomputeStateDurations(stateHistory, stateTimelineEntries)

        // Positional matching: stateHistory[0] is the initial state entry,
        // and stateHistory[N] corresponds to successfulTransitions[N]. This avoids name-based
        // lookup which breaks when a state is visited multiple times (e.g., A→B→C→B).
        val successfulTransitions = transitionLog
            .filter { it.outcome == "TRANSITION_TO" || it.outcome == "STAY" }
        var stateEntryIndex = 0

        return buildString {
            appendLine("sequenceDiagram")
            if (hasTimers) appendLine("    participant Timer as Timers")
            if (hasEvents) appendLine("    participant Ext as Events")
            appendLine("    participant SM as State Machine")
            if (hasActions) appendLine("    participant Act as Actions")
            for (entry in timeline) {
                when (entry) {
                    is TimelineEntry.State -> {
                        val time = formatTimestamp(entry.timestamp, useDate)
                        val dur = stateDurations[entry]
                        val durStr = if (dur != null) " [${formatDurationNanos(dur)}]" else ""

                        // Every state entry (including the initial) maps 1:1 to a successful
                        // transition in order. The initial entry has fromState=null.
                        val transitionIdx = stateEntryIndex
                        stateEntryIndex++
                        val matched = successfulTransitions.getOrNull(transitionIdx)

                        appendMiddlewareNote("Initial MW", matched?.initialMiddlewareSpan)
                        appendMiddlewareNote("Before MW", matched?.beforeMiddlewareSpan)

                        // onExit note (from the previous state, before this transition)
                        if (matched?.onExitSpan != null && matched.onExitSpan.durationNanos > 0) {
                            appendLine("    Note over SM: onExit [${formatDurationNanos(matched.onExitSpan.durationNanos)}]")
                        }

                        // State transition note with optional transition duration
                        val transStr = if (matched != null && matched.span.durationNanos > 0) {
                            " (transition: ${formatDurationNanos(matched.span.durationNanos)})"
                        } else {
                            ""
                        }
                        appendLine("    Note over SM: → ${entry.state} ($time)$durStr$transStr")

                        // onEntry note (into the new state, after this transition)
                        if (matched?.onEntrySpan != null && matched.onEntrySpan.durationNanos > 0) {
                            appendLine("    Note over SM: onEntry [${formatDurationNanos(matched.onEntrySpan.durationNanos)}]")
                        }
                        appendMiddlewareNote("After MW", matched?.afterMiddlewareSpan)
                        appendMiddlewareNote("Terminal MW", matched?.terminalMiddlewareSpan)
                    }
                    is TimelineEntry.Event -> {
                        val time = formatTimestamp(entry.timestamp, useDate)
                        appendLine("    Ext->>SM: ${entry.eventType} ($time)")
                        val payloadStr = formatPayload(entry.payload)
                        if (payloadStr != null) {
                            appendLine("    Note right of Ext: $payloadStr")
                        }
                    }
                    is TimelineEntry.Action -> {
                        val time = formatTimestamp(entry.timestamp, useDate)
                        val prefix = if (entry.compensation) "⟲ " else ""
                        appendLine("    SM->>Act: $prefix${entry.actionClass}.${entry.method}() #${entry.iteration} ($time)")
                        val result = if (entry.successful) "ok" else "error"
                        val durStr = entry.durationNanos?.let { " [${formatDurationNanos(it)}]" } ?: ""
                        appendLine("    Act-->>SM: $result$durStr")
                    }
                    is TimelineEntry.AfterHook -> {
                        val time = formatTimestamp(entry.timestamp, useDate)
                        appendLine("    Timer->>SM: after(${entry.duration}) ($time)")
                    }
                    is TimelineEntry.Timeout -> {
                        val time = formatTimestamp(entry.timestamp, useDate)
                        appendLine("    Timer->>SM: timeout(${entry.duration}) ($time)")
                    }
                    is TimelineEntry.PendingTimer -> {
                        val deadline = formatTimestamp(entry.timestamp, useDate)
                        appendLine("    Note over Timer,Act: [Scheduled] ${entry.timerType}(${entry.duration}) fires at $deadline")
                    }
                    is TimelineEntry.IgnoredEvent -> {
                        val time = formatTimestamp(entry.timestamp, useDate)
                        appendLine("    Ext--xSM: ${entry.triggerName} [${entry.reason}] ($time)")
                    }
                }
            }
        }.trimEnd()
    }

    private fun buildTimeline(
        stateHistory: List<StateEntry>,
        eventHistory: List<EventEntry>,
        afterHookHistory: List<AfterHookEntry>,
        timeoutHistory: List<TimeoutEntry>,
        actionHistory: List<ActionEntry>,
        pendingTimers: List<PendingTimerInfo>,
        transitionLog: List<TransitionLogEntryView> = emptyList(),
    ): List<TimelineEntry> {
        val entries = buildList {
            stateHistory.forEach { add(TimelineEntry.State(it.state, it.timestamp)) }
            eventHistory.forEach { add(TimelineEntry.Event(it.eventType, it.payload, it.timestamp)) }
            afterHookHistory.forEach { add(TimelineEntry.AfterHook(it.duration, it.timestamp)) }
            timeoutHistory.forEach { add(TimelineEntry.Timeout(it.duration, it.timestamp)) }
            actionHistory.forEach {
                val durNanos = it.endTime?.let { end ->
                    (end.epochSecond - it.startTime.epochSecond) * 1_000_000_000L +
                        (end.nano - it.startTime.nano)
                }
                add(
                    TimelineEntry.Action(
                        it.actionClass,
                        it.actionMethod,
                        it.successful,
                        it.compensation,
                        it.startTime,
                        it.iteration,
                        durNanos,
                        actionLogicalOrder(it.actionMethod),
                    ),
                )
            }
            pendingTimers.forEach { add(TimelineEntry.PendingTimer(it.type, it.duration, it.deadline)) }
            transitionLog.forEach { entry ->
                if (entry.outcome.startsWith("IGNORE_") || entry.outcome.startsWith("INVALID_")) {
                    val reason = when (entry.outcome) {
                        "IGNORE_EXPLICIT" -> "ignored"
                        "IGNORE_GUARD" -> "guard rejected"
                        "INVALID_NO_HANDLER" -> "no handler"
                        else -> entry.outcome.lowercase()
                    }
                    add(
                        TimelineEntry.IgnoredEvent(
                            triggerName = entry.triggerName,
                            reason = reason,
                            fromState = entry.fromState ?: "—",
                            timestamp = entry.span.start,
                        ),
                    )
                }
            }
        }
        return entries.sortedWith(PRECISE_COMPARATOR)
    }

    /**
     * Pre-computes state durations (in nanos) by pairing each [TimelineEntry.State] with
     * the gap to the next state, derived from the original [PreciseTimestampView]. The last state maps to null.
     */
    private fun precomputeStateDurations(
        stateHistory: List<StateEntry>,
        stateEntries: List<TimelineEntry.State>,
    ): Map<TimelineEntry.State, Long?> {
        return stateEntries.mapIndexed { i, entry ->
            val nextTs = stateHistory.getOrNull(i + 1)?.timestamp
            val curTs = stateHistory[i].timestamp
            val nanos = if (nextTs != null) {
                (nextTs.epochSecond - curTs.epochSecond) * 1_000_000_000L + (nextTs.nano - curTs.nano)
            } else {
                null
            }
            entry to nanos
        }.toMap()
    }

    private fun needsDatePrefix(timeline: List<TimelineEntry>): Boolean {
        if (timeline.size < 2) return false
        val minDay = timeline.first().timestamp.toInstant().atZone(ZoneId.of("UTC")).toLocalDate()
        val maxDay = timeline.last().timestamp.toInstant().atZone(ZoneId.of("UTC")).toLocalDate()
        return minDay != maxDay
    }

    /** Converts a [PreciseTimestampView] to an [Instant] for formatting. */
    private fun PreciseTimestampView.toInstant(): Instant = Instant.ofEpochSecond(epochSecond, nano.toLong())

    private fun formatTimestamp(
        ts: PreciseTimestampView,
        includeDate: Boolean,
    ): String {
        val format = if (includeDate) DATE_TIME_FORMAT else TIME_FORMAT
        return format.format(ts.toInstant())
    }

    private fun formatDurationNanos(nanos: Long): String {
        if (nanos < 0) return ""
        val micros = nanos / 1_000
        val ms = nanos / 1_000_000
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            days > 0 -> "${days}d ${hours % 24}h ${minutes % 60}m"
            hours > 0 -> "${hours}h ${minutes % 60}m ${seconds % 60}s"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            seconds > 0 -> "${seconds}s"
            ms > 0 -> "${ms}ms"
            micros > 0 -> "${micros}\u03BCs"
            else -> "${nanos}ns"
        }
    }

    private fun formatPayload(payload: Any?): String? {
        if (payload == null) return null
        val raw = payload.toString().replace("\n", " ").replace("\r", "")
        return if (raw.length > PAYLOAD_MAX_CHARS) {
            raw.substring(0, PAYLOAD_MAX_CHARS) + "..."
        } else {
            raw
        }
    }

    private fun StringBuilder.appendMiddlewareNote(
        label: String,
        span: TimeRangeView?,
    ) {
        if (span != null && span.durationNanos >= 0) {
            appendLine("    Note over SM: $label [${formatDurationNanos(span.durationNanos)}]")
        }
    }

    /**
     * Sorts timeline entries by timestamp, with a logical-order tiebreaker for entries
     * at the same nanosecond. This ensures correct ordering within a transition:
     * Logical order (consecutive, used as tiebreaker when timestamps are identical):
     *   0 = event/trigger arrival
     *   1 = initial-state middleware
     *   2 = before-transition middleware / invalid-transition middleware
     *   3 = business actions (side effects from handlers/hooks)
     *   4 = state commit
     *   5 = after-transition middleware
     *   6 = terminal-state middleware
     *   7 = after hooks / timeouts (timer points)
     *   8 = ignored events
     *   9 = pending timers (future)
     */
    private val PRECISE_COMPARATOR: Comparator<TimelineEntry> =
        compareBy({ it.timestamp.epochSecond }, { it.timestamp.nano }, { it.logicalOrder })

    /** Maps action method names to their logical position within a transition. */
    private fun actionLogicalOrder(method: String): Int =
        when (method) {
            INITIAL_STATE_MIDDLEWARE -> 1
            BEFORE_TRANSITION_MIDDLEWARE -> 2
            INVALID_TRANSITION_MIDDLEWARE -> 2
            AFTER_TRANSITION_MIDDLEWARE -> 5
            TERMINAL_STATE_MIDDLEWARE -> 6
            else -> 3
        }

    private sealed class TimelineEntry(val timestamp: PreciseTimestampView, val logicalOrder: Int) {
        class State(val state: String, timestamp: PreciseTimestampView) : TimelineEntry(timestamp, 4)

        class Event(val eventType: String, val payload: Any?, timestamp: PreciseTimestampView) :
            TimelineEntry(timestamp, 0)

        class Action(
            val actionClass: String,
            val method: String,
            val successful: Boolean,
            val compensation: Boolean,
            timestamp: PreciseTimestampView,
            val iteration: Long,
            val durationNanos: Long?,
            logicalOrder: Int,
        ) : TimelineEntry(timestamp, logicalOrder)

        class AfterHook(val duration: String, timestamp: PreciseTimestampView) : TimelineEntry(timestamp, 7)

        class Timeout(val duration: String, timestamp: PreciseTimestampView) : TimelineEntry(timestamp, 7)

        class PendingTimer(val timerType: String, val duration: String, timestamp: PreciseTimestampView) :
            TimelineEntry(timestamp, 9)

        class IgnoredEvent(
            val triggerName: String,
            val reason: String,
            val fromState: String,
            timestamp: PreciseTimestampView,
        ) : TimelineEntry(timestamp, 8)
    }
}
