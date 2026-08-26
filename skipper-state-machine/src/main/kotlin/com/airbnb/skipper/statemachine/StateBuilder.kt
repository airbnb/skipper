package com.airbnb.skipper.statemachine

import java.time.Duration
import kotlin.reflect.KClass

/**
 * DSL builder for defining behavior within a single state.
 *
 * Used inside `define()` blocks:
 * ```kotlin
 * state(OPEN) {
 *     timeout(Duration.ofDays(14)) { transitionTo(EXPIRED) }
 *     on<ResponseCreated> { event, input -> if (count >= input.max) transitionTo(FULL) else stay() }
 *     on<Revoked> { _, _ -> transitionTo(REVOKED) }
 * }
 * ```
 *
 * @param StateT The state enum type.
 * @param EventT The event type. Must extend [StateMachineEvent]; typically a sealed class.
 * @param InputT The workflow input type.
 */
class StateBuilder<StateT : Enum<StateT>, EventT : StateMachineEvent, InputT : Any> {
    data class EventHandler<StateT : Enum<StateT>, EventT : StateMachineEvent>(
        val eventClass: KClass<out EventT>,
        val handler: suspend (EventT) -> TransitionResult<StateT>,
    )

    data class TimeoutConfig<StateT : Enum<StateT>, InputT : Any>(
        /**
         * Computes the timeout duration from the workflow input. Evaluated lazily — each time the
         * event loop computes this state's deadline — so a looping state can vary its timeout per
         * entry (e.g. a per-step delay read from input). Must be a pure, replay-deterministic
         * function of [InputT] (see [StateBuilder.timeout]).
         */
        val durationFn: (InputT) -> Duration,
        val handler: suspend (InputT) -> TransitionResult<StateT>,
        /** Declaration index among this state's timer DSL calls; used for validation only. */
        val timerDeclarationOrdinal: Int = Int.MAX_VALUE,
        /**
         * The fixed duration when this timeout was declared with a literal [Duration], else null for
         * an input-computed duration. Lets input-free contexts (admin pending-timer rendering,
         * `after < timeout` validation) use the duration without an input, while a dynamic timeout
         * simply has no statically-known duration to show there.
         */
        val staticDuration: Duration? = null,
    )

    data class AfterConfig<InputT : Any>(
        val duration: Duration,
        /** Declaration index among after hooks in this state, assigned in registration order. */
        val ordinal: Int,
        /** Optional author-supplied stable identity; overrides the ordinal for replay matching. */
        val id: String?,
        val handler: suspend (InputT) -> Unit,
        /** Declaration index among this state's timer DSL calls; used for validation only. */
        val timerDeclarationOrdinal: Int = ordinal,
    ) {
        /**
         * Stable identity used to match firings across replays and code edits.
         *
         * Defaults to the declaration ordinal, which is stable when after hooks are appended.
         * Changing durations does not change hook identity, but must preserve the increasing-duration
         * declaration contract. Inserting or reordering hooks before existing hooks shifts ordinal ids,
         * so an author who needs that must pass an explicit [id]. See the Evolution guide.
         */
        val hookId: String get() = id ?: "#$ordinal"
    }

    @PublishedApi
    internal val mutableHandlers = mutableListOf<EventHandler<StateT, EventT>>()
    private val mutableOnEntryHooks = mutableListOf<suspend (InputT) -> Unit>()
    private val mutableOnExitHooks = mutableListOf<suspend (InputT) -> Unit>()
    private var terminalState = false
    private var timeoutConfig: TimeoutConfig<StateT, InputT>? = null
    private val mutableAfterHooks = mutableListOf<AfterConfig<InputT>>()
    private val sortedAfterHooks by lazy(LazyThreadSafetyMode.NONE) { mutableAfterHooks.sortedBy { it.duration } }
    private var immediateTransitionTarget: StateT? = null
    private var nextTimerDeclarationOrdinal = 0

    /** Reference to the input, set by SkipperStateMachine before define() is called. */
    @PublishedApi
    internal var definitionInput: InputT? = null

    val handlers: List<EventHandler<StateT, EventT>> get() = mutableHandlers
    val onEntryHooks: List<suspend (InputT) -> Unit> get() = mutableOnEntryHooks
    val onExitHooks: List<suspend (InputT) -> Unit> get() = mutableOnExitHooks
    val isTerminal: Boolean get() = terminalState
    val timeout: TimeoutConfig<StateT, InputT>? get() = timeoutConfig
    val afterHooks: List<AfterConfig<InputT>> get() = sortedAfterHooks
    val immediateTransitionTo: StateT? get() = immediateTransitionTarget

    /**
     * Register a handler for events of type [R].
     *
     * The handler receives the typed event and the workflow input. Inside the handler,
     * the workflow's `@StateField` properties are accessible and mutable, and Skipper
     * [Actions][com.airbnb.skipper.Actions] can be called (they will be checkpointed).
     *
     * ```kotlin
     * on<Authorize> { event, input ->
     *     val result = payments.authorizeCard(event.amount, input.currency)
     *     authId = result.authId
     *     transitionTo(AUTHORIZED)
     * }
     * ```
     */
    inline fun <reified R : EventT> on(noinline handler: suspend (R, InputT) -> TransitionResult<StateT>) {
        @Suppress("UNCHECKED_CAST")
        mutableHandlers.add(
            EventHandler(
                eventClass = R::class,
                handler = { event ->
                    val typed = event as R
                    val input = definitionInput ?: error("Input not available — on<R> can only be used inside a running state machine")
                    handler(typed, input)
                },
            ),
        )
    }

    /**
     * Register a handler for events of type [R] with a guard condition.
     * The handler is only invoked if the guard returns true; otherwise the event is ignored.
     *
     * Guards are `suspend` — they can perform long-running calculations. If you need
     * non-deterministic side effects in a guard, use Skipper Actions with checkpoints.
     *
     * ```kotlin
     * on<Capture>({ event, input -> event.amount > 0 && input.isAuthorized }) { event, input ->
     *     transitionTo(CAPTURED)
     * }
     * ```
     */
    inline fun <reified R : EventT> on(
        noinline guard: suspend (R, InputT) -> Boolean,
        noinline handler: suspend (R, InputT) -> TransitionResult<StateT>,
    ) {
        @Suppress("UNCHECKED_CAST")
        mutableHandlers.add(
            EventHandler(
                eventClass = R::class,
                handler = { event ->
                    val typed = event as R
                    val input = definitionInput ?: error("Input not available")
                    if (guard(typed, input)) handler(typed, input) else TransitionResult.Ignore(TransitionResult.IgnoreReason.GUARD_REJECTED)
                },
            ),
        )
    }

    /**
     * Register a hook that executes when entering this state.
     *
     * Receives the workflow input as a parameter for convenient access.
     *
     * WARNING: onEntry hooks are NOT automatically checkpointed. If you need durable
     * side effects, call Skipper Actions (via `actions<>()`) from within the hook.
     * This matches Skipper's general contract: non-deterministic operations must go through actions.
     */
    fun onEntry(hook: suspend (InputT) -> Unit) {
        mutableOnEntryHooks.add(hook)
    }

    /**
     * Register a hook that executes when exiting this state.
     *
     * Receives the workflow input as a parameter for convenient access.
     *
     * WARNING: onExit hooks are NOT automatically checkpointed. If you need durable
     * side effects, call Skipper Actions (via `actions<>()`) from within the hook.
     */
    fun onExit(hook: suspend (InputT) -> Unit) {
        mutableOnExitHooks.add(hook)
    }

    /**
     * Set a fixed timeout for this state. If no event causes a transition within [duration],
     * the [handler] fires and its [TransitionResult] is applied.
     *
     * When a state also declares [after] hooks, declare all of them before `timeout` so the timer
     * section reads in execution order. Validation rejects `after` hooks declared after `timeout`.
     *
     * ```kotlin
     * timeout(Duration.ofDays(14)) { transitionTo(EXPIRED) }
     * ```
     */
    fun timeout(
        duration: Duration,
        handler: suspend (InputT) -> TransitionResult<StateT>,
    ) {
        timeoutConfig = TimeoutConfig(
            durationFn = { duration },
            handler = handler,
            timerDeclarationOrdinal = nextTimerDeclarationOrdinal++,
            staticDuration = duration,
        )
    }

    /**
     * Set a timeout whose duration is computed from the workflow input, evaluated lazily each time
     * the event loop computes this state's deadline. Use when the wait must vary per state entry —
     * e.g. a looping `EXECUTE_STEP` state whose per-step delay comes from `input.steps[index]`:
     *
     * ```kotlin
     * timeout({ input -> input.steps[currentStepIndex].waitDuration }) { transitionTo(NEXT) }
     * ```
     *
     * The deadline is `state-entry-time + durationFn(input)`, and the entry time is durable, so the
     * same state entry must compute the same deadline on every replay.
     *
     * **CONTRACT — [durationFn] must be a pure, replay-deterministic function.** It may read only the
     * immutable [InputT] and replay-deterministic local state (e.g. a `var` rebuilt from the event
     * log, like a step index). It must **not** read the wall clock, an `@Execute` Action result, a
     * `@StateField` that is mutated by side effects, or any external mutable state — otherwise the
     * timeout can fire at a different point on replay than it originally did, diverging the workflow.
     * Unlike the fixed [duration] overload, a wrong duration here is silent: there is no compile-time
     * value to inspect. See the Evolution guide.
     *
     * **Limitations.** Because the duration is not known until runtime, the `after < timeout`
     * validation can only be checked when the machine is built with a concrete input (it is skipped
     * if the input is unavailable, e.g. in admin/query contexts). The duration should also be
     * positive; a non-positive value fires the timeout immediately on entry.
     */
    fun timeout(
        durationFn: (InputT) -> Duration,
        handler: suspend (InputT) -> TransitionResult<StateT>,
    ) {
        timeoutConfig = TimeoutConfig(durationFn, handler, timerDeclarationOrdinal = nextTimerDeclarationOrdinal++)
    }

    /**
     * Schedule a side-effect action to execute after [duration] in this state.
     *
     * Unlike [timeout], this does NOT produce a transition — the state machine
     * remains in the current state after execution. Use for reminders,
     * notifications, or escalations that fire at a specific point during
     * a state's lifetime.
     *
     * Multiple after hooks per state are supported, but they must be declared before `timeout` and in
     * strictly increasing duration order. Fresh execution wakes by earliest deadline; replay may
     * observe several past deadlines at once, so validation keeps deadline order and declaration order
     * aligned before hook bodies run.
     *
     * Deadlines reset on [stay] (same as timeout) and clear on state
     * transition. They do NOT reset on [ignore].
     *
     * The handler should use `@Execute` Actions for durable side effects.
     *
     * Each after hook is identified for replay by its declaration order unless a stable [id] is
     * provided. Appending a hook preserves existing ordinal ids. If you need to insert or reorder hooks
     * on a state that already has live instances, pass explicit stable ids so identity does not shift.
     * Explicit ids stabilize hook identity; they do not remove the increasing-duration ordering
     * requirement.
     *
     * ```kotlin
     * state(WAITING) {
     *     after(Duration.ofDays(7)) { input ->
     *         actions.sendReminder(input.userId)
     *     }
     *     timeout(Duration.ofDays(10)) { transitionTo(EXPIRED) }
     * }
     * ```
     */
    fun after(
        duration: Duration,
        id: String? = null,
        handler: suspend (InputT) -> Unit,
    ) {
        mutableAfterHooks.add(
            AfterConfig(
                duration = duration,
                ordinal = mutableAfterHooks.size,
                id = id,
                handler = handler,
                timerDeclarationOrdinal = nextTimerDeclarationOrdinal++,
            ),
        )
    }

    /**
     * Mark this state as terminal. When the state machine enters a terminal state,
     * the event loop exits and the workflow completes.
     *
     * Terminal states should generally not have event handlers.
     */
    fun terminal() {
        terminalState = true
    }

    /**
     * After running onEntry hooks, immediately transition to [targetState] without
     * waiting for an event. Useful for transient "setup" states like PENDING → ACTIVE.
     *
     * ```kotlin
     * state(PENDING) {
     *     onEntry { input ->
     *         messageThreadId = actions.createMessageThread(input.hostUserId, input.cohostUserId)
     *     }
     *     immediatelyTransitionTo(ACTIVE)
     * }
     * ```
     */
    fun immediatelyTransitionTo(targetState: StateT) {
        immediateTransitionTarget = targetState
    }

    // ── DSL helpers ──

    /** Transition to [newState]. Use inside transition handler lambdas. */
    fun transitionTo(newState: StateT): TransitionResult<StateT> = TransitionResult.TransitionTo(newState)

    /** Stay in the current state. onExit and onEntry hooks ARE called (re-entering the same state). */
    fun stay(): TransitionResult<StateT> = TransitionResult.Stay

    /** Explicitly ignore this event. No state change, no middleware, no hooks. */
    fun ignore(): TransitionResult<StateT> = TransitionResult.Ignore(TransitionResult.IgnoreReason.EXPLICIT)
}
