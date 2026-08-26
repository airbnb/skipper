package com.airbnb.skipper.statemachine

import com.airbnb.skipper.Actions
import com.airbnb.skipper.CheckpointMode
import com.airbnb.skipper.Execute
import com.airbnb.skipper.SkipperOpen
import com.airbnb.skipper.internal.serde.Serializable
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Duration

/**
 * Type-safe union for what triggered a state machine transition.
 *
 * Replaces the previous `event: EventT?` convention (where `null` meant timeout or auto-transition)
 * with an explicit discriminated union. Uses the same `@JsonTypeInfo(CLASS, WRAPPER_ARRAY)` pattern
 * as other polymorphic Skipper state fields for serde compatibility.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.WRAPPER_ARRAY)
sealed class TransitionTrigger<out EventT> {
    override fun equals(other: Any?): Boolean = this === other || this.javaClass == other?.javaClass

    override fun hashCode(): Int = javaClass.hashCode()

    /** Transition triggered by a user event. */
    data class Event<out EventT>(
        @field:JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.WRAPPER_ARRAY)
        val event: EventT,
    ) : TransitionTrigger<EventT>()

    /** Transition triggered by a state timeout expiring. */
    data class Timeout(val duration: Duration) : TransitionTrigger<Nothing>()

    /** Transition triggered by [StateBuilder.immediatelyTransitionTo]. */
    object AutoTransition : TransitionTrigger<Nothing>()

    /** Initial state entry — the state machine starts in this state, no previous state exists. */
    object InitialState : TransitionTrigger<Nothing>()
}

/**
 * Non-generic wrapper for state machine middleware context objects.
 *
 * Skipper's [com.airbnb.skipper.internal.serde.SimplePojoSerde] rejects classes with generic type
 * parameters in both `validateSerializableType` (top-level and recursive field walk) and
 * `validateSerializableObject` (round-trip test fails due to type erasure). Since our context
 * classes (e.g., [BeforeTransitionContext]) are generic data classes with generic field types
 * (e.g., [TransitionTrigger]), they cannot be serialized directly by Skipper's serde layer.
 *
 * This wrapper uses [Any] as the field type because it is the only non-generic type that can
 * hold arbitrary context objects while passing Skipper's recursive field-type validation
 * (`Object.class.getTypeParameters().length == 0`). The [@Serializable] annotation bypasses
 * the round-trip equality test, and [@JsonTypeInfo] preserves the actual context class name
 * in the serialized JSON for Skipper's debugging tools.
 */
@Serializable
data class MiddlewareCheckpoint(
    @field:JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.WRAPPER_ARRAY)
    val context: Any,
)

/**
 * Context for [StateMachineMiddleware.beforeTransition] — `toState` is not yet known.
 */
data class BeforeTransitionContext<out StateT, out EventT, out InputT>(
    val fromState: StateT,
    val trigger: TransitionTrigger<EventT>,
    val input: InputT,
    val stateMachineId: String,
    val stateMachineClass: Class<out SkipperStateMachine<*, *, *>>,
)

/**
 * Context for [StateMachineMiddleware.afterTransition] — full transition info available.
 */
data class AfterTransitionContext<out StateT, out EventT, out InputT>(
    val fromState: StateT,
    val toState: StateT,
    val trigger: TransitionTrigger<EventT>,
    val input: InputT,
    val stateMachineId: String,
    val stateMachineClass: Class<out SkipperStateMachine<*, *, *>>,
)

/**
 * Context for [StateMachineMiddleware.onInvalidTransition] — event has no handler in current state.
 */
data class InvalidTransitionContext<out StateT, out EventT, out InputT>(
    val fromState: StateT,
    val event: EventT,
    val input: InputT,
    val stateMachineId: String,
    val stateMachineClass: Class<out SkipperStateMachine<*, *, *>>,
)

/**
 * Context for [StateMachineMiddleware.onTerminalStateReached] — a terminal state was entered.
 *
 * [trigger] is the [TransitionTrigger] that caused the transition to this terminal state,
 * or `null` if the state machine's [initialState] was directly terminal (no transition occurred).
 */
data class TerminalStateReachedContext<out StateT, out EventT, out InputT>(
    val terminalState: StateT,
    val trigger: TransitionTrigger<EventT>?,
    val input: InputT,
    val stateMachineId: String,
    val stateMachineClass: Class<out SkipperStateMachine<*, *, *>>,
)

/**
 * Context for [StateMachineMiddleware.onInitialStateEntered] — the state machine just entered
 * its initial state (cold start). No previous state or trigger exists. Symmetric with
 * [TerminalStateReachedContext].
 */
data class InitialStateEnteredContext<out StateT, out InputT>(
    val initialState: StateT,
    val input: InputT,
    val stateMachineId: String,
    val stateMachineClass: Class<out SkipperStateMachine<*, *, *>>,
)

/**
 * Type-erased base for all state machine middleware. Carries [@SkipperOpen] and [@Execute]
 * so that subclasses inherit Skipper's action proxy registration without redeclaring them.
 *
 * All lifecycle hooks operate on `<Enum<*>, Any, Any>` context objects. Subclasses bridge
 * these erased contexts to their respective typed APIs.
 *
 * Middleware checkpoints are immediate so lifecycle callbacks are durable at the point they run,
 * and raw Skipper action-history rows stay anchored to the same execution attempt as the
 * transition. Admin transition timing still uses [SkipperStateMachine]'s transition journal spans
 * for visual scoping; the immediate action checkpoints provide the same durability/idempotency
 * guarantee as other framework-owned transition metadata.
 *
 * @see StateMachineMiddleware typed middleware bound to a specific state machine
 * @see UniversalStateMachineMiddleware cross-machine middleware usable with any state machine
 */
@SkipperOpen
abstract class ErasedStateMachineMiddleware : Actions() {
    /** Type-erased hook called after a handler resolves to Stay/TransitionTo, before hooks run. */
    @Execute(checkpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT)
    open suspend fun beforeTransitionMiddleware(ctx: MiddlewareCheckpoint): MiddlewareCheckpoint {
        @Suppress("UNCHECKED_CAST")
        beforeTransitionMiddlewareHook(ctx.context as BeforeTransitionContext<Enum<*>, Any, Any>)
        return ctx
    }

    /** Type-erased hook called after a successful transition (Stay and TransitionTo, not Ignore). */
    @Execute(checkpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT)
    open suspend fun afterTransitionMiddleware(ctx: MiddlewareCheckpoint): MiddlewareCheckpoint {
        @Suppress("UNCHECKED_CAST")
        afterTransitionMiddlewareHook(ctx.context as AfterTransitionContext<Enum<*>, Any, Any>)
        return ctx
    }

    /** Type-erased hook called when no handler matches the event in the current state. */
    @Execute(checkpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT)
    open suspend fun onInvalidTransitionMiddleware(ctx: MiddlewareCheckpoint): MiddlewareCheckpoint {
        @Suppress("UNCHECKED_CAST")
        onInvalidTransitionMiddlewareHook(ctx.context as InvalidTransitionContext<Enum<*>, Any, Any>)
        return ctx
    }

    /** Type-erased hook called when a terminal state is entered, after onEntry hooks. */
    @Execute(checkpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT)
    open suspend fun onTerminalStateReachedMiddleware(ctx: MiddlewareCheckpoint): MiddlewareCheckpoint {
        @Suppress("UNCHECKED_CAST")
        onTerminalStateReachedMiddlewareHook(ctx.context as TerminalStateReachedContext<Enum<*>, Any, Any>)
        return ctx
    }

    /** Type-erased hook called when the initial state is entered (cold start). */
    @Execute(checkpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT)
    open suspend fun onInitialStateEnteredMiddleware(ctx: MiddlewareCheckpoint): MiddlewareCheckpoint {
        @Suppress("UNCHECKED_CAST")
        onInitialStateEnteredMiddlewareHook(ctx.context as InitialStateEnteredContext<Enum<*>, Any>)
        return ctx
    }

    protected abstract suspend fun beforeTransitionMiddlewareHook(ctx: BeforeTransitionContext<Enum<*>, Any, Any>)

    protected abstract suspend fun afterTransitionMiddlewareHook(ctx: AfterTransitionContext<Enum<*>, Any, Any>)

    protected abstract suspend fun onInvalidTransitionMiddlewareHook(ctx: InvalidTransitionContext<Enum<*>, Any, Any>)

    protected abstract suspend fun onTerminalStateReachedMiddlewareHook(ctx: TerminalStateReachedContext<Enum<*>, Any, Any>)

    protected abstract suspend fun onInitialStateEnteredMiddlewareHook(ctx: InitialStateEnteredContext<Enum<*>, Any>)
}

/**
 * Cross-cutting middleware for state machine transitions. Registered in [StateMachineBuilder.define]
 * via `middleware<MyMiddleware>()`. Multiple middleware execute in registration order.
 *
 * Extends [ErasedStateMachineMiddleware] for Guice dependency injection. The erased bridge methods
 * cast `<Enum<*>, Any, Any>` context back to the specific `<StateT, EventT, InputT>` types and delegate
 * to the typed overrides. This cast is safe at runtime because [SkipperStateMachine] always creates
 * contexts with the correct concrete types, and the context classes are covariant.
 *
 * ```kotlin
 * class MetricsMiddleware : StateMachineMiddleware<MyState, MyEvent, MyInput>() {
 *     @Inject lateinit var metrics: MetricsRegistry
 *
 *     override suspend fun afterTransition(ctx: AfterTransitionContext<MyState, MyEvent, MyInput>) {
 *         when (val trigger = ctx.trigger) {
 *             is TransitionTrigger.Event -> metrics.counter("events").tag("event", trigger.event::class.simpleName).inc()
 *             is TransitionTrigger.Timeout -> metrics.counter("timeouts").inc()
 *             is TransitionTrigger.AutoTransition -> {} // typically not instrumented
 *         }
 *     }
 * }
 * ```
 *
 * @param StateT The state enum type.
 * @param EventT The event type. Must extend [StateMachineEvent]; typically a sealed class.
 * @param InputT The workflow input type.
 */
@SkipperOpen
abstract class StateMachineMiddleware<StateT : Enum<StateT>, EventT : StateMachineEvent, InputT : Any> : ErasedStateMachineMiddleware() {
    @Suppress("UNCHECKED_CAST")
    override suspend fun beforeTransitionMiddlewareHook(ctx: BeforeTransitionContext<Enum<*>, Any, Any>) {
        beforeTransition(ctx as BeforeTransitionContext<StateT, EventT, InputT>)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun afterTransitionMiddlewareHook(ctx: AfterTransitionContext<Enum<*>, Any, Any>) {
        afterTransition(ctx as AfterTransitionContext<StateT, EventT, InputT>)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun onInvalidTransitionMiddlewareHook(ctx: InvalidTransitionContext<Enum<*>, Any, Any>) {
        onInvalidTransition(ctx as InvalidTransitionContext<StateT, EventT, InputT>)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun onTerminalStateReachedMiddlewareHook(ctx: TerminalStateReachedContext<Enum<*>, Any, Any>) {
        onTerminalStateReached(ctx as TerminalStateReachedContext<StateT, EventT, InputT>)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun onInitialStateEnteredMiddlewareHook(ctx: InitialStateEnteredContext<Enum<*>, Any>) {
        onInitialStateEntered(ctx as InitialStateEnteredContext<StateT, InputT>)
    }

    /** Called after a handler resolves to Stay/TransitionTo, before hooks run. */
    open suspend fun beforeTransition(ctx: BeforeTransitionContext<StateT, EventT, InputT>) {}

    /** Called after a successful transition (for Stay and TransitionTo, not Ignore). */
    open suspend fun afterTransition(ctx: AfterTransitionContext<StateT, EventT, InputT>) {}

    /** Called when no handler matches the event in the current state. */
    open suspend fun onInvalidTransition(ctx: InvalidTransitionContext<StateT, EventT, InputT>) {}

    /** Called when a terminal state is entered, after onEntry hooks, before the workflow returns. */
    open suspend fun onTerminalStateReached(ctx: TerminalStateReachedContext<StateT, EventT, InputT>) {}

    /** Called when the initial state is entered (cold start, before the event loop begins). */
    open suspend fun onInitialStateEntered(ctx: InitialStateEnteredContext<StateT, InputT>) {}
}

/**
 * Universal middleware that can be registered with any state machine, regardless of the
 * specific [StateT], [EventT], [InputT] types. Registered via `universalMiddleware<MyMiddleware>()`.
 *
 * Use this for cross-machine concerns such as global audit logs or metrics collectors that
 * should apply to multiple state machines with different type parameters.
 *
 * ```kotlin
 * class GlobalAuditMiddleware : UniversalStateMachineMiddleware() {
 *     override suspend fun afterTransition(ctx: AfterTransitionContext<Enum<*>, Any, Any>) {
 *         // ctx.fromState.name and ctx.fromState.ordinal are available without casting
 *     }
 * }
 * ```
 */
@SkipperOpen
abstract class UniversalStateMachineMiddleware : ErasedStateMachineMiddleware() {
    override suspend fun beforeTransitionMiddlewareHook(ctx: BeforeTransitionContext<Enum<*>, Any, Any>) {
        beforeTransition(ctx)
    }

    override suspend fun afterTransitionMiddlewareHook(ctx: AfterTransitionContext<Enum<*>, Any, Any>) {
        afterTransition(ctx)
    }

    override suspend fun onInvalidTransitionMiddlewareHook(ctx: InvalidTransitionContext<Enum<*>, Any, Any>) {
        onInvalidTransition(ctx)
    }

    override suspend fun onTerminalStateReachedMiddlewareHook(ctx: TerminalStateReachedContext<Enum<*>, Any, Any>) {
        onTerminalStateReached(ctx)
    }

    override suspend fun onInitialStateEnteredMiddlewareHook(ctx: InitialStateEnteredContext<Enum<*>, Any>) {
        onInitialStateEntered(ctx)
    }

    /** Called after a handler resolves to Stay/TransitionTo, before hooks run. */
    open suspend fun beforeTransition(ctx: BeforeTransitionContext<Enum<*>, Any, Any>) {}

    /** Called after a successful transition (for Stay and TransitionTo, not Ignore). */
    open suspend fun afterTransition(ctx: AfterTransitionContext<Enum<*>, Any, Any>) {}

    /** Called when no handler matches the event in the current state. */
    open suspend fun onInvalidTransition(ctx: InvalidTransitionContext<Enum<*>, Any, Any>) {}

    /** Called when a terminal state is entered, after onEntry hooks, before the workflow returns. */
    open suspend fun onTerminalStateReached(ctx: TerminalStateReachedContext<Enum<*>, Any, Any>) {}

    /** Called when the initial state is entered (cold start, before the event loop begins). */
    open suspend fun onInitialStateEntered(ctx: InitialStateEnteredContext<Enum<*>, Any>) {}
}
