@file:Suppress("ForbiddenImport")

package com.airbnb.skipper.statemachine

import com.airbnb.skipper.statemachine.SkipperStateMachine.TransitionOutcome
import com.airbnb.skipper.statemachine.StateMachineStateCodec.materializeTransition
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StateMachineTransitionExecutorTest {
    @Test
    fun `processEvent records handler middleware hook and transition spans`() {
        val clock = MutableTestClock()
        val harness = StateMachineRuntimeHarness()
        val input = InternalTestInput()
        val builder = transitionBuilder(clock, input)
        val executor = transitionExecutor(clock, harness)

        val result = runBlocking {
            executor.processEvent(
                event = InternalTestEvent.Go,
                eventIndex = 0,
                fromState = InternalTestState.IDLE,
                input = input,
                builder = builder,
                middlewares = listOf(AdvancingMiddleware(clock)),
            )
        }

        assertThat(result.newState).isEqualTo(InternalTestState.RUNNING)
        assertThat(result.didTransition).isTrue()
        val transition = materializeTransition(harness.runtimeStore.runtimeState(), harness.runtimeStore.runtimeState().transitions.single())
        assertThat(transition.fromState).isEqualTo("IDLE")
        assertThat(transition.toState).isEqualTo("RUNNING")
        assertThat(transition.outcome).isEqualTo(TransitionOutcome.TRANSITION_TO)
        assertThat(spanDurationNanos(transition.handlerSpan)).isEqualTo(7L)
        assertThat(spanDurationNanos(transition.beforeMiddlewareSpan)).isEqualTo(5L)
        assertThat(spanDurationNanos(transition.onExitSpan)).isEqualTo(11L)
        assertThat(spanDurationNanos(transition.onEntrySpan)).isEqualTo(13L)
        assertThat(spanDurationNanos(transition.afterMiddlewareSpan)).isEqualTo(17L)
        assertThat(spanDurationNanos(transition.span)).isEqualTo(53L)
    }

    @Test
    fun `processEvent records invalid transitions with durable timestamp marker`() {
        val clock = MutableTestClock()
        val harness = StateMachineRuntimeHarness()
        val input = InternalTestInput()
        val builder = transitionBuilder(clock, input)
        val executor = transitionExecutor(clock, harness)

        val result = runBlocking {
            executor.processEvent(
                event = InternalTestEvent.Unknown,
                eventIndex = 0,
                fromState = InternalTestState.IDLE,
                input = input,
                builder = builder,
                middlewares = listOf(AdvancingMiddleware(clock)),
            )
        }

        assertThat(result.newState).isEqualTo(InternalTestState.IDLE)
        assertThat(result.didTransition).isFalse()
        val transition = materializeTransition(harness.runtimeStore.runtimeState(), harness.runtimeStore.runtimeState().transitions.single())
        assertThat(transition.fromState).isEqualTo("IDLE")
        assertThat(transition.toState).isNull()
        assertThat(transition.outcome).isEqualTo(TransitionOutcome.INVALID_NO_HANDLER)
        assertThat(spanDurationNanos(transition.span)).isEqualTo(19L)
    }

    private fun transitionBuilder(
        clock: MutableTestClock,
        input: InternalTestInput,
    ): StateMachineBuilder<InternalTestState, InternalTestEvent, InternalTestInput> =
        StateMachineBuilder<InternalTestState, InternalTestEvent, InternalTestInput>().also { builder ->
            builder.definitionInput = input
            builder.state(InternalTestState.IDLE) {
                on<InternalTestEvent.Go> { _, _ ->
                    clock.advanceNanos(7L)
                    transitionTo(InternalTestState.RUNNING)
                }
                onExit { clock.advanceNanos(11L) }
            }
            builder.state(InternalTestState.RUNNING) {
                onEntry {
                    clock.advanceNanos(13L)
                }
            }
        }

    private fun transitionExecutor(
        clock: MutableTestClock,
        harness: StateMachineRuntimeHarness,
    ): StateMachineTransitionExecutor<InternalTestState, InternalTestEvent, InternalTestInput> =
        StateMachineTransitionExecutor(
            clock = { clock },
            journal = harness.journal,
            stateMachineId = { harness.workflowId },
            stateMachineClass = { InternalTestWorkflow::class.java },
            invalidTransitionPolicy = { InvalidTransitionPolicy.LOG_AND_IGNORE },
            checkpoint = { block -> block() },
        )

    private fun spanDurationNanos(span: SkipperStateMachine.PreciseTimeRange?): Long {
        requireNotNull(span)
        return span.end.toEpochNanosForTest() - span.start.toEpochNanosForTest()
    }

    private fun SkipperStateMachine.PreciseTimestamp.toEpochNanosForTest(): Long = epochSecond * 1_000_000_000L + nano

    private class AdvancingMiddleware(private val clock: MutableTestClock) :
        StateMachineMiddleware<InternalTestState, InternalTestEvent, InternalTestInput>() {
        override suspend fun beforeTransition(ctx: BeforeTransitionContext<InternalTestState, InternalTestEvent, InternalTestInput>) {
            clock.advanceNanos(5L)
        }

        override suspend fun afterTransition(ctx: AfterTransitionContext<InternalTestState, InternalTestEvent, InternalTestInput>) {
            clock.advanceNanos(17L)
        }

        override suspend fun onInvalidTransition(ctx: InvalidTransitionContext<InternalTestState, InternalTestEvent, InternalTestInput>) {
            clock.advanceNanos(19L)
        }
    }
}
