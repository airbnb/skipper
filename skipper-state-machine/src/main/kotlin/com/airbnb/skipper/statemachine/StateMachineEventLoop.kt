package com.airbnb.skipper.statemachine

import com.airbnb.skipper.statemachine.StateMachineStateCodec.toEpochNanos
import com.airbnb.skipper.statemachine.StateMachineStateCodec.toInstant
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Mutable replay cursor for one [StateMachineEventLoop.run] invocation. */
private data class EventLoopCursor<StateT, EventT>(
    var state: StateT,
    var eventsProcessed: Int = 0,
    /**
     * Stable ids ([StateBuilder.AfterConfig.hookId]) of after hooks already fired in the current
     * state entry. Built up at the fire site as the loop walks — never seeded from a snapshot read,
     * so a fresh walk and a replay agree (replay re-fires through the same code path, and the firing
     * is action-replayed rather than re-executed). Cleared on every transition.
     */
    val firedAfterHookIds: MutableSet<String> = mutableSetOf(),
    var lastTrigger: TransitionTrigger<EventT>? = null,
)

/** The next unit of work selected after waiting for an event or timer deadline. */
private data class EventLoopWakeup<InputT : Any>(
    val kind: Kind,
    val afterHook: StateBuilder.AfterConfig<InputT>? = null,
) {
    enum class Kind {
        EVENT,
        AFTER_HOOK,
        TIMEOUT,
        NONE,
    }
}

/**
 * Deterministic replay loop for [SkipperStateMachine].
 *
 * The loop always starts from the configured initial state and reprocesses the durable event log
 * from index 0 so Skipper action/timer checkpoint iteration numbers line up across retries.
 * Timer and after-hook firings are journaled through [StateMachineJournal] so admin history is
 * rebuilt from durable checkpoint bytes rather than from wall-clock timestamps captured during
 * replay.
 */
internal class StateMachineEventLoop<StateT, EventT : StateMachineEvent, InputT : Any>(
    private val initialState: StateT,
    private val workflowId: () -> String,
    private val clock: () -> Clock,
    private val runtimeStore: StateMachineRuntimeStore,
    private val eventResolver: StateMachineEventResolver<EventT>,
    private val journal: StateMachineJournal<StateT, EventT>,
    private val transitionExecutor: StateMachineTransitionExecutor<StateT, EventT, InputT>,
    private val waitUntilCondition: suspend (() -> Boolean) -> Boolean,
    private val waitUntilConditionWithTimeout: suspend (() -> Boolean, Duration) -> Boolean,
) where StateT : Enum<StateT> {
    /**
     * Replays and advances the workflow until it reaches a terminal state or waits for work.
     *
     * The loop reconstructs state by starting at [initialState], replaying all durable events from
     * index 0, and replaying Skipper action checkpoints in the same order. New events, timeouts,
     * and after hooks are then processed one step at a time.
     */
    suspend fun run(
        input: InputT,
        builder: StateMachineBuilder<StateT, EventT, InputT>,
        middlewares: List<ErasedStateMachineMiddleware>,
    ): StateT {
        runtimeStore.resetReplayDerivedHistory()
        journal.resetForRun()

        val cursor = EventLoopCursor<StateT, EventT>(state = initialState)

        transitionExecutor.enterInitialState(cursor.state, input, builder, middlewares)

        while (true) {
            val stateDef = stateDefinition(builder, cursor.state)

            if (stateDef.builder.isTerminal) {
                transitionExecutor.onTerminalStateReached(cursor.state, cursor.lastTrigger, input, middlewares)
                return cursor.state
            }

            val autoTransitionResult = processAutoTransitionIfPresent(cursor, stateDef, input, builder, middlewares)
            if (autoTransitionResult != null) {
                cursor.applyProcessResult(autoTransitionResult)
                continue
            }

            val wakeup = waitForWakeup(cursor, stateDef, input)
            val result = when (wakeup.kind) {
                EventLoopWakeup.Kind.EVENT -> processNextEvent(cursor, input, builder, middlewares)
                EventLoopWakeup.Kind.AFTER_HOOK -> {
                    val afterHook = wakeup.afterHook ?: error("Missing after hook wakeup payload")
                    fireAfterHook(afterHook, input)
                    cursor.firedAfterHookIds.add(afterHook.hookId)
                    null
                }
                EventLoopWakeup.Kind.TIMEOUT -> processTimeout(cursor, stateDef, input, builder, middlewares)
                EventLoopWakeup.Kind.NONE -> null
            }

            if (result != null) {
                cursor.applyProcessResult(result)
            }
        }
    }

    /** Returns the definition for [state] or fails with workflow context. */
    private fun stateDefinition(
        builder: StateMachineBuilder<StateT, EventT, InputT>,
        state: StateT,
    ): StateMachineBuilder.StateDefinition<StateT, EventT, InputT> =
        builder.stateDefinitions[state] ?: error("No definition for state $state in workflow ${workflowId()}")

    /**
     * Executes an immediate auto-transition when the current state declares one.
     *
     * Auto-transitions do not wait for events or timers, but they still run through the same
     * transition executor so middleware, hooks, and journal timing are recorded consistently.
     */
    private suspend fun processAutoTransitionIfPresent(
        cursor: EventLoopCursor<StateT, EventT>,
        stateDef: StateMachineBuilder.StateDefinition<StateT, EventT, InputT>,
        input: InputT,
        builder: StateMachineBuilder<StateT, EventT, InputT>,
        middlewares: List<ErasedStateMachineMiddleware>,
    ): StateMachineProcessResult<StateT, EventT>? =
        stateDef.builder.immediateTransitionTo?.let { immediateTarget ->
            transitionExecutor.processAutoTransition(
                immediateTarget,
                cursor.state,
                stateDef,
                input,
                builder,
                middlewares,
            )
        }

    /**
     * Waits until either a new durable event arrives or the next state-scoped timer is due.
     *
     * After hooks are always before timeout deadlines because [StateMachineBuilder.validate]
     * rejects `after >= timeout`; the explicit deadline comparison keeps that invariant visible.
     */
    private suspend fun waitForWakeup(
        cursor: EventLoopCursor<StateT, EventT>,
        stateDef: StateMachineBuilder.StateDefinition<StateT, EventT, InputT>,
        input: InputT,
    ): EventLoopWakeup<InputT> {
        val entryInstant = currentStateEntryInstant(cursor.state)
        val unfiredHooks = stateDef.builder.afterHooks.filter { it.hookId !in cursor.firedAfterHookIds }
        // Wake at the earliest unfired-hook deadline; afterHooks is duration-sorted, so the first
        // unfired hook has the next deadline. Validation requires declaration order to match increasing
        // duration order, so selecting the lowest ordinal among due hooks preserves fresh-execution
        // order when replay sees several past deadlines at once.
        val nextAfterDeadline = unfiredHooks.firstOrNull()?.let { entryInstant.plus(it.duration) }
        // The timeout duration is computed from the input each time, so a looping state can vary it
        // per entry. The entry instant is durable, so this reproduces the same deadline on replay.
        val timeoutDeadline = stateDef.builder.timeout?.let { entryInstant.plus(it.durationFn(input)) }
        val earliestDeadline = listOfNotNull(nextAfterDeadline, timeoutDeadline).minOrNull()

        if (waitForNewEvent(cursor.eventsProcessed, earliestDeadline)) {
            return EventLoopWakeup(EventLoopWakeup.Kind.EVENT)
        }

        // Among hooks now due, fire the lowest declaration ordinal first.
        val now = clock().instant()
        val dueHookToFire = unfiredHooks
            .filter { !entryInstant.plus(it.duration).isAfter(now) }
            .minByOrNull { it.ordinal }
        if (dueHookToFire != null) {
            return EventLoopWakeup(EventLoopWakeup.Kind.AFTER_HOOK, dueHookToFire)
        }

        return when (earliestDeadline) {
            null -> EventLoopWakeup(EventLoopWakeup.Kind.NONE)
            timeoutDeadline -> EventLoopWakeup(EventLoopWakeup.Kind.TIMEOUT)
            else -> EventLoopWakeup(EventLoopWakeup.Kind.NONE)
        }
    }

    /** Returns the current state's durable entry timestamp as an [Instant]. */
    private fun currentStateEntryInstant(state: StateT): Instant =
        StateMachineStateCodec.currentStateEntryEpochNanos(runtimeStore.runtimeState())?.toInstant()
            ?: error("Missing state entry time for $state in workflow ${workflowId()}")

    /** Waits for a new durable event, optionally bounded by the next timer deadline. */
    private suspend fun waitForNewEvent(
        eventsProcessed: Int,
        earliestDeadline: Instant?,
    ): Boolean =
        if (earliestDeadline != null) {
            val remaining = Duration.between(clock().instant(), earliestDeadline)
                .coerceAtLeast(Duration.ZERO)
            waitUntilConditionWithTimeout({ runtimeStore.runtimeState().events.size > eventsProcessed }, remaining)
        } else {
            waitUntilCondition { runtimeStore.runtimeState().events.size > eventsProcessed }
        }

    /** Runs one after hook and records retry-stable handler timing. */
    private suspend fun fireAfterHook(
        afterHook: StateBuilder.AfterConfig<InputT>,
        input: InputT,
    ) {
        val firedAt = clock().preciseNow()
        val durableMarker = journal.recordAfterHookProgressJournal(
            duration = afterHook.duration,
            firedAtEpochNanos = firedAt.toEpochNanos(),
            handlerSpan = SkipperStateMachine.PreciseTimeRange(firedAt, firedAt),
            hookId = afterHook.hookId,
            checkpointMode = JournalCheckpointMode.IMMEDIATE,
            phase = StateMachineStateCodec.JournalPhase.STARTED,
        )
        val (handlerSpan, _) = measureTimeRange(clock()) { afterHook.handler(input) }
        journal.recordAfterHookProgressJournal(
            duration = afterHook.duration,
            firedAtEpochNanos = durableMarker.firedAtEpochNanos,
            handlerSpan = handlerSpan,
            hookId = afterHook.hookId,
            phase = StateMachineStateCodec.JournalPhase.COMPLETED,
        )
    }

    /** Records timeout firing and delegates timeout handler execution to the transition executor. */
    private suspend fun processTimeout(
        cursor: EventLoopCursor<StateT, EventT>,
        stateDef: StateMachineBuilder.StateDefinition<StateT, EventT, InputT>,
        input: InputT,
        builder: StateMachineBuilder<StateT, EventT, InputT>,
        middlewares: List<ErasedStateMachineMiddleware>,
    ): StateMachineProcessResult<StateT, EventT> {
        val timeout = stateDef.builder.timeout ?: error("Timeout wakeup without timeout config")
        journal.appendTimeoutJournal(
            duration = timeout.durationFn(input),
            firedAtEpochNanos = clock().preciseNow().toEpochNanos(),
            checkpointMode = JournalCheckpointMode.IMMEDIATE,
        )
        return transitionExecutor.processTimeout(cursor.state, stateDef, input, builder, middlewares)
    }

    /** Materializes and processes the next event from the durable event log. */
    private suspend fun processNextEvent(
        cursor: EventLoopCursor<StateT, EventT>,
        input: InputT,
        builder: StateMachineBuilder<StateT, EventT, InputT>,
        middlewares: List<ErasedStateMachineMiddleware>,
    ): StateMachineProcessResult<StateT, EventT> {
        val eventIndex = cursor.eventsProcessed
        val runtimeState = runtimeStore.runtimeState()
        val event = eventResolver.materializeEvent(runtimeState.events[eventIndex], runtimeState, workflowId())
        cursor.eventsProcessed++
        return transitionExecutor.processEvent(event, eventIndex, cursor.state, input, builder, middlewares)
    }

    /** Applies transition results to the replay cursor. */
    private fun EventLoopCursor<StateT, EventT>.applyProcessResult(result: StateMachineProcessResult<StateT, EventT>) {
        state = result.newState
        if (result.didTransition) {
            lastTrigger = result.trigger
            firedAfterHookIds.clear()
        }
    }
}
