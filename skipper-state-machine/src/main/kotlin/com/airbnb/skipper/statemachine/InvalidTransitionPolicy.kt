package com.airbnb.skipper.statemachine

/**
 * Controls what happens when an event arrives that has no matching handler for the current state.
 */
enum class InvalidTransitionPolicy {
    /** Log a warning and ignore the event. This is the default. */
    LOG_AND_IGNORE,

    /** Throw a [com.airbnb.skipper.NonRetryableError], stopping the workflow permanently. */
    THROW,
}
