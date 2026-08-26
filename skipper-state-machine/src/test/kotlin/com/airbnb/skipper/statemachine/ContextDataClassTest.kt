package com.airbnb.skipper.statemachine

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for the context data classes used by [StateMachineMiddleware]:
 * [BeforeTransitionContext], [AfterTransitionContext], [InvalidTransitionContext],
 * and [TerminalStateReachedContext].
 *
 * Verifies equality, hashCode, copy, destructuring, and correct handling of all fields
 * including [TransitionTrigger], `stateMachineId`, and `stateMachineClass: Class<*>`.
 */
class ContextDataClassTest {
    enum class S { A, B }

    sealed class E {
        object Go : E()

        object Stop : E()
    }

    data class I(val value: String = "default")

    private val smId = "TestStateMachine-42"

    @Suppress("UNCHECKED_CAST")
    private val smClass = ContextDataClassTest::class.java as Class<out SkipperStateMachine<*, *, *>>

    // ── BeforeTransitionContext ──

    @Test
    fun `BeforeTransitionContext - equal when same fields`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx1 = BeforeTransitionContext(S.A, trigger, I("x"), smId, smClass)
        val ctx2 = BeforeTransitionContext(S.A, trigger, I("x"), smId, smClass)
        assertThat(ctx1).isEqualTo(ctx2)
    }

    @Test
    fun `BeforeTransitionContext - not equal when different fromState`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx1 = BeforeTransitionContext(S.A, trigger, I("x"), smId, smClass)
        val ctx2 = BeforeTransitionContext(S.B, trigger, I("x"), smId, smClass)
        assertThat(ctx1).isNotEqualTo(ctx2)
    }

    @Test
    fun `BeforeTransitionContext - not equal when different trigger`() {
        val ctx1 = BeforeTransitionContext(S.A, TransitionTrigger.Event(E.Go), I("x"), smId, smClass)
        val ctx2 = BeforeTransitionContext(S.A, TransitionTrigger.Timeout(Duration.ofDays(7)), I("x"), smId, smClass)
        assertThat(ctx1).isNotEqualTo(ctx2)
    }

    @Test
    fun `BeforeTransitionContext - not equal when different stateMachineId`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx1 = BeforeTransitionContext(S.A, trigger, I("x"), "id-1", smClass)
        val ctx2 = BeforeTransitionContext(S.A, trigger, I("x"), "id-2", smClass)
        assertThat(ctx1).isNotEqualTo(ctx2)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `BeforeTransitionContext - not equal when different stateMachineClass`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx1 = BeforeTransitionContext(S.A, trigger, I("x"), smId, String::class.java as Class<out SkipperStateMachine<*, *, *>>)
        val ctx2 = BeforeTransitionContext(S.A, trigger, I("x"), smId, Int::class.java as Class<out SkipperStateMachine<*, *, *>>)
        assertThat(ctx1).isNotEqualTo(ctx2)
    }

    @Test
    fun `BeforeTransitionContext - copy with changed field`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx = BeforeTransitionContext(S.A, trigger, I("x"), smId, smClass)
        val copy = ctx.copy(fromState = S.B)
        assertThat(copy.fromState).isEqualTo(S.B)
        assertThat(copy.trigger).isEqualTo(trigger)
        assertThat(copy.input).isEqualTo(I("x"))
        assertThat(copy.stateMachineId).isEqualTo(smId)
        assertThat(copy.stateMachineClass).isEqualTo(smClass)
    }

    @Test
    fun `BeforeTransitionContext - Timeout trigger is preserved`() {
        val trigger = TransitionTrigger.Timeout(Duration.ofDays(14))
        val ctx = BeforeTransitionContext(S.A, trigger, I("x"), smId, smClass)
        assertThat(ctx.trigger).isEqualTo(TransitionTrigger.Timeout(Duration.ofDays(14)))
        assertThat(ctx.trigger).isInstanceOf(TransitionTrigger.Timeout::class.java)
    }

    @Test
    fun `BeforeTransitionContext - AutoTransition trigger is preserved`() {
        val ctx = BeforeTransitionContext(S.A, TransitionTrigger.AutoTransition, I("x"), smId, smClass)
        assertThat(ctx.trigger).isEqualTo(TransitionTrigger.AutoTransition)
    }

    @Test
    fun `BeforeTransitionContext - destructuring`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx = BeforeTransitionContext(S.A, trigger, I("val"), smId, smClass)
        val (fromState, triggerOut, input, id, className) = ctx
        assertThat(fromState).isEqualTo(S.A)
        assertThat(triggerOut).isEqualTo(trigger)
        assertThat(input).isEqualTo(I("val"))
        assertThat(id).isEqualTo(smId)
        assertThat(className).isEqualTo(smClass)
    }

    @Test
    fun `BeforeTransitionContext - hashCode consistent with equals`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx1 = BeforeTransitionContext(S.A, trigger, I("x"), smId, smClass)
        val ctx2 = BeforeTransitionContext(S.A, trigger, I("x"), smId, smClass)
        assertThat(ctx1.hashCode()).isEqualTo(ctx2.hashCode())
    }

    // ── AfterTransitionContext ──

    @Test
    fun `AfterTransitionContext - equal when same fields`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx1 = AfterTransitionContext(S.A, S.B, trigger, I("x"), smId, smClass)
        val ctx2 = AfterTransitionContext(S.A, S.B, trigger, I("x"), smId, smClass)
        assertThat(ctx1).isEqualTo(ctx2)
    }

    @Test
    fun `AfterTransitionContext - not equal when different toState`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx1 = AfterTransitionContext(S.A, S.B, trigger, I("x"), smId, smClass)
        val ctx2 = AfterTransitionContext(S.A, S.A, trigger, I("x"), smId, smClass)
        assertThat(ctx1).isNotEqualTo(ctx2)
    }

    @Test
    fun `AfterTransitionContext - not equal when different trigger`() {
        val ctx1 = AfterTransitionContext(S.A, S.B, TransitionTrigger.Event(E.Go), I("x"), smId, smClass)
        val ctx2 = AfterTransitionContext(S.A, S.B, TransitionTrigger.AutoTransition, I("x"), smId, smClass)
        assertThat(ctx1).isNotEqualTo(ctx2)
    }

    @Test
    fun `AfterTransitionContext - not equal when different stateMachineId`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx1 = AfterTransitionContext(S.A, S.B, trigger, I("x"), "id-1", smClass)
        val ctx2 = AfterTransitionContext(S.A, S.B, trigger, I("x"), "id-2", smClass)
        assertThat(ctx1).isNotEqualTo(ctx2)
    }

    @Test
    fun `AfterTransitionContext - copy with changed field`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx = AfterTransitionContext(S.A, S.B, trigger, I("x"), smId, smClass)
        val copy = ctx.copy(toState = S.A)
        assertThat(copy.toState).isEqualTo(S.A)
        assertThat(copy.fromState).isEqualTo(S.A)
        assertThat(copy.trigger).isEqualTo(trigger)
        assertThat(copy.stateMachineId).isEqualTo(smId)
        assertThat(copy.stateMachineClass).isEqualTo(smClass)
    }

    @Test
    fun `AfterTransitionContext - Timeout trigger is preserved`() {
        val trigger = TransitionTrigger.Timeout(Duration.ofDays(7))
        val ctx = AfterTransitionContext(S.A, S.B, trigger, I("x"), smId, smClass)
        assertThat(ctx.trigger).isInstanceOf(TransitionTrigger.Timeout::class.java)
        assertThat((ctx.trigger as TransitionTrigger.Timeout).duration).isEqualTo(Duration.ofDays(7))
    }

    @Test
    fun `AfterTransitionContext - destructuring`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx = AfterTransitionContext(S.A, S.B, trigger, I("val"), smId, smClass)
        val (fromState, toState, triggerOut, input, id, className) = ctx
        assertThat(fromState).isEqualTo(S.A)
        assertThat(toState).isEqualTo(S.B)
        assertThat(triggerOut).isEqualTo(trigger)
        assertThat(input).isEqualTo(I("val"))
        assertThat(id).isEqualTo(smId)
        assertThat(className).isEqualTo(smClass)
    }

    @Test
    fun `AfterTransitionContext - hashCode consistent with equals`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx1 = AfterTransitionContext(S.A, S.B, trigger, I("x"), smId, smClass)
        val ctx2 = AfterTransitionContext(S.A, S.B, trigger, I("x"), smId, smClass)
        assertThat(ctx1.hashCode()).isEqualTo(ctx2.hashCode())
    }

    // ── InvalidTransitionContext ──

    @Test
    fun `InvalidTransitionContext - equal when same fields`() {
        val ctx1 = InvalidTransitionContext(S.A, E.Go, I("x"), smId, smClass)
        val ctx2 = InvalidTransitionContext(S.A, E.Go, I("x"), smId, smClass)
        assertThat(ctx1).isEqualTo(ctx2)
    }

    @Test
    fun `InvalidTransitionContext - not equal when different event`() {
        val ctx1 = InvalidTransitionContext(S.A, E.Go, I("x"), smId, smClass)
        val ctx2 = InvalidTransitionContext(S.A, E.Stop, I("x"), smId, smClass)
        assertThat(ctx1).isNotEqualTo(ctx2)
    }

    @Test
    fun `InvalidTransitionContext - not equal when different stateMachineId`() {
        val ctx1 = InvalidTransitionContext(S.A, E.Go, I("x"), "id-1", smClass)
        val ctx2 = InvalidTransitionContext(S.A, E.Go, I("x"), "id-2", smClass)
        assertThat(ctx1).isNotEqualTo(ctx2)
    }

    @Test
    fun `InvalidTransitionContext - copy with changed field`() {
        val ctx = InvalidTransitionContext(S.A, E.Go, I("x"), smId, smClass)
        val copy = ctx.copy(fromState = S.B)
        assertThat(copy.fromState).isEqualTo(S.B)
        assertThat(copy.event).isEqualTo(E.Go)
        assertThat(copy.stateMachineId).isEqualTo(smId)
        assertThat(copy.stateMachineClass).isEqualTo(smClass)
    }

    @Test
    fun `InvalidTransitionContext - event is always non-null`() {
        val ctx = InvalidTransitionContext(S.A, E.Go, I("x"), smId, smClass)
        assertThat(ctx.event).isNotNull()
    }

    @Test
    fun `InvalidTransitionContext - destructuring`() {
        val ctx = InvalidTransitionContext(S.A, E.Stop, I("val"), smId, smClass)
        val (fromState, event, input, id, className) = ctx
        assertThat(fromState).isEqualTo(S.A)
        assertThat(event).isEqualTo(E.Stop)
        assertThat(input).isEqualTo(I("val"))
        assertThat(id).isEqualTo(smId)
        assertThat(className).isEqualTo(smClass)
    }

    @Test
    fun `InvalidTransitionContext - hashCode consistent with equals`() {
        val ctx1 = InvalidTransitionContext(S.A, E.Go, I("x"), smId, smClass)
        val ctx2 = InvalidTransitionContext(S.A, E.Go, I("x"), smId, smClass)
        assertThat(ctx1.hashCode()).isEqualTo(ctx2.hashCode())
    }

    // ── TerminalStateReachedContext ──

    @Test
    fun `TerminalStateReachedContext - equal when same fields`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx1 = TerminalStateReachedContext(S.B, trigger, I("x"), smId, smClass)
        val ctx2 = TerminalStateReachedContext(S.B, trigger, I("x"), smId, smClass)
        assertThat(ctx1).isEqualTo(ctx2)
    }

    @Test
    fun `TerminalStateReachedContext - not equal when different terminalState`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx1 = TerminalStateReachedContext(S.A, trigger, I("x"), smId, smClass)
        val ctx2 = TerminalStateReachedContext(S.B, trigger, I("x"), smId, smClass)
        assertThat(ctx1).isNotEqualTo(ctx2)
    }

    @Test
    fun `TerminalStateReachedContext - not equal when different trigger`() {
        val ctx1 = TerminalStateReachedContext(S.B, TransitionTrigger.Event(E.Go), I("x"), smId, smClass)
        val ctx2 = TerminalStateReachedContext(S.B, TransitionTrigger.Timeout(Duration.ofDays(7)), I("x"), smId, smClass)
        assertThat(ctx1).isNotEqualTo(ctx2)
    }

    @Test
    fun `TerminalStateReachedContext - not equal when different input`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx1 = TerminalStateReachedContext(S.B, trigger, I("x"), smId, smClass)
        val ctx2 = TerminalStateReachedContext(S.B, trigger, I("y"), smId, smClass)
        assertThat(ctx1).isNotEqualTo(ctx2)
    }

    @Test
    fun `TerminalStateReachedContext - not equal when different stateMachineId`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx1 = TerminalStateReachedContext(S.B, trigger, I("x"), "id-1", smClass)
        val ctx2 = TerminalStateReachedContext(S.B, trigger, I("x"), "id-2", smClass)
        assertThat(ctx1).isNotEqualTo(ctx2)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `TerminalStateReachedContext - not equal when different stateMachineClass`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx1 = TerminalStateReachedContext(S.B, trigger, I("x"), smId, String::class.java as Class<out SkipperStateMachine<*, *, *>>)
        val ctx2 = TerminalStateReachedContext(S.B, trigger, I("x"), smId, Int::class.java as Class<out SkipperStateMachine<*, *, *>>)
        assertThat(ctx1).isNotEqualTo(ctx2)
    }

    @Test
    fun `TerminalStateReachedContext - copy with changed field`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx = TerminalStateReachedContext(S.B, trigger, I("x"), smId, smClass)
        val copy = ctx.copy(terminalState = S.A)
        assertThat(copy.terminalState).isEqualTo(S.A)
        assertThat(copy.trigger).isEqualTo(trigger)
        assertThat(copy.input).isEqualTo(I("x"))
        assertThat(copy.stateMachineId).isEqualTo(smId)
        assertThat(copy.stateMachineClass).isEqualTo(smClass)
    }

    @Test
    fun `TerminalStateReachedContext - null trigger for initial-terminal edge`() {
        val ctx = TerminalStateReachedContext<S, E, I>(S.B, null, I("x"), smId, smClass)
        assertThat(ctx.trigger).isNull()
        assertThat(ctx.terminalState).isEqualTo(S.B)
    }

    @Test
    fun `TerminalStateReachedContext - non-null trigger with Event`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx = TerminalStateReachedContext(S.B, trigger, I("x"), smId, smClass)
        assertThat(ctx.trigger).isEqualTo(trigger)
        assertThat(ctx.trigger).isInstanceOf(TransitionTrigger.Event::class.java)
    }

    @Test
    fun `TerminalStateReachedContext - Timeout trigger is preserved`() {
        val trigger = TransitionTrigger.Timeout(Duration.ofDays(14))
        val ctx = TerminalStateReachedContext<S, E, I>(S.B, trigger, I("x"), smId, smClass)
        assertThat(ctx.trigger).isInstanceOf(TransitionTrigger.Timeout::class.java)
        assertThat((ctx.trigger as TransitionTrigger.Timeout).duration).isEqualTo(Duration.ofDays(14))
    }

    @Test
    fun `TerminalStateReachedContext - AutoTransition trigger is preserved`() {
        val ctx = TerminalStateReachedContext<S, E, I>(S.B, TransitionTrigger.AutoTransition, I("x"), smId, smClass)
        assertThat(ctx.trigger).isEqualTo(TransitionTrigger.AutoTransition)
    }

    @Test
    fun `TerminalStateReachedContext - destructuring`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx = TerminalStateReachedContext(S.B, trigger, I("val"), smId, smClass)
        val (terminalState, triggerOut, input, id, className) = ctx
        assertThat(terminalState).isEqualTo(S.B)
        assertThat(triggerOut).isEqualTo(trigger)
        assertThat(input).isEqualTo(I("val"))
        assertThat(id).isEqualTo(smId)
        assertThat(className).isEqualTo(smClass)
    }

    @Test
    fun `TerminalStateReachedContext - hashCode consistent with equals`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx1 = TerminalStateReachedContext(S.B, trigger, I("x"), smId, smClass)
        val ctx2 = TerminalStateReachedContext(S.B, trigger, I("x"), smId, smClass)
        assertThat(ctx1.hashCode()).isEqualTo(ctx2.hashCode())
    }

    @Test
    fun `TerminalStateReachedContext - toString contains all fields`() {
        val trigger = TransitionTrigger.Event(E.Go)
        val ctx = TerminalStateReachedContext(S.B, trigger, I("x"), smId, smClass)
        val str = ctx.toString()
        assertThat(str).contains("B")
        assertThat(str).contains(smId)
        assertThat(str).contains(smClass.name)
    }
}
