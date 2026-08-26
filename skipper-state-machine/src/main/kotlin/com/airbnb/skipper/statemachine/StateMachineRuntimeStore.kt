package com.airbnb.skipper.statemachine

import com.airbnb.skipper.statemachine.StateMachineStateCodec.RuntimeState

/**
 * Owns the compact runtime snapshot cache and the single persisted state-machine blob.
 *
 * The workflow facade supplies the actual `@StateField` getter/setter. This class deliberately
 * keeps no Skipper dependency; it only centralizes decode/cache/persist behavior so replay-derived
 * admin history is cleared and rebuilt consistently.
 */
internal class StateMachineRuntimeStore(
    private val workflowId: () -> String,
    private val getPersistedState: () -> SkipperStateMachine.PersistedStateBlob,
    private val setPersistedState: (SkipperStateMachine.PersistedStateBlob) -> Unit,
) {
    private var runtimeStateCache: RuntimeState? = null

    /** Returns the decoded runtime snapshot, decoding the persisted blob at most once per replay. */
    fun runtimeState(): RuntimeState =
        runtimeStateCache ?: StateMachineStateCodec.decode(getPersistedState().payload, workflowId()).also { runtimeStateCache = it }

    /** Encodes and persists [runtimeState] back into the workflow `@StateField`. */
    fun persist(runtimeState: RuntimeState = runtimeState()) {
        runtimeStateCache = runtimeState
        setPersistedState(SkipperStateMachine.PersistedStateBlob(StateMachineStateCodec.encode(runtimeState)))
    }

    /**
     * Clears only the in-memory replay cache. The compact blob is rewritten as journal checkpoint
     * records replay, avoiding a transient persisted state with empty admin history.
     */
    fun resetReplayDerivedHistory() {
        with(StateMachineStateCodec) {
            val state = runtimeState()
            state.clearReplayDerivedHistory()
            runtimeStateCache = state
        }
    }
}
