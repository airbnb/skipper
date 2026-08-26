package com.airbnb.skipper

/**
 * Suspend-compatible checkpoint helpers. Provides an action method that accepts a suspend lambda
 * and is annotated with [Execute] so the Javassist proxy intercepts it and creates a checkpoint.
 *
 * This is the suspend counterpart of [CheckpointHelpers].
 */
open class SuspendCheckpointHelpers : Actions() {
    /**
     * Checkpoint a suspend lambda.
     *
     * This special type of action must have a checkpoint mode of
     * [CheckpointMode.EVENTUAL_CHECKPOINT]. This is very important because if the workflow state
     * is mutated inside the lambda, if the checkpoint is persisted immediately, then if the
     * workflow update downstream after workflow execution fails, the checkpoint won't be rolled
     * back therefore upon replay, the checkpoint will no longer be executed and the state mutation
     * that happened within the lambda will be lost.
     *
     * @param block the suspend lambda to run
     */
    @Execute(checkpointMode = CheckpointMode.EVENTUAL_CHECKPOINT)
    open suspend fun checkpointSuspend(block: suspend () -> Unit) {
        block()
    }
}
