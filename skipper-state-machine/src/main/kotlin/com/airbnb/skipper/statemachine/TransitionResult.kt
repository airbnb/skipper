package com.airbnb.skipper.statemachine

/**
 * The result of evaluating a transition handler. Transition handlers return one of these
 * to declare what the state machine should do.
 *
 * Provides class-based [equals]/[hashCode] that satisfies Skipper's serde round-trip validation
 * (the same pattern as [StateMachineEvent]). [TransitionTo] overrides with property-based
 * comparison via its data class implementation; [Ignore] overrides with [reason]-based comparison.
 */
sealed class TransitionResult<out StateT> {
    override fun equals(other: Any?): Boolean = this === other || this.javaClass == other?.javaClass

    override fun hashCode(): Int = javaClass.hashCode()

    /** Transition to a new state. */
    data class TransitionTo<StateT>(val newState: StateT) : TransitionResult<StateT>()

    /** Stay in the current state. onExit and onEntry hooks ARE called (re-entering the same state). */
    object Stay : TransitionResult<Nothing>()

    /** Why a transition was ignored — always specified, never defaulted. */
    enum class IgnoreReason {
        /** Handler explicitly returned [ignore]. */
        EXPLICIT,

        /** Guard condition evaluated to false. */
        GUARD_REJECTED,
    }

    /**
     * Ignore this event. No state change, no middleware, no commit.
     *
     * The [reason] distinguishes between an explicit handler `ignore()` call and a guard
     * rejection, enabling the admin view to show why an event was dropped.
     */
    data class Ignore(val reason: IgnoreReason) : TransitionResult<Nothing>()
}
