package com.airbnb.skipper.statemachine

import com.airbnb.skipper.statemachine.SkipperStateMachine.TransitionOutcome
import com.airbnb.skipper.statemachine.SkipperStateMachine.TriggerKind
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactAfterHookRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactEventRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactSpan
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactTimeoutRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactTransitionRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.RuntimeState
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StateMachineAdminSnapshotTest {
    private enum class SnapshotTestState { IDLE, RUNNING, DONE }

    private sealed class SnapshotTestEvent : StateMachineEvent() {
        object Go : SnapshotTestEvent()

        data class Complete(val reason: String = "done") : SnapshotTestEvent()

        /**
         * A renamed event: the class is now `Finished` but its persisted alias stays "LegacyDone" via
         * [EventAlias]. The alias deliberately differs from the simple class name so eventHistory tests
         * can prove `eventType` is the persisted/effective alias (what the transition log shows) rather
         * than the materialized class's simple name.
         */
        @EventAlias("LegacyDone")
        object Finished : SnapshotTestEvent()
    }

    @Suppress("UNCHECKED_CAST")
    private open class SnapshotTestStateMachine : SkipperStateMachine<SnapshotTestState, SnapshotTestEvent, String>(SnapshotTestState.IDLE) {
        override fun StateMachineBuilder<SnapshotTestState, SnapshotTestEvent, String>.define() {
            state(SnapshotTestState.IDLE) {
                on<SnapshotTestEvent.Go> { _, _ -> transitionTo(SnapshotTestState.RUNNING) }
                after(Duration.ofMinutes(5)) { _ -> }
                timeout(Duration.ofMinutes(10)) { transitionTo(SnapshotTestState.DONE) }
            }
            state(SnapshotTestState.RUNNING) {
                on<SnapshotTestEvent.Complete> { _, _ -> transitionTo(SnapshotTestState.DONE) }
            }
            state(SnapshotTestState.DONE) { terminal() }
        }
    }

    private fun buildTestBuilder(): StateMachineBuilder<SnapshotTestState, SnapshotTestEvent, String> {
        val builder = StateMachineBuilder<SnapshotTestState, SnapshotTestEvent, String>()
        with(SnapshotTestStateMachine()) {
            builder.define()
        }
        return builder
    }

    /** IDLE declares two after hooks (5m -> "#0", 10m -> "#1") so pending-timer matching by hook id is testable. */
    private open class TwoAfterHookStateMachine : SkipperStateMachine<SnapshotTestState, SnapshotTestEvent, String>(SnapshotTestState.IDLE) {
        override fun StateMachineBuilder<SnapshotTestState, SnapshotTestEvent, String>.define() {
            state(SnapshotTestState.IDLE) {
                on<SnapshotTestEvent.Go> { _, _ -> transitionTo(SnapshotTestState.RUNNING) }
                after(Duration.ofMinutes(5)) { _ -> }
                after(Duration.ofMinutes(10)) { _ -> }
                timeout(Duration.ofMinutes(15)) { transitionTo(SnapshotTestState.DONE) }
            }
            state(SnapshotTestState.RUNNING) {
                on<SnapshotTestEvent.Complete> { _, _ -> transitionTo(SnapshotTestState.DONE) }
            }
            state(SnapshotTestState.DONE) { terminal() }
        }
    }

    private fun buildTwoAfterHookBuilder(): StateMachineBuilder<SnapshotTestState, SnapshotTestEvent, String> {
        val builder = StateMachineBuilder<SnapshotTestState, SnapshotTestEvent, String>()
        with(TwoAfterHookStateMachine()) {
            builder.define()
        }
        return builder
    }

    /**
     * IDLE has an after hook (5m) plus an input-computed (lambda) timeout. The dynamic timeout has no
     * statically-known duration, so admin/query contexts — which have no workflow input — must omit
     * its pending deadline while still rendering the after hook.
     */
    private open class DynamicTimeoutStateMachine : SkipperStateMachine<SnapshotTestState, SnapshotTestEvent, String>(SnapshotTestState.IDLE) {
        override fun StateMachineBuilder<SnapshotTestState, SnapshotTestEvent, String>.define() {
            state(SnapshotTestState.IDLE) {
                on<SnapshotTestEvent.Go> { _, _ -> transitionTo(SnapshotTestState.RUNNING) }
                after(Duration.ofMinutes(5)) { _ -> }
                timeout({ _ -> Duration.ofMinutes(10) }) { transitionTo(SnapshotTestState.DONE) }
            }
            state(SnapshotTestState.RUNNING) {
                on<SnapshotTestEvent.Complete> { _, _ -> transitionTo(SnapshotTestState.DONE) }
            }
            state(SnapshotTestState.DONE) { terminal() }
        }
    }

    private fun buildDynamicTimeoutBuilder(): StateMachineBuilder<SnapshotTestState, SnapshotTestEvent, String> {
        val builder = StateMachineBuilder<SnapshotTestState, SnapshotTestEvent, String>()
        with(DynamicTimeoutStateMachine()) {
            builder.define()
        }
        return builder
    }

    private fun createResolver(aliasMigrations: Map<String, String> = emptyMap()): StateMachineEventResolver<SnapshotTestEvent> {
        val builder = buildTestBuilder()
        return StateMachineEventResolver(
            stateMachineClass = SnapshotTestStateMachine::class.java,
            handlerEventClasses = builder.stateDefinitions.values
                .flatMap { it.builder.handlers }
                .map { it.eventClass },
            aliasMigrations = aliasMigrations,
        )
    }

    // ── Tests ──

    @Test
    fun `assemble returns empty snapshot for empty state`() {
        val snapshot = StateMachineAdminSnapshot.assemble(
            runtimeState = RuntimeState(),
            builder = buildTestBuilder(),
            currentStateName = null,
            currentStateEntryEpochNanos = null,
            eventResolver = createResolver(),
            workflowId = "wf-1",
        )

        assertThat(snapshot.currentState).isNull()
        assertThat(snapshot.stateEntryTime).isNull()
        assertThat(snapshot.stateHistory).isEmpty()
        assertThat(snapshot.eventHistory).isEmpty()
        assertThat(snapshot.afterHookHistory).isEmpty()
        assertThat(snapshot.timeoutHistory).isEmpty()
        assertThat(snapshot.transitionHistory).isEmpty()
        assertThat(snapshot.pendingTimerDeadlines).isEmpty()
    }

    @Test
    fun `assemble maps state transitions to stateHistory`() {
        val rs = RuntimeState()
        with(StateMachineStateCodec) {
            rs.stateId("IDLE")
            rs.stateId("RUNNING")
        }
        rs.transitions.add(
            CompactTransitionRecord(
                fromStateId = null,
                toStateId = 0,
                outcome = TransitionOutcome.TRANSITION_TO,
                triggerKind = TriggerKind.INITIAL,
                stateEntryEpochNanos = 1_000_000_000_000_000_000L,
                span = CompactSpan(startEpochNanos = 0L, durationNanos = 1_000_000_000L),
            ),
        )
        rs.transitions.add(
            CompactTransitionRecord(
                fromStateId = 0,
                toStateId = 1,
                outcome = TransitionOutcome.TRANSITION_TO,
                triggerKind = TriggerKind.EVENT,
                stateEntryEpochNanos = 1_000_000_002_000_000_000L,
                span = CompactSpan(startEpochNanos = 1_000_000_000_000_000_000L, durationNanos = 2_000_000_000L),
            ),
        )

        val snapshot = StateMachineAdminSnapshot.assemble(
            runtimeState = rs,
            builder = buildTestBuilder(),
            currentStateName = "RUNNING",
            currentStateEntryEpochNanos = 1_000_000_002_000_000_000L,
            eventResolver = createResolver(),
            workflowId = "wf-1",
        )

        assertThat(snapshot.stateHistory).hasSize(2)
        assertThat(snapshot.stateHistory[0].state).isEqualTo("IDLE")
        assertThat(snapshot.stateHistory[1].state).isEqualTo("RUNNING")
    }

    @Test
    fun `assemble maps events to eventHistory`() {
        val rs = RuntimeState()
        with(StateMachineStateCodec) {
            rs.eventTypeId("Go")
        }
        rs.events.add(CompactEventRecord(typeId = 0, payload = null, receivedAtEpochNanos = 5_000_000_000L))

        val snapshot = StateMachineAdminSnapshot.assemble(
            runtimeState = rs,
            builder = buildTestBuilder(),
            currentStateName = null,
            currentStateEntryEpochNanos = null,
            eventResolver = createResolver(),
            workflowId = "wf-1",
        )

        assertThat(snapshot.eventHistory).hasSize(1)
        assertThat(snapshot.eventHistory[0].eventType).isEqualTo("Go")
    }

    // ── eventHistory rename consistency + tolerant payload materialization ──

    @Test
    fun `assemble shows the event's effective alias in eventHistory, agreeing with its transition row`() {
        // A renamed event (`Finished`) keeps its persisted alias "LegacyDone" via @EventAlias. eventType
        // must be that effective alias — what the EVENT transition row also shows — not the materialized
        // class's simple name ("Finished"). This is the consistency the fix guarantees: the eventHistory
        // and transitionHistory views never disagree about a renamed event's name.
        val rs = RuntimeState()
        with(StateMachineStateCodec) {
            rs.stateId("IDLE")
            rs.stateId("RUNNING")
            rs.eventTypeId("LegacyDone")
        }
        rs.events.add(CompactEventRecord(typeId = 0, payload = null, receivedAtEpochNanos = 1_500_000_000L))
        rs.transitions.add(
            CompactTransitionRecord(
                fromStateId = 0,
                toStateId = 1,
                outcome = TransitionOutcome.TRANSITION_TO,
                triggerKind = TriggerKind.EVENT,
                stateEntryEpochNanos = 2_000_000_000L,
                eventIndex = 0,
                span = CompactSpan(startEpochNanos = 1_000_000_000L, durationNanos = 1_000_000_000L),
            ),
        )

        val snapshot = StateMachineAdminSnapshot.assemble(
            runtimeState = rs,
            builder = buildTestBuilder(),
            currentStateName = "RUNNING",
            currentStateEntryEpochNanos = 2_000_000_000L,
            eventResolver = createResolver(),
            workflowId = "wf-1",
        )

        assertThat(snapshot.eventHistory).hasSize(1)
        assertThat(snapshot.eventHistory[0].eventType).isEqualTo("LegacyDone")
        // The same renamed event, rendered in the transition log, carries the identical name.
        val eventTriggerName = snapshot.transitionHistory.single { it.triggerKind == TriggerKind.EVENT }.triggerName
        assertThat(snapshot.eventHistory[0].eventType).isEqualTo(eventTriggerName)
    }

    @Test
    fun `assemble tolerates an unresolvable event alias with a fallback eventType and null payload`() {
        // An alias that is neither a current alias nor a migration key (e.g. a renamed event whose
        // migration entry was dropped) must not fail the whole snapshot. eventType degrades to the raw
        // persisted alias and payload becomes null, instead of throwing out of materializeEvent.
        val rs = RuntimeState()
        with(StateMachineStateCodec) {
            rs.eventTypeId("DroppedEvent")
        }
        rs.events.add(CompactEventRecord(typeId = 0, payload = null, receivedAtEpochNanos = 7_000_000_000L))

        val snapshot = StateMachineAdminSnapshot.assemble(
            runtimeState = rs,
            builder = buildTestBuilder(),
            currentStateName = null,
            currentStateEntryEpochNanos = null,
            eventResolver = createResolver(),
            workflowId = "wf-1",
        )

        assertThat(snapshot.eventHistory).hasSize(1)
        assertThat(snapshot.eventHistory[0].eventType).isEqualTo("DroppedEvent")
        assertThat(snapshot.eventHistory[0].payload).isNull()
    }

    @Test
    fun `assemble maps afterHooks to afterHookHistory`() {
        val rs = RuntimeState()
        rs.afterHooks.add(
            CompactAfterHookRecord(
                firedAtEpochNanos = 10_000_000_000L,
                durationNanos = Duration.ofMinutes(5).toNanos(),
                handlerSpan = CompactSpan(startEpochNanos = 10_000_000_000L, durationNanos = 500_000_000L),
            ),
        )

        val snapshot = StateMachineAdminSnapshot.assemble(
            runtimeState = rs,
            builder = buildTestBuilder(),
            currentStateName = null,
            currentStateEntryEpochNanos = null,
            eventResolver = createResolver(),
            workflowId = "wf-1",
        )

        assertThat(snapshot.afterHookHistory).hasSize(1)
        assertThat(snapshot.afterHookHistory[0].duration).isEqualTo(Duration.ofMinutes(5))
        assertThat(snapshot.afterHookHistory[0].handlerSpan).isNotNull
    }

    @Test
    fun `assemble maps timeouts to timeoutHistory`() {
        val rs = RuntimeState()
        rs.timeouts.add(
            CompactTimeoutRecord(
                firedAtEpochNanos = 20_000_000_000L,
                durationNanos = Duration.ofMinutes(10).toNanos(),
            ),
        )

        val snapshot = StateMachineAdminSnapshot.assemble(
            runtimeState = rs,
            builder = buildTestBuilder(),
            currentStateName = null,
            currentStateEntryEpochNanos = null,
            eventResolver = createResolver(),
            workflowId = "wf-1",
        )

        assertThat(snapshot.timeoutHistory).hasSize(1)
        assertThat(snapshot.timeoutHistory[0].duration).isEqualTo(Duration.ofMinutes(10))
    }

    @Test
    fun `assemble computes pending timers when currentStateName and entryTime are set`() {
        val rs = RuntimeState()
        val entryNanos = 1_000_000_000_000_000_000L

        val snapshot = StateMachineAdminSnapshot.assemble(
            runtimeState = rs,
            builder = buildTestBuilder(),
            currentStateName = "IDLE",
            currentStateEntryEpochNanos = entryNanos,
            eventResolver = createResolver(),
            workflowId = "wf-1",
        )

        assertThat(snapshot.pendingTimerDeadlines).hasSize(2)
        val types = snapshot.pendingTimerDeadlines.map { it.type }
        assertThat(types).containsExactly("after", "timeout")
    }

    @Test
    fun `assemble omits the pending timer for an input-computed timeout but keeps other timers`() {
        // A lambda timeout has no staticDuration, so without the workflow input there is no deadline to
        // show. The after hook (which has a fixed duration) must still render, proving only the dynamic
        // timeout is suppressed — not all of the state's timers.
        val rs = RuntimeState()
        val entryNanos = 1_000_000_000_000_000_000L

        val snapshot = StateMachineAdminSnapshot.assemble(
            runtimeState = rs,
            builder = buildDynamicTimeoutBuilder(),
            currentStateName = "IDLE",
            currentStateEntryEpochNanos = entryNanos,
            eventResolver = createResolver(),
            workflowId = "wf-1",
        )

        val types = snapshot.pendingTimerDeadlines.map { it.type }
        assertThat(types).containsExactly("after")
        assertThat(types).doesNotContain("timeout")
    }

    @Test
    fun `assemble returns empty pending timers when no current state`() {
        val rs = RuntimeState()

        val snapshot = StateMachineAdminSnapshot.assemble(
            runtimeState = rs,
            builder = buildTestBuilder(),
            currentStateName = null,
            currentStateEntryEpochNanos = null,
            eventResolver = createResolver(),
            workflowId = "wf-1",
        )

        assertThat(snapshot.pendingTimerDeadlines).isEmpty()
    }

    @Test
    fun `assemble materializes transition log`() {
        val rs = RuntimeState()
        with(StateMachineStateCodec) {
            rs.stateId("IDLE")
        }
        rs.transitions.add(
            CompactTransitionRecord(
                fromStateId = null,
                toStateId = 0,
                outcome = TransitionOutcome.TRANSITION_TO,
                triggerKind = TriggerKind.INITIAL,
                stateEntryEpochNanos = 1_000_000_000L,
                span = CompactSpan(startEpochNanos = 0L, durationNanos = 1_000_000_000L),
            ),
        )

        val snapshot = StateMachineAdminSnapshot.assemble(
            runtimeState = rs,
            builder = buildTestBuilder(),
            currentStateName = "IDLE",
            currentStateEntryEpochNanos = 1_000_000_000L,
            eventResolver = createResolver(),
            workflowId = "wf-1",
        )

        assertThat(snapshot.transitionHistory).hasSize(1)
        assertThat(snapshot.transitionHistory[0].toState).isEqualTo("IDLE")
        assertThat(snapshot.transitionHistory[0].triggerKind).isEqualTo(TriggerKind.INITIAL)
    }

    @Test
    fun `assemble extracts validEventsPerState from builder`() {
        val snapshot = StateMachineAdminSnapshot.assemble(
            runtimeState = RuntimeState(),
            builder = buildTestBuilder(),
            currentStateName = null,
            currentStateEntryEpochNanos = null,
            eventResolver = createResolver(),
            workflowId = "wf-1",
        )

        assertThat(snapshot.validEventsPerState).containsKey("IDLE")
        assertThat(snapshot.validEventsPerState).containsKey("RUNNING")
        assertThat(snapshot.validEventsPerState).containsKey("DONE")
        assertThat(snapshot.validEventsPerState["IDLE"]).hasSize(1)
        assertThat(snapshot.validEventsPerState["RUNNING"]).hasSize(1)
        assertThat(snapshot.validEventsPerState["DONE"]).isEmpty()
    }

    // ── Pending-timer matching by hook id (non-positional) ──

    @Test
    fun `assemble excludes a fired non-tail after hook by id and keeps the earlier hook pending`() {
        // IDLE has after hooks at 5m ("#0") and 10m ("#1"). Only the LATER hook ("#1") has fired in
        // the current state entry. The old positional logic dropped the first N hooks and would have
        // wrongly hidden the 5m hook; matching by hook id must keep 5m pending and hide only 10m.
        val rs = RuntimeState()
        val entryNanos = 1_000_000_000_000_000_000L
        rs.afterHooks.add(
            CompactAfterHookRecord(
                firedAtEpochNanos = entryNanos + 1_000_000_000L,
                durationNanos = Duration.ofMinutes(10).toNanos(),
                hookId = "#1",
            ),
        )

        val snapshot = StateMachineAdminSnapshot.assemble(
            runtimeState = rs,
            builder = buildTwoAfterHookBuilder(),
            currentStateName = "IDLE",
            currentStateEntryEpochNanos = entryNanos,
            eventResolver = createResolver(),
            workflowId = "wf-1",
        )

        val pendingAfterDurations = snapshot.pendingTimerDeadlines.filter { it.type == "after" }.map { it.duration }
        assertThat(pendingAfterDurations).containsExactly(Duration.ofMinutes(5).toString())
    }

    @Test
    fun `assemble excludes a legacy fired after hook matched by duration`() {
        // A record written before hook ids existed (hookId == null) still hides the hook whose
        // declared duration matches the record's durationNanos (the back-compat fallback path).
        val rs = RuntimeState()
        val entryNanos = 1_000_000_000_000_000_000L
        rs.afterHooks.add(
            CompactAfterHookRecord(
                firedAtEpochNanos = entryNanos + 1_000_000_000L,
                durationNanos = Duration.ofMinutes(10).toNanos(),
                hookId = null,
            ),
        )

        val snapshot = StateMachineAdminSnapshot.assemble(
            runtimeState = rs,
            builder = buildTwoAfterHookBuilder(),
            currentStateName = "IDLE",
            currentStateEntryEpochNanos = entryNanos,
            eventResolver = createResolver(),
            workflowId = "wf-1",
        )

        val pendingAfterDurations = snapshot.pendingTimerDeadlines.filter { it.type == "after" }.map { it.duration }
        assertThat(pendingAfterDurations).containsExactly(Duration.ofMinutes(5).toString())
    }

    // ── Transition-log rename remap (so transitionHistory agrees with stateHistory/currentState) ──

    @Test
    fun `assemble migrates fromState and toState in transition rows via stateNameMigrations`() {
        // A renamed state is persisted under its old name "OPEN". stateHistory already remaps it; the
        // transition row's fromState/toState must mirror that remap so the log does not mix the old
        // persisted name with the migrated names shown everywhere else.
        val rs = RuntimeState()
        with(StateMachineStateCodec) {
            rs.stateId("OPEN")
            rs.stateId("DONE")
        }
        rs.transitions.add(
            CompactTransitionRecord(
                fromStateId = 0,
                toStateId = 1,
                outcome = TransitionOutcome.TRANSITION_TO,
                triggerKind = TriggerKind.EVENT,
                stateEntryEpochNanos = 2_000_000_000L,
                span = CompactSpan(startEpochNanos = 1_000_000_000L, durationNanos = 1_000_000_000L),
            ),
        )

        val snapshot = StateMachineAdminSnapshot.assemble(
            runtimeState = rs,
            builder = buildTestBuilder(),
            currentStateName = "DONE",
            currentStateEntryEpochNanos = 2_000_000_000L,
            eventResolver = createResolver(),
            workflowId = "wf-1",
            stateNameMigrations = mapOf("OPEN" to "RUNNING"),
        )

        assertThat(snapshot.transitionHistory).hasSize(1)
        assertThat(snapshot.transitionHistory[0].fromState).isEqualTo("RUNNING")
        assertThat(snapshot.transitionHistory[0].toState).isEqualTo("DONE")
    }

    @Test
    fun `assemble rewrites an EVENT trigger name to the event's current alias`() {
        // An in-flight instance persisted the event under its old alias "OldGo"; the class now resolves
        // to "Go" via eventAliasMigrations. The EVENT transition row's triggerName must show the current
        // alias "Go", matching how the event is rendered elsewhere after the rename.
        val rs = RuntimeState()
        with(StateMachineStateCodec) {
            rs.stateId("IDLE")
            rs.stateId("RUNNING")
            rs.eventTypeId("OldGo")
        }
        rs.events.add(CompactEventRecord(typeId = 0, payload = null, receivedAtEpochNanos = 1_500_000_000L))
        rs.transitions.add(
            CompactTransitionRecord(
                fromStateId = 0,
                toStateId = 1,
                outcome = TransitionOutcome.TRANSITION_TO,
                triggerKind = TriggerKind.EVENT,
                stateEntryEpochNanos = 2_000_000_000L,
                eventIndex = 0,
                span = CompactSpan(startEpochNanos = 1_000_000_000L, durationNanos = 1_000_000_000L),
            ),
        )

        val snapshot = StateMachineAdminSnapshot.assemble(
            runtimeState = rs,
            builder = buildTestBuilder(),
            currentStateName = "RUNNING",
            currentStateEntryEpochNanos = 2_000_000_000L,
            eventResolver = createResolver(aliasMigrations = mapOf("OldGo" to "Go")),
            workflowId = "wf-1",
        )

        assertThat(snapshot.transitionHistory).hasSize(1)
        assertThat(snapshot.transitionHistory[0].triggerKind).isEqualTo(TriggerKind.EVENT)
        assertThat(snapshot.transitionHistory[0].triggerName).isEqualTo("Go")
    }

    @Test
    fun `assemble leaves a framework-derived trigger name untouched even when it matches an event migration key`() {
        // The remap only applies to EVENT triggers — TIMEOUT/AUTO/INITIAL labels are framework-derived,
        // not persisted event identities. Here the migration key "initial" deliberately equals the
        // INITIAL row's label; running framework labels through the resolver (the bug this guards
        // against) would rewrite it to "Go", so the row must still read "initial".
        val rs = RuntimeState()
        with(StateMachineStateCodec) {
            rs.stateId("IDLE")
        }
        rs.transitions.add(
            CompactTransitionRecord(
                fromStateId = null,
                toStateId = 0,
                outcome = TransitionOutcome.TRANSITION_TO,
                triggerKind = TriggerKind.INITIAL,
                stateEntryEpochNanos = 1_000_000_000L,
                span = CompactSpan(startEpochNanos = 0L, durationNanos = 1_000_000_000L),
            ),
        )

        val snapshot = StateMachineAdminSnapshot.assemble(
            runtimeState = rs,
            builder = buildTestBuilder(),
            currentStateName = "IDLE",
            currentStateEntryEpochNanos = 1_000_000_000L,
            eventResolver = createResolver(aliasMigrations = mapOf("initial" to "Go")),
            workflowId = "wf-1",
        )

        assertThat(snapshot.transitionHistory).hasSize(1)
        assertThat(snapshot.transitionHistory[0].triggerKind).isEqualTo(TriggerKind.INITIAL)
        assertThat(snapshot.transitionHistory[0].triggerName).isEqualTo("initial")
    }
}
