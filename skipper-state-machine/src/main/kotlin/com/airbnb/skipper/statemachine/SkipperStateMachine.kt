package com.airbnb.skipper.statemachine

import com.airbnb.skipper.QueryMethod
import com.airbnb.skipper.SignalMethod
import com.airbnb.skipper.SkipperInjector
import com.airbnb.skipper.SkipperOpen
import com.airbnb.skipper.StateField
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.internal.serde.Serializable
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactEventRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.toEpochNanos
import com.airbnb.skipper.util.injectActionMembers
import java.time.Instant
import javax.inject.Inject

/**
 * Abstract base class for building durable, replay-correct state machines on top of Skipper.
 *
 * Subclasses define states as an Enum, events as a sealed class hierarchy, and an input type,
 * then wire them together using a Kotlin DSL. Skipper handles durability: the event log is
 * persisted, side effects in handlers use checkpointed Actions, and compensation is available
 * through `@Compensate`.
 *
 * ### Replay Correctness
 *
 * The event loop replays from [initialState] every time the workflow executes. The event log
 * (stored inside a compact persisted blob) is an append-only list — events are never removed. On
 * replay, all previously processed events are re-processed from index 0 in the same order.
 * `waitUntil` calls that were previously satisfied return immediately from checkpoint, ensuring
 * action invocations replay with the correct iteration indices.
 *
 * Transition, timeout, after-hook, and lifecycle-progress history are rebuilt from compact journal
 * checkpoint segments. The journal stores Smile + Zstd encoded records as action checkpoint
 * results, so replay returns the original timestamp/span bytes without relying on a skipped
 * `@StateField` mutation.
 *
 * Internally this facade delegates the replay loop, runtime blob cache, transition execution, and
 * journal checkpointing to small package-private collaborators. Public state-machine APIs stay in
 * this class so subclasses only need to implement [define].
 *
 * ### Usage
 * ```kotlin
 * class MyWorkflow : SkipperStateMachine<MyState, MyEvent, MyInput>(MyState.INITIAL) {
 *     private val myActions = actions<MyActions>()
 *
 *     override fun StateMachineBuilder<MyState, MyEvent, MyInput>.define() {
 *         state(INITIAL) { handleInitial() }
 *         state(DONE) { terminal() }
 *     }
 * }
 * ```
 *
 * @param StateT The state enum type. Must be a Kotlin/Java Enum.
 * @param EventT The event type. Must extend [StateMachineEvent]; typically a sealed class.
 * @param InputT The workflow input type.
 * @param initialState The state the machine starts in.
 */
@SkipperOpen
abstract class SkipperStateMachine<StateT, EventT : StateMachineEvent, InputT : Any>(
    private val initialState: StateT,
) : Workflow() where StateT : Enum<StateT> {
    companion object {
        inline fun <reified StateMachineT : SkipperStateMachine<out Enum<*>, out StateMachineEvent, *>, IdT : Any> workflowId(id: IdT) =
            "${StateMachineT::class.simpleName}-$id"
    }

    @Inject
    private lateinit var injector: SkipperInjector

    private val checkpointActions = actions<StateMachineCheckpoint>()

    /**
     * Cached middleware instances. Created once in [runStateMachine] and reused across replays.
     * Not a `@StateField` — middleware proxies are transient and recreated on workflow restart.
     */
    private var cachedMiddlewares: List<ErasedStateMachineMiddleware>? = null

    // ── Precise timestamps ──

    /**
     * Nanosecond-precision timestamp stored as epoch seconds + nanosecond offset (0–999,999,999).
     * Matches [Instant]'s internal representation but stored as primitives for Skipper serde safety
     * and to avoid JSON float64 precision loss on the frontend.
     */
    @Serializable
    data class PreciseTimestamp(
        val epochSecond: Long,
        val nano: Int,
    )

    /** Nanosecond-precision time range. */
    @Serializable
    data class PreciseTimeRange(
        val start: PreciseTimestamp,
        val end: PreciseTimestamp,
    )

    // ── Persisted state ──

    @Serializable
    data class PersistedStateBlob(
        val payload: ByteArray = ByteArray(0),
    ) {
        override fun equals(other: Any?): Boolean = this === other || (other is PersistedStateBlob && payload.contentEquals(other.payload))

        override fun hashCode(): Int = payload.contentHashCode()
    }

    @StateField
    private var persistedState: PersistedStateBlob = PersistedStateBlob()

    // Skipper assigns Workflow.id after construction, so collaborators must read it lazily.
    private val runtimeStore = StateMachineRuntimeStore(
        workflowId = { id },
        getPersistedState = { persistedState },
        setPersistedState = { persistedState = it },
    )

    private val journal = StateMachineJournal<StateT, EventT>(
        workflowId = { id },
        checkpointActions = checkpointActions,
        runtimeStore = runtimeStore,
    )

    @Suppress("UNCHECKED_CAST")
    private val stateClass: Class<StateT> by lazy(LazyThreadSafetyMode.NONE) {
        initialState.declaringJavaClass as Class<StateT>
    }

    private val eventResolver: StateMachineEventResolver<EventT> by lazy(LazyThreadSafetyMode.NONE) {
        StateMachineEventResolver(
            stateMachineClass = stateMachineClass(),
            handlerEventClasses = buildDefinition(availableDefinitionInput()).stateDefinitions.values
                .flatMap { it.builder.handlers }
                .map { it.eventClass },
            aliasMigrations = eventAliasMigrations,
        )
    }

    private val validatedStateNameMigrations: Map<String, String> by lazy(LazyThreadSafetyMode.NONE) {
        validateStateNameMigrations()
    }

    // ── Transition log ──

    /** What outcome a transition attempt had. */
    enum class TransitionOutcome {
        /** transitionTo(newState) — moved to a new state. */
        TRANSITION_TO,

        /** stay() — re-entered the same state (hooks and middleware fire). */
        STAY,

        /** Handler explicitly returned ignore(). */
        IGNORE_EXPLICIT,

        /** Guard condition evaluated to false. */
        IGNORE_GUARD,

        /** No handler registered for this event in the current state. */
        INVALID_NO_HANDLER,
    }

    /** What kind of trigger initiated a transition attempt. */
    enum class TriggerKind { EVENT, TIMEOUT, AUTO_TRANSITION, INITIAL }

    /**
     * Records the outcome and timing of a single transition attempt: the trigger, outcome,
     * from/to states, and wall-clock spans for each step. Ignored events also get entries
     * (with null step spans) so the admin view can show why events were dropped.
     */
    @Serializable
    data class TransitionLogEntry(
        val fromState: String?,
        val toState: String?,
        val outcome: TransitionOutcome,
        val triggerKind: TriggerKind,
        val triggerName: String,
        val span: PreciseTimeRange,
        val handlerSpan: PreciseTimeRange?,
        val eventIndex: Int?,
        val initialMiddlewareSpan: PreciseTimeRange?,
        val beforeMiddlewareSpan: PreciseTimeRange?,
        val onExitSpan: PreciseTimeRange?,
        val onEntrySpan: PreciseTimeRange?,
        val afterMiddlewareSpan: PreciseTimeRange?,
        val terminalMiddlewareSpan: PreciseTimeRange?,
    )

    // ── Configuration ──

    open val invalidTransitionPolicy: InvalidTransitionPolicy = InvalidTransitionPolicy.LOG_AND_IGNORE

    /**
     * Maps a previously-persisted state name to the current enum constant name, so an enum constant
     * can be renamed without breaking in-flight instances whose blob still stores the old name.
     *
     * State identity is persisted as the enum constant's `.name`. Renaming a constant therefore
     * orphans that stored name unless it is remapped here. For example, after renaming `OPEN` to
     * `ACCEPTING`, override:
     *
     * ```kotlin
     * override val stateNameMigrations = mapOf("OPEN" to "ACCEPTING")
     * ```
     *
     * Once an old name has been persisted by any instance, its mapping must remain until those
     * instances drain — treat entries as permanent for the lifetime of affected instances. The
     * default is empty, so this has no effect until a rename is introduced.
     */
    open val stateNameMigrations: Map<String, String> = emptyMap()

    /**
     * Maps a previously-persisted event alias to the current alias, so an event class can be renamed
     * without breaking in-flight instances whose event log still stores the old alias.
     *
     * An event's persisted alias is its [EventAlias] value, or its simple class name when the
     * annotation is absent. The preferred way to rename an event class is to keep its old alias via
     * `@EventAlias("OldName")` on the renamed class; use this map when that is not possible (e.g. the
     * alias must change). For example:
     *
     * ```kotlin
     * override val eventAliasMigrations = mapOf("ResponseCreated" to "ResponseSubmitted")
     * ```
     *
     * The map **value** is the renamed class's *current effective alias* — its [EventAlias] value if
     * it has one, otherwise its simple class name — not necessarily its Kotlin class name. So if the
     * renamed `ResponseSubmitted` also carries `@EventAlias("Foo")`, map to `"Foo"`, not
     * `"ResponseSubmitted"`.
     *
     * Entries must remain until affected instances drain. The default is empty.
     */
    open val eventAliasMigrations: Map<String, String> = emptyMap()

    // ── Abstract: the ONLY thing users implement ──

    abstract fun StateMachineBuilder<StateT, EventT, InputT>.define()

    // ── Provided by the base class ──

    @WorkflowMethod
    open suspend fun execute(input: InputT): StateT = runStateMachine(input)

    @SignalMethod
    open fun sendEvent(event: EventT) {
        stateNameMigrationsOrThrow()
        eventResolver.validateOrThrow()
        with(StateMachineStateCodec) {
            val runtimeState = runtimeStore.runtimeState()
            runtimeState.events.add(
                CompactEventRecord(
                    typeId = runtimeState.eventTypeId(eventResolver.eventTypeAlias(event::class)),
                    payload = eventResolver.serializeEventPayload(event),
                    receivedAtEpochNanos = executionContext.clock.preciseNow().toEpochNanos(),
                ),
            )
            runtimeStore.persist(runtimeState)
        }
    }

    val currentState: StateT
        get() = StateMachineStateCodec.currentStateId(runtimeStore.runtimeState())?.let(::resolveState) ?: initialState

    @QueryMethod
    open fun getState(): StateT = currentState

    @QueryMethod
    open fun isTerminal(): Boolean {
        val builder = buildDefinition(availableDefinitionInput())
        return builder.stateDefinitions[currentState]?.builder?.isTerminal == true
    }

    /** Returns the complete admin snapshot for debugging and visualization. */
    @QueryMethod
    open fun getAdminSnapshot(): StateMachineAdminSnapshot.AdminSnapshot {
        val runtimeState = runtimeStore.runtimeState()
        return StateMachineAdminSnapshot.assemble(
            runtimeState = runtimeState,
            builder = buildDefinition(availableDefinitionInput()),
            currentStateName = StateMachineStateCodec.currentStateId(runtimeState)?.let(::resolveState)?.name,
            currentStateEntryEpochNanos = StateMachineStateCodec.currentStateEntryEpochNanos(runtimeState),
            eventResolver = eventResolver,
            workflowId = id,
            stateNameMigrations = stateNameMigrationsOrThrow(),
        )
    }

    /**
     * The replay-correct state machine event loop.
     *
     * On every execution, including replay, the loop starts from [initialState] and processes all
     * persisted events from index 0. This ensures Skipper's internal action/timer counters align
     * with checkpoint data.
     */
    private suspend fun runStateMachine(input: InputT): StateT {
        stateNameMigrationsOrThrow()
        eventResolver.validateOrThrow()
        val builder = buildDefinition(input)
        builder.validateOrThrow()

        val middlewares = getOrCreateMiddlewares(builder.middlewareClasses)
        return StateMachineEventLoop(
            initialState = initialState,
            workflowId = { id },
            clock = { executionContext.clock },
            runtimeStore = runtimeStore,
            eventResolver = eventResolver,
            journal = journal,
            transitionExecutor = transitionExecutor(),
            waitUntilCondition = { condition -> waitUntil(condition) },
            waitUntilConditionWithTimeout = { condition, timeout -> waitUntil(condition, timeout) },
        ).run(input, builder, middlewares)
    }

    private fun transitionExecutor(): StateMachineTransitionExecutor<StateT, EventT, InputT> =
        StateMachineTransitionExecutor(
            clock = { executionContext.clock },
            journal = journal,
            stateMachineId = { id },
            stateMachineClass = { stateMachineClass() },
            invalidTransitionPolicy = { invalidTransitionPolicy },
            checkpoint = { block -> checkpoint { block() } },
        )

    /**
     * Creates middleware instances via [actions] and injects their Guice dependencies.
     *
     * [Workflow.actions] creates Javassist proxies but does NOT perform Guice injection.
     * Normally, [com.airbnb.skipper.util.injectWorkflowMembers] handles this by walking
     * the workflow's fields for [com.airbnb.skipper.Actions] instances — but middleware are
     * created at runtime (not at construction), so they're invisible to that mechanism.
     */
    private fun getOrCreateMiddlewares(middlewareClasses: List<Class<out ErasedStateMachineMiddleware>>): List<ErasedStateMachineMiddleware> {
        return cachedMiddlewares ?: middlewareClasses.map { clazz ->
            actions(clazz).also { injector.injectActionMembers(it) }
        }.also { cachedMiddlewares = it }
    }

    private fun resolveState(stateId: Int): StateT {
        val persistedName = StateMachineStateCodec.resolveStateName(runtimeStore.runtimeState(), stateId, id)
        val currentName = stateNameMigrationsOrThrow()[persistedName] ?: persistedName
        return stateClass.enumConstants.firstOrNull { it.name == currentName }
            ?: error(
                "No state enum constant named '$currentName' for workflow $id" +
                    (if (currentName != persistedName) " (migrated from persisted name '$persistedName')" else "") +
                    ". If this state was renamed, add a stateNameMigrations entry mapping the persisted " +
                    "name to the current constant.",
            )
    }

    private fun stateNameMigrationsOrThrow(): Map<String, String> = validatedStateNameMigrations

    private fun validateStateNameMigrations(): Map<String, String> {
        val currentStateNames = stateClass.enumConstants.map { it.name }.toSet()

        val blankEntries = stateNameMigrations.filter { (oldName, currentName) ->
            oldName.isBlank() || currentName.isBlank()
        }
        require(blankEntries.isEmpty()) {
            "State name migration entries must be non-blank in ${stateMachineClass().simpleName}: $blankEntries"
        }

        val shadowingKeys = stateNameMigrations.keys.filter { it in currentStateNames }
        require(shadowingKeys.isEmpty()) {
            "State name migration keys must be old persisted names, not current state enum constants in " +
                "${stateMachineClass().simpleName}: $shadowingKeys"
        }

        val unresolvedTargets = stateNameMigrations.filterValues { it !in currentStateNames }
        require(unresolvedTargets.isEmpty()) {
            "State name migration targets must resolve to a current state enum constant in " +
                "${stateMachineClass().simpleName} (known: ${currentStateNames.sorted()}): $unresolvedTargets"
        }

        return stateNameMigrations
    }

    @Suppress("UNCHECKED_CAST")
    private fun availableDefinitionInput(): InputT? {
        workflowExecutionInput()?.let { return it as InputT }
        val persistedWorkflow = skipperEngine.getWorkflow(id)
        if (persistedWorkflow.isEmpty) {
            return null
        }
        return persistedWorkflow.get().input as InputT?
    }

    private fun workflowExecutionInput(): Any? =
        try {
            executionContext.workflow.input
        } catch (e: UninitializedPropertyAccessException) {
            null
        }

    private fun buildDefinition(input: InputT?): StateMachineBuilder<StateT, EventT, InputT> {
        return StateMachineBuilder<StateT, EventT, InputT>().also {
            it.definitionInput = input
            it.define()
        }
    }

    /** Returns the state machine class for use in middleware contexts. */
    @Suppress("UNCHECKED_CAST")
    private fun stateMachineClass(): Class<out SkipperStateMachine<*, *, *>> = this::class.java as Class<out SkipperStateMachine<*, *, *>>
}
