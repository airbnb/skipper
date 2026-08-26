@file:Suppress("ForbiddenImport")

package com.airbnb.skipper.statemachine

import com.airbnb.skipper.statemachine.StateMachineStateCodec.CompactEventRecord
import com.airbnb.skipper.statemachine.StateMachineStateCodec.currentStateId
import com.airbnb.skipper.statemachine.StateMachineStateCodec.materializeTransition
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StateMachineEventLoopTest {
    @Test
    fun `run replays the event log from the initial state until terminal`() {
        val clock = MutableTestClock()
        val harness = StateMachineRuntimeHarness()
        val input = InternalTestInput()
        val builder = StateMachineBuilder<InternalTestState, InternalTestEvent, InternalTestInput>().also { builder ->
            builder.definitionInput = input
            builder.state(InternalTestState.IDLE) {
                on<InternalTestEvent.Go> { _, _ -> transitionTo(InternalTestState.DONE) }
            }
            builder.state(InternalTestState.DONE) {
                terminal()
            }
        }
        val eventResolver: StateMachineEventResolver<InternalTestEvent> = StateMachineEventResolver(
            stateMachineClass = InternalTestWorkflow::class.java,
            handlerEventClasses = listOf(InternalTestEvent.Go::class),
        )
        with(StateMachineStateCodec) {
            val runtimeState = harness.runtimeStore.runtimeState()
            runtimeState.events.add(
                CompactEventRecord(
                    typeId = runtimeState.eventTypeId("Go"),
                    payload = eventResolver.serializeEventPayload(InternalTestEvent.Go),
                    receivedAtEpochNanos = 0L,
                ),
            )
            harness.runtimeStore.persist(runtimeState)
        }
        val executor: StateMachineTransitionExecutor<InternalTestState, InternalTestEvent, InternalTestInput> = StateMachineTransitionExecutor(
            clock = { clock },
            journal = harness.journal,
            stateMachineId = { harness.workflowId },
            stateMachineClass = { InternalTestWorkflow::class.java },
            invalidTransitionPolicy = { InvalidTransitionPolicy.LOG_AND_IGNORE },
            checkpoint = { block -> block() },
        )
        val loop: StateMachineEventLoop<InternalTestState, InternalTestEvent, InternalTestInput> = StateMachineEventLoop(
            initialState = InternalTestState.IDLE,
            workflowId = { harness.workflowId },
            clock = { clock },
            runtimeStore = harness.runtimeStore,
            eventResolver = eventResolver,
            journal = harness.journal,
            transitionExecutor = executor,
            waitUntilCondition = { condition -> condition() },
            waitUntilConditionWithTimeout = { _, _: Duration -> error("No timers expected") },
        )

        val finalState = runBlocking { loop.run(input, builder, emptyList()) }

        assertThat(finalState).isEqualTo(InternalTestState.DONE)
        val runtimeState = harness.runtimeStore.runtimeState()
        assertThat(currentStateId(runtimeState)).isEqualTo(1)
        assertThat(runtimeState.transitions.map { materializeTransition(runtimeState, it).triggerName })
            .containsExactly("initial", "Go")
    }

    // ── After-hook identity at the loop level ──

    @Test
    fun `a fired after hook records its derived ordinal hookId in the journal`() {
        val harness = StateMachineRuntimeHarness()
        val input = InternalTestInput()
        val builder = StateMachineBuilder<InternalTestState, InternalTestEvent, InternalTestInput>().also { b ->
            b.definitionInput = input
            b.state(InternalTestState.IDLE) {
                after(Duration.ofMinutes(3)) { _ -> }
                timeout(Duration.ofMinutes(5)) { transitionTo(InternalTestState.DONE) }
            }
            b.state(InternalTestState.DONE) { terminal() }
        }

        // No durable events; every deadline wait reports "deadline reached, no event", so the loop
        // fires the after hook and then the timeout.
        val finalState = runBlocking { runLoopFiringTimers(harness).run(input, builder, emptyList()) }

        assertThat(finalState).isEqualTo(InternalTestState.DONE)
        val afterHookIds = harness.runtimeStore.runtimeState().afterHooks.map { it.hookId }
        assertThat(afterHookIds).containsExactly("#0")
    }

    @Test
    fun `the fired set suppresses re-selection so each after hook fires exactly once`() {
        val harness = StateMachineRuntimeHarness()
        val input = InternalTestInput()
        val builder = StateMachineBuilder<InternalTestState, InternalTestEvent, InternalTestInput>().also { b ->
            b.definitionInput = input
            b.state(InternalTestState.IDLE) {
                after(Duration.ofMinutes(2)) { _ -> }
                after(Duration.ofMinutes(4)) { _ -> }
                timeout(Duration.ofMinutes(6)) { transitionTo(InternalTestState.DONE) }
            }
            b.state(InternalTestState.DONE) { terminal() }
        }

        // Both after hooks fire, then the timeout. If selection did not track fired ids, the loop
        // would keep re-selecting the first hook and never advance to the timeout / DONE.
        val finalState = runBlocking { runLoopFiringTimers(harness).run(input, builder, emptyList()) }

        assertThat(finalState).isEqualTo(InternalTestState.DONE)
        val afterHookIds = harness.runtimeStore.runtimeState().afterHooks.map { it.hookId }
        assertThat(afterHookIds).containsExactlyInAnyOrder("#0", "#1")
    }

    @Test
    fun `an explicit after hook id is recorded instead of the ordinal`() {
        val harness = StateMachineRuntimeHarness()
        val input = InternalTestInput()
        val builder = StateMachineBuilder<InternalTestState, InternalTestEvent, InternalTestInput>().also { b ->
            b.definitionInput = input
            b.state(InternalTestState.IDLE) {
                after(Duration.ofMinutes(3), id = "reminder") { _ -> }
                timeout(Duration.ofMinutes(5)) { transitionTo(InternalTestState.DONE) }
            }
            b.state(InternalTestState.DONE) { terminal() }
        }

        val finalState = runBlocking { runLoopFiringTimers(harness).run(input, builder, emptyList()) }

        assertThat(finalState).isEqualTo(InternalTestState.DONE)
        val afterHookIds = harness.runtimeStore.runtimeState().afterHooks.map { it.hookId }
        assertThat(afterHookIds).containsExactly("reminder")
    }

    /**
     * Builds an event loop whose timer waits always report "deadline reached with no new event", so
     * the loop deterministically fires due after hooks and timeouts. There are no durable events, so
     * the no-deadline wait is never reached.
     */
    private fun runLoopFiringTimers(
        harness: StateMachineRuntimeHarness,
    ): StateMachineEventLoop<InternalTestState, InternalTestEvent, InternalTestInput> {
        val clock = MutableTestClock()
        val eventResolver = StateMachineEventResolver<InternalTestEvent>(
            stateMachineClass = InternalTestWorkflow::class.java,
            handlerEventClasses = emptyList(),
        )
        val executor: StateMachineTransitionExecutor<InternalTestState, InternalTestEvent, InternalTestInput> = StateMachineTransitionExecutor(
            clock = { clock },
            journal = harness.journal,
            stateMachineId = { harness.workflowId },
            stateMachineClass = { InternalTestWorkflow::class.java },
            invalidTransitionPolicy = { InvalidTransitionPolicy.LOG_AND_IGNORE },
            checkpoint = { block -> block() },
        )
        return StateMachineEventLoop(
            initialState = InternalTestState.IDLE,
            workflowId = { harness.workflowId },
            clock = { clock },
            runtimeStore = harness.runtimeStore,
            eventResolver = eventResolver,
            journal = harness.journal,
            transitionExecutor = executor,
            waitUntilCondition = { error("No event-only waits expected; the loop should fire timers") },
            // Model the real blocking wait: waitUntil(deadline) blocks until the deadline, advancing
            // virtual time to it, then reports "no new event". The timeout passed here is the
            // remaining duration to earliestDeadline (see StateMachineEventLoop.waitForNewEvent), so
            // advancing by exactly it lands the clock on the deadline. The loop's now-based due-check
            // then sees each hook become due at its deadline and fires them in declaration order.
            waitUntilConditionWithTimeout = { _, timeout ->
                clock.advanceNanos(timeout.toNanos())
                false
            },
        )
    }
}
