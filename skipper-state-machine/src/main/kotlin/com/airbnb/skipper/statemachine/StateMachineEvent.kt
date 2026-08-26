package com.airbnb.skipper.statemachine

/**
 * Base class for all state machine event types.
 *
 * All `EventT` types used with [SkipperStateMachine] must extend this class. It provides
 * class-based [equals]/[hashCode] that satisfies Skipper's `SimplePojoSerde` round-trip
 * serialization validation: the serde serializes the event, deserializes it back, and
 * checks `equals()`. Jackson's KotlinModule may create a new instance rather than
 * returning the `object` singleton, so reference equality would fail.
 *
 * This base implementation compares by `javaClass`, which is correct for `object` subtypes
 * (parameterless singletons). `data class` subtypes automatically override [equals]/[hashCode]
 * with property-based comparison, so they work naturally without any extra effort.
 *
 * Usage:
 * ```kotlin
 * sealed class MyEvent : StateMachineEvent() {
 *     object Start : MyEvent()                           // uses class-based equals
 *     data class Complete(val reason: String) : MyEvent() // uses data class equals
 * }
 * ```
 */
open class StateMachineEvent {
    override fun equals(other: Any?): Boolean = this === other || this.javaClass == other?.javaClass

    override fun hashCode(): Int = javaClass.hashCode()
}
