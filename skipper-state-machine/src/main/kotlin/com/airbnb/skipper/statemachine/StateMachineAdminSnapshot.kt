package com.airbnb.skipper.statemachine

import com.airbnb.skipper.statemachine.SkipperStateMachine.PreciseTimeRange
import com.airbnb.skipper.statemachine.SkipperStateMachine.PreciseTimestamp
import com.airbnb.skipper.statemachine.SkipperStateMachine.TransitionLogEntry
import com.airbnb.skipper.statemachine.StateMachineStateCodec.RuntimeState
import java.time.Duration
import java.time.Instant

/**
 * Admin/debug snapshot types and assembly for [SkipperStateMachine].
 * Owns all data classes used by the admin API and the [assemble] factory method.
 * No Skipper dependencies.
 */
object StateMachineAdminSnapshot {
    /** Full after-hook execution info for admin/debug views. */
    data class AfterHookExecutionEntry(
        val duration: Duration,
        val timestamp: Instant,
        val handlerSpan: PreciseTimeRange?,
    )

    /** Full timeout execution info for admin/debug views. */
    data class TimeoutExecutionEntry(
        val duration: Duration,
        val timestamp: Instant,
        val handlerSpan: PreciseTimeRange?,
    )

    /** State history entry for admin views. */
    data class AdminStateEntry(val state: String, val timestamp: Instant)

    /** Event history entry for admin views. */
    data class AdminEventEntry(val eventType: String, val payload: Any?, val timestamp: Instant)

    /** Pending timer deadline for admin views. */
    data class AdminPendingTimer(
        val type: String,
        val duration: String,
        val deadlineEpochSecond: Long,
        val deadlineNano: Int,
    )

    /**
     * Complete admin/debug snapshot of a state machine's current state and history.
     * Returned by [SkipperStateMachine.getAdminSnapshot], replacing 12+ individual queries.
     */
    data class AdminSnapshot(
        val currentState: String?,
        val stateEntryTime: PreciseTimestamp?,
        val stateHistory: List<AdminStateEntry>,
        val eventHistory: List<AdminEventEntry>,
        val afterHookHistory: List<AfterHookExecutionEntry>,
        val timeoutHistory: List<TimeoutExecutionEntry>,
        val transitionHistory: List<TransitionLogEntry>,
        val validEventsPerState: Map<String, List<Class<*>>>,
        val pendingTimerDeadlines: List<AdminPendingTimer>,
    )

    /**
     * Assembles a complete admin snapshot from the compact runtime state and DSL definition.
     * Pure data transformation — no Skipper dependencies. Each history section is built by a
     * dedicated `assemble*` helper; this function only wires them into the [AdminSnapshot].
     *
     * [currentStateName] is already remapped through `stateNameMigrations` by the caller (it comes
     * from `resolveState(...).name`), so it is used verbatim here.
     */
    internal fun assemble(
        runtimeState: RuntimeState,
        builder: StateMachineBuilder<*, *, *>,
        currentStateName: String?,
        currentStateEntryEpochNanos: Long?,
        eventResolver: StateMachineEventResolver<*>,
        workflowId: String,
        stateNameMigrations: Map<String, String> = emptyMap(),
    ): AdminSnapshot =
        with(StateMachineStateCodec) {
            @Suppress("UNCHECKED_CAST")
            val validEvents = builder.stateDefinitions.entries.associate { (enumState, def) ->
                enumState.name to def.builder.handlers.map { it.eventClass.java as Class<*> }
            }

            AdminSnapshot(
                currentState = currentStateName,
                stateEntryTime = currentStateEntryEpochNanos?.toPreciseTimestamp(),
                stateHistory = assembleStateHistory(runtimeState, workflowId, stateNameMigrations),
                eventHistory = assembleEventHistory(runtimeState, eventResolver, workflowId),
                afterHookHistory = assembleAfterHookHistory(runtimeState),
                timeoutHistory = assembleTimeoutHistory(runtimeState),
                transitionHistory = assembleTransitionHistory(runtimeState, eventResolver, workflowId, stateNameMigrations),
                validEventsPerState = validEvents,
                pendingTimerDeadlines = assemblePendingTimers(runtimeState, builder, currentStateName, currentStateEntryEpochNanos),
            )
        }

    /**
     * State-entry history. Applies the same rename remap the event loop uses (`resolveState`), so
     * history rows show the current constant name and agree with `currentState` after a state rename.
     */
    private fun StateMachineStateCodec.assembleStateHistory(
        runtimeState: RuntimeState,
        workflowId: String,
        stateNameMigrations: Map<String, String>,
    ): List<AdminStateEntry> =
        runtimeState.transitions.mapNotNull { transition ->
            val toStateId = transition.toStateId ?: return@mapNotNull null
            val epochNanos = transition.stateEntryEpochNanos ?: return@mapNotNull null
            val persistedName = resolveStateName(runtimeState, toStateId, workflowId)
            AdminStateEntry(stateNameMigrations[persistedName] ?: persistedName, epochNanos.toInstant())
        }

    /**
     * Event-receipt history. `eventType` is the event's current effective alias (matching an EVENT
     * row's `triggerName` in [assembleTransitionHistory]) so the two views never disagree after an
     * event rename. The payload is materialized tolerantly: an alias that no longer resolves yields a
     * null payload rather than failing the whole snapshot query — symmetric with `currentAlias`'s
     * graceful fallback for the displayed name.
     */
    private fun StateMachineStateCodec.assembleEventHistory(
        runtimeState: RuntimeState,
        eventResolver: StateMachineEventResolver<*>,
        workflowId: String,
    ): List<AdminEventEntry> =
        runtimeState.events.map { event ->
            val persistedAlias = resolveEventTypeAlias(runtimeState, event.typeId, workflowId)
            AdminEventEntry(
                eventType = eventResolver.currentAlias(persistedAlias, workflowId),
                payload = runCatching { eventResolver.materializeEvent(event, runtimeState, workflowId) }.getOrNull(),
                timestamp = event.receivedAtEpochNanos.toInstant(),
            )
        }

    /** After-hook execution history. Carries no persisted state/event identity, so no remap applies. */
    private fun StateMachineStateCodec.assembleAfterHookHistory(runtimeState: RuntimeState): List<AfterHookExecutionEntry> =
        runtimeState.afterHooks.map {
            AfterHookExecutionEntry(
                duration = Duration.ofNanos(it.durationNanos),
                timestamp = it.firedAtEpochNanos.toInstant(),
                handlerSpan = it.handlerSpan?.toPreciseTimeRange(),
            )
        }

    /**
     * Timeout execution history. Timeout firing is journaled before the timeout handler runs. If the
     * handler fails retryably, admin may temporarily have a timeout row without its TIMEOUT
     * transition; once the handler succeeds, positional alignment gives the timeout row its handler
     * span. The index zip is safe because `timeouts` and TIMEOUT-kind `transitions` are both
     * append-only and rebuilt in the same replay order, so index i of each refers to the same firing.
     * (Unlike the named/id-keyed checkpoints elsewhere, this stays positional because there is at most
     * one timeout per state entry and the two lists never reorder.)
     */
    private fun StateMachineStateCodec.assembleTimeoutHistory(runtimeState: RuntimeState): List<TimeoutExecutionEntry> {
        val timeoutTransitions = runtimeState.transitions.filter {
            it.triggerKind == SkipperStateMachine.TriggerKind.TIMEOUT
        }
        return runtimeState.timeouts.mapIndexed { index, timeout ->
            TimeoutExecutionEntry(
                duration = Duration.ofNanos(timeout.durationNanos),
                timestamp = timeout.firedAtEpochNanos.toInstant(),
                handlerSpan = timeoutTransitions.getOrNull(index)?.handlerSpan?.toPreciseTimeRange(),
            )
        }
    }

    /**
     * Transition history. Mirrors the [assembleStateHistory] remap so the log agrees with
     * `currentState`/`stateHistory` after a rename: `fromState`/`toState` use the same
     * `migrations[name] ?: name` idiom, and an EVENT trigger name is remapped to the event's current
     * effective alias (covering `@EventAlias` and `eventAliasMigrations`). TIMEOUT/AUTO/INITIAL labels
     * are framework-derived, not persisted identities, so they pass through. The remap is applied here
     * at the admin boundary, leaving the shared codec (also used by runtime/replay) returning raw
     * persisted identity.
     */
    private fun StateMachineStateCodec.assembleTransitionHistory(
        runtimeState: RuntimeState,
        eventResolver: StateMachineEventResolver<*>,
        workflowId: String,
        stateNameMigrations: Map<String, String>,
    ): List<TransitionLogEntry> =
        runtimeState.transitions.map { materializeTransition(runtimeState, it) }.map { entry ->
            entry.copy(
                fromState = entry.fromState?.let { stateNameMigrations[it] ?: it },
                toState = entry.toState?.let { stateNameMigrations[it] ?: it },
                triggerName = if (entry.triggerKind == SkipperStateMachine.TriggerKind.EVENT) {
                    eventResolver.currentAlias(entry.triggerName, workflowId)
                } else {
                    entry.triggerName
                },
            )
        }

    /**
     * Pending (not-yet-fired) timer deadlines for the current state: after hooks whose deadline has
     * not passed plus the state's static timeout, if any. Empty unless the machine is parked in a
     * known state with a known entry time.
     */
    private fun StateMachineStateCodec.assemblePendingTimers(
        runtimeState: RuntimeState,
        builder: StateMachineBuilder<*, *, *>,
        currentStateName: String?,
        currentStateEntryEpochNanos: Long?,
    ): List<AdminPendingTimer> {
        if (currentStateName == null || currentStateEntryEpochNanos == null) {
            return emptyList()
        }
        val entryInstant = currentStateEntryEpochNanos.toInstant()
        val stateDef = builder.stateDefinitions.entries.firstOrNull { it.key.name == currentStateName }?.value
        val pendingTimers = mutableListOf<AdminPendingTimer>()

        // After hooks fired since entering the current state, matched to the definition by stable hook
        // id (so insert/remove/reorder edits are reflected correctly). Legacy records that predate hook
        // ids are matched by duration as a fallback.
        val firedInCurrentState = runtimeState.afterHooks.filter { it.firedAtEpochNanos >= currentStateEntryEpochNanos }
        val firedHookIds = firedInCurrentState.mapNotNull { it.hookId }.toSet()
        val firedLegacyDurationNanos = firedInCurrentState.filter { it.hookId == null }.map { it.durationNanos }.toSet()
        stateDef?.builder?.afterHooks
            ?.filterNot { hook -> hook.hookId in firedHookIds || hook.duration.toNanos() in firedLegacyDurationNanos }
            ?.forEach { hook ->
                val deadline = entryInstant.plus(hook.duration)
                pendingTimers.add(AdminPendingTimer("after", hook.duration.toString(), deadline.epochSecond, deadline.nano))
            }
        // Only render the timeout's pending deadline when its duration is statically known. An
        // input-computed (dynamic) timeout has no fixed duration to show without the workflow input,
        // which admin/query contexts do not have.
        stateDef?.builder?.timeout?.staticDuration?.let { timeoutDuration ->
            val deadline = entryInstant.plus(timeoutDuration)
            pendingTimers.add(AdminPendingTimer("timeout", timeoutDuration.toString(), deadline.epochSecond, deadline.nano))
        }
        return pendingTimers
    }
}
