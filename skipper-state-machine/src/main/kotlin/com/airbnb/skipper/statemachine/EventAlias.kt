package com.airbnb.skipper.statemachine

/**
 * Pins the alias persisted in the event log for an event class, decoupling it from the Kotlin
 * simple class name.
 *
 * By default an event's persisted alias is its simple class name. That is stable across package
 * moves but not across a class rename — renaming the class orphans the alias stored in in-flight
 * instances' event logs. Annotate a renamed class with its original alias to keep replay working:
 *
 * ```kotlin
 * @EventAlias("ResponseCreated")   // the class's former simple name
 * data class ResponseSubmitted(val id: Long) : MyEvent()
 * ```
 *
 * Once any instance has persisted an alias, that alias must remain stable for the lifetime of those
 * instances. Aliases must be non-blank and unique within a state machine's event hierarchy (the
 * resolver enforces this at startup).
 *
 * See the Evolution guide for the full rename recipe, including
 * [SkipperStateMachine.eventAliasMigrations] for renames that cannot keep the old alias.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class EventAlias(val value: String)
