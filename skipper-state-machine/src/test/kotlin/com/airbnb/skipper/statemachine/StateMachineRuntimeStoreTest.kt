package com.airbnb.skipper.statemachine

import com.airbnb.skipper.statemachine.SkipperStateMachine.TransitionOutcome
import com.airbnb.skipper.statemachine.SkipperStateMachine.TriggerKind
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactAfterHookRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactEventRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactSpan
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactTimeoutRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactTransitionRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.RuntimeState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StateMachineRuntimeStoreTest {
    @Test
    fun `runtimeState decodes the persisted blob lazily`() {
        val harness = StateMachineRuntimeHarness()
        val persisted = RuntimeState(stateNames = mutableListOf("IDLE"))
        harness.persistedState = SkipperStateMachine.PersistedStateBlob(StateMachineStateCodec.encode(persisted))

        assertThat(harness.runtimeStore.runtimeState().stateNames).containsExactly("IDLE")
    }

    @Test
    fun `persist writes compact bytes and keeps the runtime cache current`() {
        val harness = StateMachineRuntimeHarness()
        val state = RuntimeState()
        with(StateMachineStateCodec) {
            state.stateId("IDLE")
            state.eventTypeId("Go")
        }

        harness.runtimeStore.persist(state)

        val decoded = StateMachineStateCodec.decode(harness.persistedState.payload, harness.workflowId)
        assertThat(decoded.stateNames).containsExactly("IDLE")
        assertThat(decoded.eventTypeNames).containsExactly("Go")
        assertThat(harness.runtimeStore.runtimeState()).isSameAs(state)
    }

    @Test
    fun `runtimeState uses the in-memory cache until persist replaces it`() {
        val harness = StateMachineRuntimeHarness()
        val first = harness.runtimeStore.runtimeState()
        first.stateNames.add("CACHED")
        harness.persistedState = SkipperStateMachine.PersistedStateBlob(
            StateMachineStateCodec.encode(RuntimeState(stateNames = mutableListOf("EXTERNAL"))),
        )

        assertThat(harness.runtimeStore.runtimeState().stateNames).containsExactly("CACHED")
    }

    @Test
    fun `resetReplayDerivedHistory clears only replay-derived cached history`() {
        val harness = StateMachineRuntimeHarness()
        val state = RuntimeState()
        state.stateNames.add("IDLE")
        state.eventTypeNames.add("Go")
        state.events.add(CompactEventRecord(typeId = 0, receivedAtEpochNanos = 10L))
        state.afterHooks.add(CompactAfterHookRecord(firedAtEpochNanos = 20L, durationNanos = 1L))
        state.timeouts.add(CompactTimeoutRecord(firedAtEpochNanos = 30L, durationNanos = 2L))
        state.transitions.add(
            CompactTransitionRecord(
                toStateId = 0,
                outcome = TransitionOutcome.TRANSITION_TO,
                triggerKind = TriggerKind.INITIAL,
                stateEntryEpochNanos = 40L,
                span = CompactSpan(startEpochNanos = 0L, durationNanos = 40L),
            ),
        )
        harness.runtimeStore.persist(state)
        val persistedBeforeReset = harness.persistedState

        harness.runtimeStore.resetReplayDerivedHistory()

        val cached = harness.runtimeStore.runtimeState()
        assertThat(cached.stateNames).containsExactly("IDLE")
        assertThat(cached.eventTypeNames).containsExactly("Go")
        assertThat(cached.events).hasSize(1)
        assertThat(cached.afterHooks).isEmpty()
        assertThat(cached.timeouts).isEmpty()
        assertThat(cached.transitions).isEmpty()
        assertThat(harness.persistedState).isEqualTo(persistedBeforeReset)
    }
}
