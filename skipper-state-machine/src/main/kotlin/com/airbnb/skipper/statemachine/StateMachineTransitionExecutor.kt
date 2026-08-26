package com.airbnb.skipper.statemachine

import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.statemachine.StateMachineStateCodec.MarkerKind
import com.airbnb.skipper.statemachine.StateMachineStateCodec.toEpochNanos
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory

/**
 * Result of processing one event, timeout, or auto-transition step.
 *
 * [trigger] is populated only when the step produced a successful Stay/TransitionTo transition.
 * The event loop uses it to reset state-scoped timers and report terminal-state context.
 */
internal data class StateMachineProcessResult<StateT, EventT>(
    val newState: StateT,
    val didTransition: Boolean,
    val trigger: TransitionTrigger<EventT>? = null,
)

/** Target metadata for a Stay or TransitionTo result after it has been resolved. */
private data class ResolvedTransitionTarget<StateT, EventT : StateMachineEvent, InputT : Any>(
    val state: StateT,
    val stateDefinition: StateMachineBuilder.StateDefinition<StateT, EventT, InputT>?,
    val outcome: SkipperStateMachine.TransitionOutcome,
) where StateT : Enum<StateT>

/**
 * Executes one state-machine lifecycle step: middleware, hooks, state-entry journal records,
 * invalid-transition handling, and transition completion.
 *
 * Each visible lifecycle phase writes a small durable start marker before user code executes and
 * merges the measured end time back into the same transition record after success. If a retryable
 * action fails halfway through a hook or middleware callback, replay keeps the original start
 * timestamp instead of making the admin timeline appear to start on the successful retry.
 */
internal class StateMachineTransitionExecutor<StateT, EventT : StateMachineEvent, InputT : Any>(
    private val clock: () -> Clock,
    private val journal: StateMachineJournal<StateT, EventT>,
    private val stateMachineId: () -> String,
    private val stateMachineClass: () -> Class<out SkipperStateMachine<*, *, *>>,
    private val invalidTransitionPolicy: () -> InvalidTransitionPolicy,
    private val checkpoint: (() -> Unit) -> Unit,
) where StateT : Enum<StateT> {
    companion object {
        private val log = LoggerFactory.getLogger(StateMachineTransitionExecutor::class.java)
    }

    /**
     * Records and executes initial-state middleware and `onEntry` hooks.
     *
     * This method creates the first transition row (`null -> initialState`) and immediately
     * checkpoints lifecycle progress before user hooks run, preserving first-attempt timings if an
     * initial `onEntry` action fails and the workflow retries.
     */
    suspend fun enterInitialState(
        state: StateT,
        input: InputT,
        builder: StateMachineBuilder<StateT, EventT, InputT>,
        middlewares: List<ErasedStateMachineMiddleware>,
    ) {
        val start = clock().preciseNow()
        val pendingTransition = journal.buildPendingTransition(
            fromState = null,
            toState = state,
            outcome = SkipperStateMachine.TransitionOutcome.TRANSITION_TO,
            trigger = TransitionTrigger.InitialState,
            transitionStart = start,
            initialMiddlewareSpan = SkipperStateMachine.PreciseTimeRange(start, start),
        )
        var durableRecord = journal.recordTransitionJournal(
            pendingTransition = pendingTransition,
            span = SkipperStateMachine.PreciseTimeRange(start, start),
            checkpointMode = JournalCheckpointMode.IMMEDIATE,
            phase = StateMachineStateCodec.JournalPhase.STARTED,
        )

        val initialMiddleware = runPhase {
            val ctx = InitialStateEnteredContext(state, input, stateMachineId(), stateMachineClass())
            val middlewareCheckpoint = MiddlewareCheckpoint(ctx)
            middlewares.forEach { it.onInitialStateEnteredMiddleware(middlewareCheckpoint) }
        }

        val onEntryStart = clock().preciseNow()
        durableRecord = journal.recordTransitionProgressJournal(
            durableRecord = durableRecord,
            spanEnd = onEntryStart,
            stateEntryEpochNanos = onEntryStart.toEpochNanos(),
            initialMiddlewareSpan = initialMiddleware,
            onEntrySpan = SkipperStateMachine.PreciseTimeRange(onEntryStart, onEntryStart),
            phase = StateMachineStateCodec.JournalPhase.BEFORE_MIDDLEWARE,
        )

        val onEntry = runPhase {
            builder.stateDefinitions[state]?.builder?.onEntryHooks?.forEach { it(input) }
        }

        journal.recordTransitionCompletionJournal(
            durableStartRecord = durableRecord,
            spanEnd = clock().preciseNow(),
            onEntrySpan = onEntry,
        )
    }

    /**
     * Runs terminal-state middleware and attaches its span to the latest transition when possible.
     *
     * The middleware is called after the terminal state's `onEntry` hook has completed and before
     * the workflow method returns its final state.
     */
    suspend fun onTerminalStateReached(
        terminalState: StateT,
        lastTrigger: TransitionTrigger<EventT>?,
        input: InputT,
        middlewares: List<ErasedStateMachineMiddleware>,
    ) {
        val terminalMiddlewareStart = clock().preciseNow()
        val durableRecord = journal.recordTerminalMiddlewareProgress(
            SkipperStateMachine.PreciseTimeRange(terminalMiddlewareStart, terminalMiddlewareStart)
        )
        val terminalMiddleware = runPhase {
            val ctx = TerminalStateReachedContext(terminalState, lastTrigger, input, stateMachineId(), stateMachineClass())
            val middlewareCheckpoint = MiddlewareCheckpoint(ctx)
            middlewares.forEach { it.onTerminalStateReachedMiddleware(middlewareCheckpoint) }
        }
        durableRecord?.let {
            journal.recordTransitionProgressJournal(
                durableRecord = it,
                spanEnd = terminalMiddleware.end,
                terminalMiddlewareSpan = terminalMiddleware,
                checkpointMode = JournalCheckpointMode.IMMEDIATE,
                phase = StateMachineStateCodec.JournalPhase.TERMINAL_COMPLETED,
            )
        }
    }

    /**
     * Processes one materialized event from the durable event log.
     *
     * Handler start time is first written as a hidden timestamp marker because the final outcome
     * (ignore, stay, transition, or invalid) is not known until the handler returns.
     */
    suspend fun processEvent(
        event: EventT,
        eventIndex: Int,
        fromState: StateT,
        input: InputT,
        builder: StateMachineBuilder<StateT, EventT, InputT>,
        middlewares: List<ErasedStateMachineMiddleware>,
    ): StateMachineProcessResult<StateT, EventT> {
        val stateDef = builder.stateDefinitions[fromState]

        if (stateDef == null) {
            handleInvalidTransition(fromState, event, "No definition for state $fromState", input, middlewares, eventIndex)
            return StateMachineProcessResult(fromState, didTransition = false)
        }

        val handler = stateDef.builder.handlers.firstOrNull { handler ->
            handler.eventClass.java.isInstance(event)
        }

        if (handler == null) {
            handleInvalidTransition(fromState, event, "No handler for ${event::class.simpleName} in state $fromState", input, middlewares, eventIndex)
            return StateMachineProcessResult(fromState, didTransition = false)
        }

        val handlerStart = journal.recordTimestampMarker(clock().preciseNow(), MarkerKind.EVENT_HANDLER, eventIndex)
        val result = handler.handler.invoke(event)
        val handlerSpan = SkipperStateMachine.PreciseTimeRange(handlerStart, clock().preciseNow())
        val trigger = TransitionTrigger.Event(event)
        return resolveTransition(result, fromState, trigger, stateDef, input, builder, middlewares, handlerSpan, eventIndex)
    }

    /**
     * Processes a state timeout after the event loop observes its deadline.
     *
     * Timeout firing itself is journaled by [StateMachineEventLoop]; this method records the
     * timeout handler span and then applies the resulting transition.
     */
    suspend fun processTimeout(
        fromState: StateT,
        stateDef: StateMachineBuilder.StateDefinition<StateT, EventT, InputT>,
        input: InputT,
        builder: StateMachineBuilder<StateT, EventT, InputT>,
        middlewares: List<ErasedStateMachineMiddleware>,
    ): StateMachineProcessResult<StateT, EventT> {
        val timeout = stateDef.builder.timeout!!
        val timeoutDuration = timeout.durationFn(input)
        warnIfTimeoutOrphansAfterHooks(fromState, stateDef, timeout, timeoutDuration)
        val handlerStart = journal.recordTimestampMarker(clock().preciseNow(), MarkerKind.TIMEOUT_HANDLER)
        val result = timeout.handler(input)
        val handlerSpan = SkipperStateMachine.PreciseTimeRange(handlerStart, clock().preciseNow())
        val trigger = TransitionTrigger.Timeout(timeoutDuration)
        return resolveTransition(result, fromState, trigger, stateDef, input, builder, middlewares, handlerSpan)
    }

    /**
     * Warns once when a timeout fires before an `after` hook that can therefore never run in this
     * state entry. For a fixed-duration timeout this is impossible — build-time validation already
     * rejects `after >= timeout` — so the check applies only to an input-computed timeout, whose
     * duration is unknown at build time. The warning is wrapped in a checkpoint so it logs once per
     * occurrence rather than on every replay.
     */
    private suspend fun warnIfTimeoutOrphansAfterHooks(
        fromState: StateT,
        stateDef: StateMachineBuilder.StateDefinition<StateT, EventT, InputT>,
        timeout: StateBuilder.TimeoutConfig<StateT, InputT>,
        timeoutDuration: Duration,
    ) {
        if (timeout.staticDuration != null) return
        val orphaned = stateDef.builder.afterHooks.filter { it.duration >= timeoutDuration }
        if (orphaned.isEmpty()) return
        checkpoint {
            log.warn(
                "Timeout (computed {}) fired before {} after-hook(s) {} in state {} — those hooks never " +
                    "ran this entry. Ensure the input-computed timeout exceeds every after() duration. " +
                    "stateMachineId={}",
                timeoutDuration,
                orphaned.size,
                orphaned.map { it.hookId },
                fromState,
                stateMachineId(),
            )
        }
    }

    /**
     * Processes an immediate auto-transition declared by [StateBuilder.immediatelyTransitionTo].
     */
    suspend fun processAutoTransition(
        targetState: StateT,
        fromState: StateT,
        stateDef: StateMachineBuilder.StateDefinition<StateT, EventT, InputT>,
        input: InputT,
        builder: StateMachineBuilder<StateT, EventT, InputT>,
        middlewares: List<ErasedStateMachineMiddleware>,
    ): StateMachineProcessResult<StateT, EventT> =
        resolveTransition(
            result = TransitionResult.TransitionTo(targetState),
            fromState = fromState,
            trigger = TransitionTrigger.AutoTransition,
            stateDef = stateDef,
            input = input,
            builder = builder,
            middlewares = middlewares,
        )

    /**
     * Applies a handler result by recording transition rows, running middleware/hooks, and
     * returning the new local state.
     */
    private suspend fun resolveTransition(
        result: TransitionResult<StateT>,
        fromState: StateT,
        trigger: TransitionTrigger<EventT>,
        stateDef: StateMachineBuilder.StateDefinition<StateT, EventT, InputT>,
        input: InputT,
        builder: StateMachineBuilder<StateT, EventT, InputT>,
        middlewares: List<ErasedStateMachineMiddleware>,
        handlerSpan: SkipperStateMachine.PreciseTimeRange? = null,
        eventIndex: Int? = null,
    ): StateMachineProcessResult<StateT, EventT> {
        val transitionStart = handlerSpan?.start ?: clock().preciseNow()

        if (result is TransitionResult.Ignore) {
            return recordIgnoredTransition(result, fromState, trigger, transitionStart, handlerSpan, eventIndex)
        }

        val target = resolveTransitionTarget(result, fromState, stateDef, builder)
        return executeSuccessfulTransition(
            target = target,
            fromState = fromState,
            trigger = trigger,
            stateDef = stateDef,
            input = input,
            middlewares = middlewares,
            handlerSpan = handlerSpan,
            eventIndex = eventIndex,
            transitionStart = transitionStart,
        )
    }

    /** Records an ignored transition and returns without running transition middleware or hooks. */
    private fun recordIgnoredTransition(
        result: TransitionResult.Ignore,
        fromState: StateT,
        trigger: TransitionTrigger<EventT>,
        transitionStart: SkipperStateMachine.PreciseTimestamp,
        handlerSpan: SkipperStateMachine.PreciseTimeRange?,
        eventIndex: Int?,
    ): StateMachineProcessResult<StateT, EventT> {
        journal.recordTransitionJournal(
            pendingTransition = journal.buildPendingTransition(
                fromState = fromState,
                toState = null,
                outcome = ignoreOutcome(result.reason),
                trigger = trigger,
                transitionStart = transitionStart,
                handlerSpan = handlerSpan,
                eventIndex = eventIndex,
            ),
            span = SkipperStateMachine.PreciseTimeRange(transitionStart, clock().preciseNow()),
        )
        checkpoint {
            log.debug(
                "Event ignored ({}) in state {}. stateMachineId={}",
                result.reason,
                fromState,
                stateMachineId(),
            )
        }
        return StateMachineProcessResult(fromState, didTransition = false)
    }

    /** Maps ignore reasons to the admin/query transition outcome enum. */
    private fun ignoreOutcome(reason: TransitionResult.IgnoreReason): SkipperStateMachine.TransitionOutcome =
        when (reason) {
            TransitionResult.IgnoreReason.EXPLICIT -> SkipperStateMachine.TransitionOutcome.IGNORE_EXPLICIT
            TransitionResult.IgnoreReason.GUARD_REJECTED -> SkipperStateMachine.TransitionOutcome.IGNORE_GUARD
        }

    /** Resolves a non-ignore transition result into target state, target definition, and outcome. */
    private fun resolveTransitionTarget(
        result: TransitionResult<StateT>,
        fromState: StateT,
        stateDef: StateMachineBuilder.StateDefinition<StateT, EventT, InputT>,
        builder: StateMachineBuilder<StateT, EventT, InputT>,
    ): ResolvedTransitionTarget<StateT, EventT, InputT> =
        when (result) {
            is TransitionResult.TransitionTo -> ResolvedTransitionTarget(
                state = result.newState,
                stateDefinition = builder.stateDefinitions[result.newState],
                outcome = SkipperStateMachine.TransitionOutcome.TRANSITION_TO,
            )
            is TransitionResult.Stay -> ResolvedTransitionTarget(
                state = fromState,
                stateDefinition = stateDef,
                outcome = SkipperStateMachine.TransitionOutcome.STAY,
            )
            is TransitionResult.Ignore -> error("Ignore results are handled before resolving transition target")
        }

    /**
     * Runs the full lifecycle for a Stay or TransitionTo result.
     *
     * Every visible phase is bracketed by a durable progress write before user code executes. On
     * retry, those durable starts are merged with successful completion spans so admin timing stays
     * anchored to the first attempt.
     */
    private suspend fun executeSuccessfulTransition(
        target: ResolvedTransitionTarget<StateT, EventT, InputT>,
        fromState: StateT,
        trigger: TransitionTrigger<EventT>,
        stateDef: StateMachineBuilder.StateDefinition<StateT, EventT, InputT>,
        input: InputT,
        middlewares: List<ErasedStateMachineMiddleware>,
        handlerSpan: SkipperStateMachine.PreciseTimeRange?,
        eventIndex: Int?,
        transitionStart: SkipperStateMachine.PreciseTimestamp,
    ): StateMachineProcessResult<StateT, EventT> {
        val beforeMiddlewareStart = clock().preciseNow()
        val pendingTransition = journal.buildPendingTransition(
            fromState = fromState,
            toState = target.state,
            outcome = target.outcome,
            trigger = trigger,
            transitionStart = transitionStart,
            handlerSpan = handlerSpan,
            eventIndex = eventIndex,
            beforeMiddlewareSpan = SkipperStateMachine.PreciseTimeRange(beforeMiddlewareStart, beforeMiddlewareStart),
        )
        var durableRecord = journal.recordTransitionJournal(
            pendingTransition = pendingTransition,
            span = SkipperStateMachine.PreciseTimeRange(transitionStart, beforeMiddlewareStart),
            checkpointMode = JournalCheckpointMode.IMMEDIATE,
            phase = StateMachineStateCodec.JournalPhase.STARTED,
        )

        val beforeMiddleware = runBeforeTransitionMiddleware(fromState, trigger, input, middlewares)

        val onExitStart = clock().preciseNow()
        durableRecord = journal.recordTransitionProgressJournal(
            durableRecord = durableRecord,
            spanEnd = onExitStart,
            beforeMiddlewareSpan = beforeMiddleware,
            onExitSpan = SkipperStateMachine.PreciseTimeRange(onExitStart, onExitStart),
            phase = StateMachineStateCodec.JournalPhase.BEFORE_MIDDLEWARE,
        )

        val onExit = runOnExitHooks(stateDef, input)

        val onEntryStart = clock().preciseNow()
        durableRecord = journal.recordTransitionProgressJournal(
            durableRecord = durableRecord,
            spanEnd = onEntryStart,
            stateEntryEpochNanos = onEntryStart.toEpochNanos(),
            onExitSpan = onExit,
            onEntrySpan = SkipperStateMachine.PreciseTimeRange(onEntryStart, onEntryStart),
            phase = StateMachineStateCodec.JournalPhase.ON_EXIT,
        )

        val onEntry = runOnEntryHooks(target.stateDefinition, input)

        val afterMiddlewareStart = clock().preciseNow()
        durableRecord = journal.recordTransitionProgressJournal(
            durableRecord = durableRecord,
            spanEnd = afterMiddlewareStart,
            onEntrySpan = onEntry,
            afterMiddlewareSpan = SkipperStateMachine.PreciseTimeRange(afterMiddlewareStart, afterMiddlewareStart),
            phase = StateMachineStateCodec.JournalPhase.ON_ENTRY,
        )

        val afterMiddleware = runAfterTransitionMiddleware(fromState, target.state, trigger, input, middlewares)

        journal.recordTransitionCompletionJournal(
            durableStartRecord = durableRecord,
            spanEnd = clock().preciseNow(),
            onEntrySpan = onEntry,
            afterMiddlewareSpan = afterMiddleware,
        )

        return StateMachineProcessResult(target.state, didTransition = true, trigger = trigger)
    }

    /** Runs before-transition middleware and returns the measured span. */
    private suspend fun runBeforeTransitionMiddleware(
        fromState: StateT,
        trigger: TransitionTrigger<EventT>,
        input: InputT,
        middlewares: List<ErasedStateMachineMiddleware>,
    ): SkipperStateMachine.PreciseTimeRange {
        val ctx = BeforeTransitionContext(fromState, trigger, input, stateMachineId(), stateMachineClass())
        val checkpoint = MiddlewareCheckpoint(ctx)
        return runPhase {
            middlewares.forEach { it.beforeTransitionMiddleware(checkpoint) }
        }
    }

    /** Runs all on-exit hooks for the source state and returns the measured span. */
    private suspend fun runOnExitHooks(
        stateDef: StateMachineBuilder.StateDefinition<StateT, EventT, InputT>,
        input: InputT,
    ): SkipperStateMachine.PreciseTimeRange =
        runPhase {
            stateDef.builder.onExitHooks.forEach { it(input) }
        }

    /** Runs all on-entry hooks for the target state and returns the measured span. */
    private suspend fun runOnEntryHooks(
        targetStateDef: StateMachineBuilder.StateDefinition<StateT, EventT, InputT>?,
        input: InputT,
    ): SkipperStateMachine.PreciseTimeRange =
        runPhase {
            targetStateDef?.builder?.onEntryHooks?.forEach { it(input) }
        }

    /** Runs after-transition middleware and returns the measured span. */
    private suspend fun runAfterTransitionMiddleware(
        fromState: StateT,
        targetState: StateT,
        trigger: TransitionTrigger<EventT>,
        input: InputT,
        middlewares: List<ErasedStateMachineMiddleware>,
    ): SkipperStateMachine.PreciseTimeRange {
        val ctx = AfterTransitionContext(fromState, targetState, trigger, input, stateMachineId(), stateMachineClass())
        val checkpoint = MiddlewareCheckpoint(ctx)
        return runPhase {
            middlewares.forEach { it.afterTransitionMiddleware(checkpoint) }
        }
    }

    /** Measures a lifecycle phase. */
    private suspend fun runPhase(block: suspend () -> Unit): SkipperStateMachine.PreciseTimeRange {
        val (span, _) = measureTimeRange(clock()) { block() }
        return span
    }

    /**
     * Applies the configured invalid-transition policy and records an ignored transition row.
     *
     * The start marker is hidden until policy handling completes, which keeps failed/retried
     * invalid-transition middleware anchored to the original event attempt.
     */
    private suspend fun handleInvalidTransition(
        fromState: StateT,
        event: EventT,
        reason: String,
        input: InputT,
        middlewares: List<ErasedStateMachineMiddleware>,
        eventIndex: Int? = null,
    ) {
        val invalidStart = journal.recordTimestampMarker(clock().preciseNow(), MarkerKind.INVALID, eventIndex)
        val ctx = InvalidTransitionContext(fromState, event, input, stateMachineId(), stateMachineClass())
        val invalidCheckpoint = MiddlewareCheckpoint(ctx)
        middlewares.forEach { it.onInvalidTransitionMiddleware(invalidCheckpoint) }

        when (invalidTransitionPolicy()) {
            InvalidTransitionPolicy.LOG_AND_IGNORE -> {
                checkpoint {
                    log.warn(
                        "Invalid transition: {}. event={}, state={}, stateMachineId={}",
                        reason,
                        event::class.simpleName,
                        fromState,
                        stateMachineId(),
                    )
                }
            }

            InvalidTransitionPolicy.THROW -> {
                throw NonRetryableError(
                    "Invalid transition: $reason. event=${event::class.simpleName}, state=$fromState, stateMachineId=${stateMachineId()}",
                )
            }
        }
        val invalidSpan = SkipperStateMachine.PreciseTimeRange(invalidStart, clock().preciseNow())

        journal.recordTransitionJournal(
            pendingTransition = journal.buildPendingTransition(
                fromState = fromState,
                toState = null,
                outcome = SkipperStateMachine.TransitionOutcome.INVALID_NO_HANDLER,
                trigger = TransitionTrigger.Event(event),
                transitionStart = invalidSpan.start,
                eventIndex = eventIndex,
            ),
            span = invalidSpan,
        )
    }
}
