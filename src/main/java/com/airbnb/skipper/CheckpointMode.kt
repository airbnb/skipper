package com.airbnb.skipper

/**
 * Enum representing the different modes of checkpointing that can be applied to an action method.
 */
enum class CheckpointMode {
    /** No checkpointing will be performed after the execution of the action method. */
    NO_CHECKPOINT,

    /**
     * Checkpointing will be performed immediately after the execution of the action method
     * completes. This is the recommended option for use-cases with a small number of actions that
     * want to provide a higher level of idempotence for their actions.
     */
    IMMEDIATE_CHECKPOINT,

    /**
     * Checkpointing will be performed after the workflow execution completes or gets to a state
     * where it cannot move execution further. This is the recommended option for use-cases with
     * lots of actions since it will alleviate I/O on the underlying storage.
     */
    EVENTUAL_CHECKPOINT,

    /** Default checkpointing mode. This will use the default mode set at the Guice module level. */
    DEFAULT
}
