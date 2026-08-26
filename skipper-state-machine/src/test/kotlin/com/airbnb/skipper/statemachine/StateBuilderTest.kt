@file:Suppress("ForbiddenImport")

package com.airbnb.skipper.statemachine

import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StateBuilderTest {
    enum class S { A, B, DONE }

    sealed class E : StateMachineEvent() {
        data class Trigger(val value: Int) : E()

        object Reset : E()
    }

    data class I(val threshold: Int = 10)

    private fun builder(
        input: I? = null,
        init: StateBuilder<S, E, I>.() -> Unit
    ): StateBuilder<S, E, I> =
        StateBuilder<S, E, I>().apply {
            if (input != null) definitionInput = input
            init()
        }

    // ── on<R> handler registration ──

    @Test
    fun `on registers handler for correct event class`() {
        val sb = builder { on<E.Trigger> { _, _ -> transitionTo(S.B) } }
        assertThat(sb.handlers).hasSize(1)
        assertThat(sb.handlers[0].eventClass).isEqualTo(E.Trigger::class)
    }

    @Test
    fun `on registers multiple handlers in order`() {
        val sb = builder {
            on<E.Trigger> { _, _ -> transitionTo(S.B) }
            on<E.Reset> { _, _ -> transitionTo(S.DONE) }
        }
        assertThat(sb.handlers).hasSize(2)
        assertThat(sb.handlers[0].eventClass).isEqualTo(E.Trigger::class)
        assertThat(sb.handlers[1].eventClass).isEqualTo(E.Reset::class)
    }

    @Test
    fun `on handler receives event and returns result`() {
        val input = I(threshold = 5)
        val sb = builder(input) {
            on<E.Trigger> { event, _ ->
                if (event.value > 0) transitionTo(S.B) else stay()
            }
        }
        val result = runBlocking { sb.handlers[0].handler(E.Trigger(1)) }
        assertThat(result).isEqualTo(TransitionResult.TransitionTo(S.B))
    }

    @Test
    fun `on handler receives correct input`() {
        val input = I(threshold = 42)
        val sb = builder(input) {
            on<E.Trigger> { _, receivedInput ->
                assertThat(receivedInput.threshold).isEqualTo(42)
                transitionTo(S.B)
            }
        }
        runBlocking { sb.handlers[0].handler(E.Trigger(1)) }
    }

    @Test
    fun `on handler throws when input not available`() {
        val sb = builder(input = null) {
            on<E.Trigger> { _, _ -> transitionTo(S.B) }
        }
        assertThrows<IllegalStateException> {
            runBlocking { sb.handlers[0].handler(E.Trigger(1)) }
        }
    }

    // ── on<R> with guard ──

    @Test
    fun `on with guard - guard passes - handler invoked`() {
        val input = I(threshold = 10)
        val sb = builder(input) {
            on<E.Trigger>(
                { event, _ -> event.value > 0 },
                { _, _ -> transitionTo(S.B) },
            )
        }
        val result = runBlocking { sb.handlers[0].handler(E.Trigger(5)) }
        assertThat(result).isEqualTo(TransitionResult.TransitionTo(S.B))
    }

    @Test
    fun `on with guard - guard fails - returns Ignore`() {
        val input = I(threshold = 10)
        val sb = builder(input) {
            on<E.Trigger>(
                { event, _ -> event.value > 100 },
                { _, _ -> transitionTo(S.B) },
            )
        }
        val result = runBlocking { sb.handlers[0].handler(E.Trigger(5)) }
        assertThat(result).isEqualTo(TransitionResult.Ignore(TransitionResult.IgnoreReason.GUARD_REJECTED))
    }

    @Test
    fun `on with guard - guard receives correct input`() {
        val input = I(threshold = 10)
        val sb = builder(input) {
            on<E.Trigger>(
                { event, receivedInput -> event.value > receivedInput.threshold },
                { _, _ -> transitionTo(S.B) },
            )
        }
        assertThat(runBlocking { sb.handlers[0].handler(E.Trigger(11)) })
            .isEqualTo(TransitionResult.TransitionTo(S.B))
        assertThat(runBlocking { sb.handlers[0].handler(E.Trigger(9)) })
            .isEqualTo(TransitionResult.Ignore(TransitionResult.IgnoreReason.GUARD_REJECTED))
    }

    @Test
    fun `on with guard - guard throws when input not available`() {
        val sb = builder(input = null) {
            on<E.Trigger>(
                { _, _ -> true },
                { _, _ -> transitionTo(S.B) },
            )
        }
        assertThrows<IllegalStateException> {
            runBlocking { sb.handlers[0].handler(E.Trigger(1)) }
        }
    }

    // ── onEntry hooks ──

    @Test
    fun `onEntry hooks are registered in order`() {
        val log = mutableListOf<Int>()
        val sb = builder {
            onEntry { _ -> log.add(1) }
            onEntry { _ -> log.add(2) }
            onEntry { _ -> log.add(3) }
        }
        assertThat(sb.onEntryHooks).hasSize(3)
        runBlocking { sb.onEntryHooks.forEach { it(I()) } }
        assertThat(log).containsExactly(1, 2, 3)
    }

    @Test
    fun `onEntry hook receives correct input`() {
        val input = I(threshold = 99)
        var captured: I? = null
        val sb = builder { onEntry { received -> captured = received } }
        runBlocking { sb.onEntryHooks[0](input) }
        assertThat(captured).isEqualTo(input)
    }

    @Test
    fun `onEntry with no hooks registered`() {
        val sb = builder {}
        assertThat(sb.onEntryHooks).isEmpty()
    }

    // ── onExit hooks ──

    @Test
    fun `onExit hooks are registered in order`() {
        val log = mutableListOf<String>()
        val sb = builder {
            onExit { _ -> log.add("exit1") }
            onExit { _ -> log.add("exit2") }
        }
        assertThat(sb.onExitHooks).hasSize(2)
        runBlocking { sb.onExitHooks.forEach { it(I()) } }
        assertThat(log).containsExactly("exit1", "exit2")
    }

    @Test
    fun `onExit hook receives correct input`() {
        val input = I(threshold = 77)
        var captured: I? = null
        val sb = builder { onExit { received -> captured = received } }
        runBlocking { sb.onExitHooks[0](input) }
        assertThat(captured).isEqualTo(input)
    }

    // ── onEntry and onExit are independent ──

    @Test
    fun `onEntry and onExit hooks are independent`() {
        val sb = builder {
            onEntry { _ -> }
            onEntry { _ -> }
            onExit { _ -> }
        }
        assertThat(sb.onEntryHooks).hasSize(2)
        assertThat(sb.onExitHooks).hasSize(1)
    }

    // ── timeout ──

    @Test
    fun `timeout stores duration`() {
        val sb = builder { timeout(Duration.ofHours(1)) { transitionTo(S.DONE) } }
        assertThat(sb.timeout).isNotNull()
        assertThat(sb.timeout!!.staticDuration).isEqualTo(Duration.ofHours(1))
    }

    @Test
    fun `timeout handler returns correct result`() {
        val sb = builder { timeout(Duration.ofSeconds(30)) { transitionTo(S.DONE) } }
        val result = runBlocking { sb.timeout!!.handler(I()) }
        assertThat(result).isEqualTo(TransitionResult.TransitionTo(S.DONE))
    }

    @Test
    fun `no timeout by default`() {
        val sb = builder { on<E.Reset> { _, _ -> stay() } }
        assertThat(sb.timeout).isNull()
    }

    // ── terminal ──

    @Test
    fun `terminal flag is false by default`() {
        val sb = builder {}
        assertThat(sb.isTerminal).isFalse()
    }

    @Test
    fun `terminal sets flag to true`() {
        val sb = builder { terminal() }
        assertThat(sb.isTerminal).isTrue()
    }

    // ── immediatelyTransitionTo ──

    @Test
    fun `immediatelyTransitionTo stores target state`() {
        val sb = builder { immediatelyTransitionTo(S.B) }
        assertThat(sb.immediateTransitionTo).isEqualTo(S.B)
    }

    @Test
    fun `immediateTransitionTo is null by default`() {
        val sb = builder {}
        assertThat(sb.immediateTransitionTo).isNull()
    }

    @Test
    fun `timeout called twice - second overwrites first`() {
        val sb = builder {
            timeout(Duration.ofHours(1)) { transitionTo(S.B) }
            timeout(Duration.ofMinutes(30)) { transitionTo(S.DONE) }
        }
        assertThat(sb.timeout!!.staticDuration).isEqualTo(Duration.ofMinutes(30))
    }

    @Test
    fun `immediatelyTransitionTo called twice - second overwrites first`() {
        val sb = builder {
            immediatelyTransitionTo(S.A)
            immediatelyTransitionTo(S.B)
        }
        assertThat(sb.immediateTransitionTo).isEqualTo(S.B)
    }

    @Test
    fun `terminal called twice is idempotent`() {
        val sb = builder {
            terminal()
            terminal()
        }
        assertThat(sb.isTerminal).isTrue()
    }

    @Test
    fun `on handler error message mentions input not available`() {
        val sb = builder(input = null) {
            on<E.Trigger> { _, _ -> transitionTo(S.B) }
        }
        val ex = assertThrows<IllegalStateException> {
            runBlocking { sb.handlers[0].handler(E.Trigger(1)) }
        }
        assertThat(ex.message).contains("Input not available")
    }

    // ── after ──

    @Test
    fun `after stores duration and handler`() {
        val sb = builder { after(Duration.ofDays(7)) { _ -> } }
        assertThat(sb.afterHooks).hasSize(1)
        assertThat(sb.afterHooks[0].duration).isEqualTo(Duration.ofDays(7))
    }

    @Test
    fun `after registers multiple hooks in order`() {
        val sb = builder {
            after(Duration.ofDays(3)) { _ -> }
            after(Duration.ofDays(7)) { _ -> }
        }
        assertThat(sb.afterHooks).hasSize(2)
        assertThat(sb.afterHooks[0].duration).isEqualTo(Duration.ofDays(3))
        assertThat(sb.afterHooks[1].duration).isEqualTo(Duration.ofDays(7))
    }

    @Test
    fun `after handler receives correct input`() {
        val input = I(threshold = 42)
        var captured: I? = null
        val sb = builder(input) { after(Duration.ofDays(1)) { received -> captured = received } }
        runBlocking { sb.afterHooks[0].handler(input) }
        assertThat(captured).isEqualTo(input)
    }

    @Test
    fun `no after hooks by default`() {
        val sb = builder { on<E.Reset> { _, _ -> stay() } }
        assertThat(sb.afterHooks).isEmpty()
    }

    // ── DSL helpers ──

    @Test
    fun `transitionTo creates correct TransitionTo result`() {
        val sb = builder {}
        val result = sb.transitionTo(S.B)
        assertThat(result).isInstanceOf(TransitionResult.TransitionTo::class.java)
        assertThat((result as TransitionResult.TransitionTo<*>).newState).isEqualTo(S.B)
    }

    @Test
    fun `stay creates Stay result`() {
        val sb = builder {}
        assertThat(sb.stay()).isEqualTo(TransitionResult.Stay)
    }

    @Test
    fun `ignore creates Ignore result`() {
        val sb = builder {}
        assertThat(sb.ignore()).isEqualTo(TransitionResult.Ignore(TransitionResult.IgnoreReason.EXPLICIT))
    }
}
