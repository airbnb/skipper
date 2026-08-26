package com.airbnb.skipper

/**
 * Set of common, shareable actions to make it possible to create anonymous actions that can be
 * inlined in the workflow code and checkpointed.
 */
internal open class CheckpointHelpers : Actions() {
    /**
     * Checkpoint a runnable lambda.
     *
     * This special type of action must have a checkpoint mode of
     * [CheckpointMode.EVENTUAL_CHECKPOINT]. This is very important because if the workflow state is
     * mutated inside the lambda, if the checkpoint is persisted immediately, then if the workflow
     * update downstream after workflow execution fails, the checkpoint won't be rolled back
     * therefore upon replay, the checkpoint will no longer be executed and the state mutation that
     * happened within the lambda will be lost.
     *
     * @param lambda the lambda to run
     */
    @Execute(checkpointMode = CheckpointMode.EVENTUAL_CHECKPOINT)
    open fun checkpointRunnable(lambda: Runnable) {
        lambda.run()
    }
}
