package com.airbnb.skipper.statemachine

import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactEventRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.RuntimeState
import com.fasterxml.jackson.databind.JsonNode
import kotlin.reflect.KClass
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.full.isSuperclassOf

/**
 * Resolves event type aliases, discovers event class hierarchies, and handles
 * event serialization/deserialization for [SkipperStateMachine].
 *
 * Per-instance because it depends on the state machine's generic `EventT` type parameter and
 * the concrete handler declarations discovered from the state-machine DSL.
 */
internal class StateMachineEventResolver<EventT : StateMachineEvent>(
    private val stateMachineClass: Class<out SkipperStateMachine<*, *, *>>,
    handlerEventClasses: List<KClass<out EventT>>,
    private val aliasMigrations: Map<String, String> = emptyMap(),
) {
    /** Lazily discovered event classes keyed by their compact persisted alias. */
    val eventClassesByAlias: Map<String, Class<out EventT>> by lazy(LazyThreadSafetyMode.NONE) {
        val eventClasses = (
            discoverEventClasses(eventBaseClass()) + handlerEventClasses
        )
            .distinctBy { it.qualifiedName ?: it.simpleName ?: it.toString() }

        val duplicateAliases = eventClasses.groupBy(::eventTypeAlias).filterValues { it.size > 1 }
        require(duplicateAliases.isEmpty()) {
            duplicateAliases.entries.joinToString(
                prefix = "Event type aliases must be unique within ${stateMachineClass.simpleName}: ",
            ) { (alias, classes) ->
                "$alias -> ${classes.joinToString { it.qualifiedName ?: it.simpleName ?: it.toString() }}"
            }
        }

        val byAlias = eventClasses.associateBy(::eventTypeAlias).mapValues { it.value.java }

        // Validate aliasMigrations at startup (symmetric with the duplicate-alias guard above), so a
        // misconfigured rename fails loudly here rather than silently rerouting or failing per-instance
        // at decode time: a migration key must not shadow a live alias, and every value must resolve.
        val shadowingKeys = aliasMigrations.keys.filter { it in byAlias }
        require(shadowingKeys.isEmpty()) {
            "Event alias migration keys must not collide with current event aliases in " +
                "${stateMachineClass.simpleName}: $shadowingKeys"
        }
        val unresolvedTargets = aliasMigrations.filterValues { it !in byAlias }
        require(unresolvedTargets.isEmpty()) {
            "Event alias migration targets must resolve to a current event alias in " +
                "${stateMachineClass.simpleName} (known: ${byAlias.keys.sorted()}): $unresolvedTargets"
        }

        byAlias
    }

    /**
     * Eagerly runs the alias validation that otherwise happens on first event decode: duplicate
     * aliases, plus [aliasMigrations] keys that shadow a live alias or targets that do not resolve.
     *
     * Forcing it at workflow startup (symmetric with `stateNameMigrations` validation) makes a
     * misconfigured `@EventAlias` / `eventAliasMigrations` fail fast there, rather than lazily on the
     * first instance that happens to decode a migrated event.
     */
    fun validateOrThrow() {
        eventClassesByAlias
    }

    /**
     * Returns the stable alias stored in the compact event log for [eventClass].
     *
     * An explicit [EventAlias] takes precedence, which lets an event class be renamed while keeping
     * its persisted alias stable. Otherwise the simple class name is used: it keeps the payload
     * compact and stays stable across package moves. Anonymous/local classes are rejected because
     * they do not have a reliable non-empty persisted name.
     */
    fun eventTypeAlias(eventClass: KClass<out EventT>): String {
        eventClass.annotations.filterIsInstance<EventAlias>().firstOrNull()?.let { alias ->
            require(alias.value.isNotBlank()) {
                "EventAlias value must be non-blank in ${stateMachineClass.simpleName}: " +
                    (eventClass.qualifiedName ?: eventClass.simpleName ?: eventClass.toString())
            }
            return alias.value
        }

        val simpleName = eventClass.simpleName
        if (!simpleName.isNullOrBlank()) {
            return simpleName
        }

        val fallbackName = eventClass.qualifiedName
            ?.substringAfterLast('$')
            ?.substringAfterLast('.')
            .orEmpty()
        require(fallbackName.isNotBlank()) {
            "Event classes must have a stable non-empty name in ${stateMachineClass.simpleName}"
        }
        return fallbackName
    }

    /**
     * Resolves a compact event alias back to the concrete event class for replay/admin decode.
     *
     * A persisted alias is first remapped through [aliasMigrations], so an event class renamed with a
     * new alias still resolves for in-flight instances whose log stores the old alias. Migration
     * targets are validated to resolve at startup (see [eventClassesByAlias]), so a lookup miss here
     * means the persisted alias is neither a current alias nor a migration key.
     */
    fun resolveEventClass(
        alias: String,
        workflowId: String
    ): Class<out EventT> {
        val currentAlias = aliasMigrations[alias] ?: alias
        return eventClassesByAlias[currentAlias]
            ?: error(
                "Unknown event type alias '$alias' for workflow $workflowId. " +
                    "Known aliases: ${eventClassesByAlias.keys.sorted()}. " +
                    "If this event was renamed, keep its old alias via @EventAlias or add an " +
                    "eventAliasMigrations entry.",
            )
    }

    /**
     * Maps a persisted event alias to its current effective alias for admin/debug display.
     *
     * Applies both rename mechanisms in one step: an [aliasMigrations] entry (persisted alias →
     * current alias) and an [EventAlias] rename on the resolved class. Unlike [resolveEventClass],
     * this never throws — an alias that no longer resolves (e.g. a renamed event whose migration
     * entry was dropped after its instances were thought drained) falls back to the raw persisted
     * alias, so an admin/debug view degrades gracefully instead of failing the whole snapshot query.
     */
    fun currentAlias(
        persistedAlias: String,
        workflowId: String
    ): String =
        runCatching { eventTypeAlias(resolveEventClass(persistedAlias, workflowId).kotlin) }
            .getOrDefault(persistedAlias)

    /** Serializes an event instance to a compact JSON tree before the snapshot is Smile-encoded. */
    fun serializeEventPayload(event: EventT): JsonNode? {
        val payload = StateMachineStateCodec.mapper.valueToTree<JsonNode>(event)
        return payload?.takeUnless { it.isNull }
    }

    /**
     * Materializes a compact event-log record into the typed event object used by handlers.
     *
     * Kotlin `object` events are returned from their singleton instance; data-class events are
     * reconstructed from their persisted JSON payload; parameterless class events fall back to a
     * no-arg constructor.
     */
    @Suppress("UNCHECKED_CAST")
    fun materializeEvent(
        eventRecord: CompactEventRecord,
        runtimeState: RuntimeState,
        workflowId: String,
    ): EventT {
        val alias = StateMachineStateCodec.resolveEventTypeAlias(runtimeState, eventRecord.typeId, workflowId)
        val eventClass = resolveEventClass(alias, workflowId)
        singletonInstance(eventClass)?.let { return it as EventT }

        val payload = eventRecord.payload
        return when {
            payload != null -> StateMachineStateCodec.mapper.treeToValue(payload, eventClass)
            else -> eventClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }
    }

    /** Extracts the concrete `EventT` base class from the state-machine generic signature. */
    @Suppress("UNCHECKED_CAST")
    private fun eventBaseClass(): KClass<out EventT>? =
        stateMachineClass.kotlin.allSupertypes
            .firstOrNull { it.classifier == SkipperStateMachine::class }
            ?.arguments
            ?.firstNotNullOfOrNull { arg ->
                (arg.type?.classifier as? KClass<*>)
                    ?.takeIf { StateMachineEvent::class.isSuperclassOf(it) }
            } as? KClass<out EventT>

    /** Recursively discovers concrete sealed or nested event subclasses for alias resolution. */
    @Suppress("UNCHECKED_CAST")
    private fun discoverEventClasses(eventClass: KClass<out EventT>?): List<KClass<out EventT>> {
        if (eventClass == null) {
            return emptyList()
        }

        val sealedEventClasses = eventClass.sealedSubclasses
            .filter { eventClass.isSuperclassOf(it) }
            .map { it as KClass<out EventT> }
        if (sealedEventClasses.isNotEmpty()) {
            return sealedEventClasses.flatMap(::discoverEventClasses)
        }

        val nestedEventClasses = eventClass.nestedClasses
            .filter { eventClass.isSuperclassOf(it) }
            .mapNotNull { it as? KClass<out EventT> }
        if (nestedEventClasses.isEmpty()) {
            return listOf(eventClass).takeUnless { eventClass.isAbstract } ?: emptyList()
        }

        return nestedEventClasses.flatMap(::discoverEventClasses)
    }

    /** Returns the Kotlin singleton instance for `object` event classes, if available. */
    private fun singletonInstance(clazz: Class<*>): Any? = clazz.kotlin.objectInstance
}
