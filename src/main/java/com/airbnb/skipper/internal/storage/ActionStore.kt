package com.airbnb.skipper.internal.storage

import com.airbnb.skipper.internal.CheckpointTag
import com.airbnb.skipper.internal.api.ActionCheckpoint
import java.util.Optional
import java.util.concurrent.CompletableFuture

interface ActionStore {
    /**
     * Create a new action checkpoint.
     *
     * @param checkpoint
     * @return
     */
    fun createActionCheckpoint(checkpoint: ActionCheckpoint): CompletableFuture<ActionCheckpoint>

    fun getActionCheckpoint(checkpointId: CheckpointTag): CompletableFuture<Optional<ActionCheckpoint>>
}
