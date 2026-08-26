package com.airbnb.skipper.statemachine

/**
 * Top-level DSL builder for defining state machine behavior.
 *
 * Invoked synchronously inside [SkipperStateMachine.define]. Handlers and hooks registered here
 * are stored as lambdas and executed asynchronously when events arrive.
 *
 * ```kotlin
 * override fun StateMachineBuilder<MyState, MyEvent, MyInput>.define() {
 *     state(IDLE) { handleIdle() }
 *     state(RUNNING) { handleRunning() }
 *     state(DONE) { terminal() }
 * }
 * ```
 *
 * @param StateT The state enum type.
 * @param EventT The event type. Must extend [StateMachineEvent]; typically a sealed class.
 * @param InputT The workflow input type.
 */
class StateMachineBuilder<StateT : Enum<StateT>, EventT : StateMachineEvent, InputT : Any> {
    data class StateDefinition<StateT : Enum<StateT>, EventT : StateMachineEvent, InputT : Any>(
        val builder: StateBuilder<StateT, EventT, InputT>,
    )

    private val mutableStateDefinitions = linkedMapOf<StateT, StateDefinition<StateT, EventT, InputT>>()

    @PublishedApi
    internal val mutableMiddlewareClasses = mutableListOf<Class<out ErasedStateMachineMiddleware>>()

    val stateDefinitions: Map<StateT, StateDefinition<StateT, EventT, InputT>> get() = mutableStateDefinitions
    val middlewareClasses: List<Class<out ErasedStateMachineMiddleware>> get() = mutableMiddlewareClasses

    /** Reference to the input, set by SkipperStateMachine before define() is called. */
    @PublishedApi
    internal var definitionInput: InputT? = null

    /**
     * The workflow input, for branching the **shape** of `define()` across deploys.
     *
     * Because the input is fixed for the life of an instance and identical on every replay, branching
     * the set of states/hooks you declare on an input field (e.g. a `schemaVersion`) is replay-stable
     * with no checkpoint — old instances keep their original shape, new instances get the new one:
     *
     * ```kotlin
     * override fun StateMachineBuilder<S, E, Input>.define() {
     *     state(OPEN) { handleOpen() }
     *     if (input.schemaVersion >= 2) state(REVIEW) { handleReview() }
     *     state(DONE) { terminal() }
     * }
     * ```
     *
     * To branch *behavior* (not shape) for new vs. in-flight instances, call `version()` inside a
     * handler/hook body instead. `define()` also runs in query contexts (`getAdminSnapshot`,
     * `isTerminal`), where the original workflow input is available for shape but there is no
     * checkpoint engine for `version()`.
     */
    val input: InputT
        get() = definitionInput
            ?: error(
                "input is only available while defining an existing workflow with a stored input. " +
                    "For behavior changes, use version() inside a handler body instead of calling " +
                    "version() in define().",
            )

    /**
     * Define behavior for a specific state.
     *
     * Convention: delegate to a per-state handler function to keep [define] scannable.
     *
     * ```kotlin
     * state(OPEN) { handleOpen() }
     * state(FULFILLED) { terminal() }
     * ```
     */
    fun state(
        state: StateT,
        init: StateBuilder<StateT, EventT, InputT>.() -> Unit,
    ) {
        val builder = StateBuilder<StateT, EventT, InputT>().apply {
            definitionInput = this@StateMachineBuilder.definitionInput
            init()
        }
        mutableStateDefinitions[state] = StateDefinition(builder)
    }

    /**
     * Register middleware for cross-cutting concerns (metrics, logging, audit).
     * Middleware execute in registration order. The class must extend [StateMachineMiddleware]
     * and will be instantiated by Skipper's action proxy system (supporting Guice injection).
     *
     * ```kotlin
     * middleware<MetricsMiddleware>()
     * middleware<AuditLogMiddleware>()
     * ```
     */
    inline fun <reified MiddlewareT : StateMachineMiddleware<StateT, EventT, InputT>> middleware() {
        mutableMiddlewareClasses.add(MiddlewareT::class.java)
    }

    /**
     * Register universal middleware for cross-cutting concerns that apply across multiple state
     * machines with different type parameters. Middleware execute in registration order.
     * The class must extend [UniversalStateMachineMiddleware] and will be instantiated by Skipper's
     * action proxy system (supporting Guice injection).
     *
     * ```kotlin
     * universalMiddleware<GlobalAuditMiddleware>()
     * ```
     */
    inline fun <reified MiddlewareT : UniversalStateMachineMiddleware> universalMiddleware() {
        mutableMiddlewareClasses.add(MiddlewareT::class.java)
    }

    // ── Validation ──

    /**
     * Validate the state machine definition. Returns a list of error/warning messages.
     * Empty list means the definition is valid.
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        for ((state, def) in mutableStateDefinitions) {
            val sb = def.builder
            val afterHooksInDeclarationOrder = sb.afterHooks.sortedBy { it.ordinal }

            // Rule 1: State will hang — no handlers, no terminal, no auto-transition, no timeout
            if (!sb.isTerminal && sb.immediateTransitionTo == null &&
                sb.handlers.isEmpty() && sb.timeout == null
            ) {
                errors.add("State $state has no event handlers, timeout, terminal(), or immediatelyTransitionTo — workflow will hang in this state")
            }

            // Rule 2: immediatelyTransitionTo target must exist
            val target = sb.immediateTransitionTo
            if (target != null && !mutableStateDefinitions.containsKey(target)) {
                errors.add("State $state has immediatelyTransitionTo($target) but state $target is not defined")
            }

            // Rule 3: Terminal with handlers (warning)
            if (sb.isTerminal && sb.handlers.isNotEmpty()) {
                errors.add("Terminal state $state has ${sb.handlers.size} event handler(s) that will never execute")
            }

            // Rule 4: after() durations must be positive
            for ((index, afterHook) in afterHooksInDeclarationOrder.withIndex()) {
                if (afterHook.duration.isNegative || afterHook.duration.isZero) {
                    errors.add("State $state has after[$index] with non-positive duration ${afterHook.duration}")
                }
            }

            // Rule 5: after() durations must be < timeout duration (if timeout has a fixed duration).
            // Only checked for a literal timeout. An input-computed timeout is intentionally skipped:
            // its duration can vary per state entry (the lambda reads replay-deterministic state like
            // a step index), so a single build-time evaluation would validate one arbitrary iteration
            // and could even invoke the lambda with an unexpected index. The author owns that
            // relationship, as documented on the lambda timeout() overload.
            val timeoutDuration = sb.timeout?.staticDuration
            if (timeoutDuration != null) {
                for ((index, afterHook) in afterHooksInDeclarationOrder.withIndex()) {
                    if (afterHook.duration >= timeoutDuration) {
                        errors.add(
                            "State $state has after[$index] with duration " +
                                "${afterHook.duration} >= timeout duration " +
                                "$timeoutDuration — the hook will never fire",
                        )
                    }
                }
            }

            // Rule 6: after() declarations must appear before timeout() so timer code reads in order.
            val timeout = sb.timeout
            if (timeout != null) {
                for ((index, afterHook) in afterHooksInDeclarationOrder.withIndex()) {
                    if (afterHook.timerDeclarationOrdinal > timeout.timerDeclarationOrdinal) {
                        errors.add(
                            "State $state declares after[$index] after timeout(); declare all after() " +
                                "hooks before timeout() so timers read in execution order",
                        )
                    }
                }
            }

            // Rule 7: after() durations must be unique within a state
            val afterDurations = afterHooksInDeclarationOrder.map { it.duration }
            val duplicateAfterDurations = afterDurations.groupBy { it }.filter { it.value.size > 1 }.keys
            for (dup in duplicateAfterDurations) {
                errors.add("State $state has multiple after() hooks with the same duration $dup")
            }

            // Rule 8: after() hooks must be declared in the same order they can fire.
            afterHooksInDeclarationOrder.zipWithNext().forEachIndexed { index, (previous, current) ->
                if (current.duration < previous.duration) {
                    errors.add(
                        "State $state has after[${index + 1}] with duration ${current.duration} following " +
                            "after[$index] with longer duration ${previous.duration}; after() hooks must be " +
                            "declared in increasing duration order so replay fires hooks in the same order as " +
                            "fresh execution",
                    )
                }
            }

            // Rule 9: after() hook ids must be unique within a state (an explicit id must not collide
            // with another explicit id or with a derived ordinal id like "#0").
            val duplicateHookIds = afterHooksInDeclarationOrder
                .map { it.hookId }
                .groupBy { it }
                .filter { it.value.size > 1 }
                .keys
            for (dup in duplicateHookIds) {
                errors.add("State $state has multiple after() hooks with the same id '$dup'")
            }
        }

        // Rule 10: immediatelyTransitionTo cycle detection (cross-state, so it runs after the
        // per-state loop) — each cycle reported exactly once
        val alreadyChecked = mutableSetOf<StateT>()
        for ((state, def) in mutableStateDefinitions) {
            if (state in alreadyChecked) continue
            val target = def.builder.immediateTransitionTo ?: continue
            val visited = linkedSetOf<StateT>()
            visited.add(state)
            var current: StateT? = target
            while (current != null) {
                if (current in alreadyChecked) break
                if (!visited.add(current)) {
                    errors.add("immediatelyTransitionTo forms a cycle: ${visited.joinToString(" → ")} → $current")
                    break
                }
                current = mutableStateDefinitions[current]?.builder?.immediateTransitionTo
            }
            alreadyChecked.addAll(visited)
        }

        return errors
    }

    /**
     * Validate and throw [StateMachineValidationException] if any errors are found.
     */
    fun validateOrThrow() {
        val errors = validate()
        if (errors.isNotEmpty()) {
            throw StateMachineValidationException("State machine validation failed:\n${errors.joinToString("\n  - ", prefix = "  - ")}")
        }
    }

    /** Thrown when [validateOrThrow] finds one or more errors in the state machine definition. */
    class StateMachineValidationException(message: String) : IllegalStateException(message)
}
