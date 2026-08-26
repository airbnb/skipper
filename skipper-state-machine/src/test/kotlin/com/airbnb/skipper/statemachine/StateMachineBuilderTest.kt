@file:Suppress("ForbiddenImport")

package com.airbnb.skipper.statemachine

import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StateMachineBuilderTest {
    enum class TestState { A, B, C, DONE }

    sealed class TestEvent : StateMachineEvent() {
        data class Go(val target: TestState) : TestEvent()

        object Stop : TestEvent()
    }

    data class TestInput(val value: String = "test")

    @Test
    fun `state definitions are registered`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {}
            state(TestState.B) {}
        }
        assertThat(builder.stateDefinitions).containsKey(TestState.A)
        assertThat(builder.stateDefinitions).containsKey(TestState.B)
        assertThat(builder.stateDefinitions).doesNotContainKey(TestState.C)
    }

    @Test
    fun `state definitions preserve insertion order`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.C) { terminal() }
            state(TestState.A) { on<TestEvent.Stop> { _, _ -> transitionTo(TestState.DONE) } }
            state(TestState.DONE) { terminal() }
        }
        assertThat(builder.stateDefinitions.keys.toList())
            .containsExactly(TestState.C, TestState.A, TestState.DONE)
    }

    @Test
    fun `terminal flag is set correctly`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {}
            state(TestState.DONE) { terminal() }
        }
        assertThat(builder.stateDefinitions[TestState.A]!!.builder.isTerminal).isFalse()
        assertThat(builder.stateDefinitions[TestState.DONE]!!.builder.isTerminal).isTrue()
    }

    @Test
    fun `event handlers are registered and matched by type`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {
                on<TestEvent.Go> { _, _ -> transitionTo(TestState.B) }
                on<TestEvent.Stop> { _, _ -> transitionTo(TestState.DONE) }
            }
        }
        val handlers = builder.stateDefinitions[TestState.A]!!.builder.handlers
        assertThat(handlers).hasSize(2)
        assertThat(handlers[0].eventClass).isEqualTo(TestEvent.Go::class)
        assertThat(handlers[1].eventClass).isEqualTo(TestEvent.Stop::class)
    }

    @Test
    fun `first matching handler wins`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {
                on<TestEvent.Go> { _, _ -> transitionTo(TestState.B) }
                on<TestEvent.Go> { _, _ -> transitionTo(TestState.C) }
            }
        }
        val handlers = builder.stateDefinitions[TestState.A]!!.builder.handlers
        val event = TestEvent.Go(TestState.B)
        val firstMatch = handlers.first { it.eventClass.java.isInstance(event) }
        assertThat(firstMatch).isEqualTo(handlers[0])
    }

    @Test
    fun `guard prevents handler invocation`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            definitionInput = TestInput()
            state(TestState.A) {
                on<TestEvent.Go>({ event, _ -> event.target == TestState.B }) { _, _ ->
                    transitionTo(TestState.B)
                }
            }
        }
        val handler = builder.stateDefinitions[TestState.A]!!.builder.handlers[0]

        // Guard passes
        val goToB = TestEvent.Go(TestState.B)
        val resultB = runBlocking { handler.handler(goToB) }
        assertThat(resultB).isInstanceOf(TransitionResult.TransitionTo::class.java)
        assertThat((resultB as TransitionResult.TransitionTo<*>).newState).isEqualTo(TestState.B)

        // Guard fails
        val goToC = TestEvent.Go(TestState.C)
        val resultC = runBlocking { handler.handler(goToC) }
        assertThat(resultC).isInstanceOf(TransitionResult.Ignore::class.java)
    }

    @Test
    fun `timeout is registered`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) { timeout(Duration.ofDays(14)) { transitionTo(TestState.DONE) } }
        }
        val timeout = builder.stateDefinitions[TestState.A]!!.builder.timeout
        assertThat(timeout).isNotNull()
        assertThat(timeout!!.staticDuration).isEqualTo(Duration.ofDays(14))
    }

    @Test
    fun `timeout handler returns correct result`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) { timeout(Duration.ofMinutes(30)) { transitionTo(TestState.DONE) } }
        }
        val timeout = builder.stateDefinitions[TestState.A]!!.builder.timeout!!
        val result = runBlocking { timeout.handler(TestInput()) }
        assertThat(result).isEqualTo(TransitionResult.TransitionTo(TestState.DONE))
    }

    @Test
    fun `immediateTransitionTo is registered`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) { immediatelyTransitionTo(TestState.B) }
        }
        assertThat(builder.stateDefinitions[TestState.A]!!.builder.immediateTransitionTo)
            .isEqualTo(TestState.B)
    }

    @Test
    fun `onEntry and onExit hooks are registered`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {
                onEntry { _ -> }
                onEntry { _ -> }
                onExit { _ -> }
            }
        }
        val stateBuilder = builder.stateDefinitions[TestState.A]!!.builder
        assertThat(stateBuilder.onEntryHooks).hasSize(2)
        assertThat(stateBuilder.onExitHooks).hasSize(1)
    }

    @Test
    fun `middleware classes are registered in order`() {
        class FirstMiddleware : StateMachineMiddleware<TestState, TestEvent, TestInput>()

        class SecondMiddleware : StateMachineMiddleware<TestState, TestEvent, TestInput>()

        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) { on<TestEvent.Stop> { _, _ -> transitionTo(TestState.DONE) } }
            state(TestState.DONE) { terminal() }
            middleware<FirstMiddleware>()
            middleware<SecondMiddleware>()
        }
        assertThat(builder.middlewareClasses).hasSize(2)
        assertThat(builder.middlewareClasses[0]).isEqualTo(FirstMiddleware::class.java)
        assertThat(builder.middlewareClasses[1]).isEqualTo(SecondMiddleware::class.java)
    }

    @Test
    fun `universalMiddleware classes are registered`() {
        class GlobalAudit : UniversalStateMachineMiddleware()

        class GlobalMetrics : UniversalStateMachineMiddleware()

        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) { on<TestEvent.Stop> { _, _ -> transitionTo(TestState.DONE) } }
            state(TestState.DONE) { terminal() }
            universalMiddleware<GlobalAudit>()
            universalMiddleware<GlobalMetrics>()
        }
        assertThat(builder.middlewareClasses).hasSize(2)
        assertThat(builder.middlewareClasses[0]).isEqualTo(GlobalAudit::class.java)
        assertThat(builder.middlewareClasses[1]).isEqualTo(GlobalMetrics::class.java)
    }

    @Test
    fun `typed and universal middleware can be mixed in same builder`() {
        class TypedMiddleware : StateMachineMiddleware<TestState, TestEvent, TestInput>()

        class GlobalMiddleware : UniversalStateMachineMiddleware()

        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) { on<TestEvent.Stop> { _, _ -> transitionTo(TestState.DONE) } }
            state(TestState.DONE) { terminal() }
            middleware<TypedMiddleware>()
            universalMiddleware<GlobalMiddleware>()
        }
        assertThat(builder.middlewareClasses).hasSize(2)
        assertThat(builder.middlewareClasses[0]).isEqualTo(TypedMiddleware::class.java)
        assertThat(builder.middlewareClasses[1]).isEqualTo(GlobalMiddleware::class.java)
    }

    @Test
    fun `input is threaded into StateBuilder`() {
        val input = TestInput(value = "wired")
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            definitionInput = input
            state(TestState.A) {
                on<TestEvent.Go> { _, receivedInput ->
                    assertThat(receivedInput.value).isEqualTo("wired")
                    transitionTo(TestState.B)
                }
            }
        }
        val handler = builder.stateDefinitions[TestState.A]!!.builder.handlers[0]
        runBlocking { handler.handler(TestEvent.Go(TestState.B)) }
    }

    // ── input accessor ──

    @Test
    fun `input returns definitionInput when set`() {
        val input = TestInput(value = "executing")
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            definitionInput = input
        }
        assertThat(builder.input).isSameAs(input)
    }

    @Test
    fun `input throws when definitionInput is null`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>()
        assertThatThrownBy { builder.input }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("input is only available")
    }

    // ── Validation tests ──

    @Test
    fun `validate - valid state machine returns empty errors`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) { on<TestEvent.Go> { _, _ -> transitionTo(TestState.B) } }
            state(TestState.B) { on<TestEvent.Stop> { _, _ -> transitionTo(TestState.DONE) } }
            state(TestState.DONE) { terminal() }
        }
        assertThat(builder.validate()).isEmpty()
    }

    @Test
    fun `validate - state with no handlers and no terminal hangs`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {}
            state(TestState.DONE) { terminal() }
        }
        val errors = builder.validate()
        assertThat(errors).hasSize(1)
        assertThat(errors[0]).contains("A").contains("will hang")
    }

    @Test
    fun `validate - state with only timeout does not hang`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) { timeout(Duration.ofDays(1)) { transitionTo(TestState.DONE) } }
            state(TestState.DONE) { terminal() }
        }
        assertThat(builder.validate()).isEmpty()
    }

    @Test
    fun `validate - state with only immediatelyTransitionTo does not hang`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) { immediatelyTransitionTo(TestState.B) }
            state(TestState.B) { on<TestEvent.Stop> { _, _ -> transitionTo(TestState.DONE) } }
            state(TestState.DONE) { terminal() }
        }
        assertThat(builder.validate()).isEmpty()
    }

    @Test
    fun `validate - immediatelyTransitionTo target must be defined`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) { immediatelyTransitionTo(TestState.C) }
            state(TestState.DONE) { terminal() }
        }
        val errors = builder.validate()
        assertThat(errors).anyMatch { it.contains("C") && it.contains("not defined") }
    }

    @Test
    fun `validate - terminal state with handlers produces warning`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.DONE) {
                terminal()
                on<TestEvent.Go> { _, _ -> transitionTo(TestState.A) }
            }
        }
        val errors = builder.validate()
        assertThat(errors).anyMatch { it.contains("DONE") && it.contains("never execute") }
    }

    @Test
    fun `validate - terminal state without handlers is valid`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) { on<TestEvent.Stop> { _, _ -> transitionTo(TestState.DONE) } }
            state(TestState.DONE) { terminal() }
        }
        assertThat(builder.validate()).isEmpty()
    }

    @Test
    fun `validate - multiple errors returned`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {}
            state(TestState.B) {}
        }
        assertThat(builder.validate()).hasSize(2)
    }

    @Test
    fun `validateOrThrow - throws on errors`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {}
        }
        assertThrows<StateMachineBuilder.StateMachineValidationException> {
            builder.validateOrThrow()
        }
    }

    @Test
    fun `validateOrThrow - error message contains all failures`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {}
            state(TestState.B) {}
        }
        val ex = assertThrows<StateMachineBuilder.StateMachineValidationException> { builder.validateOrThrow() }
        assertThat(ex.message).contains("A").contains("B").contains("will hang")
    }

    @Test
    fun `validateOrThrow - does not throw when valid`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) { on<TestEvent.Go> { _, _ -> transitionTo(TestState.DONE) } }
            state(TestState.DONE) { terminal() }
        }
        builder.validateOrThrow()
    }

    @Test
    fun `validate - terminal with multiple handlers counts correctly`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.DONE) {
                terminal()
                on<TestEvent.Go> { _, _ -> transitionTo(TestState.A) }
                on<TestEvent.Stop> { _, _ -> stay() }
            }
        }
        val errors = builder.validate()
        assertThat(errors).anyMatch { it.contains("2 event handler(s)") }
    }

    @Test
    fun `state called twice with same key - second definition overwrites first`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) { on<TestEvent.Go> { _, _ -> transitionTo(TestState.B) } }
            state(TestState.A) { terminal() }
        }
        val stateDef = builder.stateDefinitions[TestState.A]!!
        assertThat(stateDef.builder.isTerminal).isTrue()
        assertThat(stateDef.builder.handlers).isEmpty()
    }

    @Test
    fun `empty builder has no state definitions`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>()
        assertThat(builder.stateDefinitions).isEmpty()
    }

    @Test
    fun `validate on empty builder returns no errors`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>()
        assertThat(builder.validate()).isEmpty()
    }

    @Test
    fun `validate catches multiple different rule violations simultaneously`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {} // hanging
            state(TestState.B) { immediatelyTransitionTo(TestState.C) } // target not defined
            state(TestState.DONE) {
                terminal()
                on<TestEvent.Go> { _, _ -> transitionTo(TestState.A) } // terminal with handler
            }
        }
        val errors = builder.validate()
        assertThat(errors).hasSizeGreaterThanOrEqualTo(3)
    }

    // ── Cycle detection tests ──

    @Test
    fun `validate detects immediatelyTransitionTo cycle`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) { immediatelyTransitionTo(TestState.B) }
            state(TestState.B) { immediatelyTransitionTo(TestState.A) }
        }
        val errors = builder.validate()
        assertThat(errors).anyMatch { it.contains("cycle") }
    }

    @Test
    fun `validate detects self-referencing immediatelyTransitionTo`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) { immediatelyTransitionTo(TestState.A) }
        }
        val errors = builder.validate()
        assertThat(errors).anyMatch { it.contains("cycle") }
    }

    @Test
    fun `validate detects longer immediatelyTransitionTo cycle`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) { immediatelyTransitionTo(TestState.B) }
            state(TestState.B) { immediatelyTransitionTo(TestState.C) }
            state(TestState.C) { immediatelyTransitionTo(TestState.A) }
        }
        val errors = builder.validate()
        assertThat(errors).anyMatch { it.contains("cycle") }
    }

    @Test
    fun `validate allows valid immediatelyTransitionTo chain without cycle`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) { immediatelyTransitionTo(TestState.B) }
            state(TestState.B) { immediatelyTransitionTo(TestState.C) }
            state(TestState.C) { on<TestEvent.Go> { _, _ -> transitionTo(TestState.DONE) } }
            state(TestState.DONE) { terminal() }
        }
        val errors = builder.validate()
        assertThat(errors).isEmpty()
    }

    // ── after() validation tests ──

    @Test
    fun `validate - after with valid duration passes`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {
                after(Duration.ofDays(3)) { _ -> }
                timeout(Duration.ofDays(7)) { transitionTo(TestState.DONE) }
            }
            state(TestState.DONE) { terminal() }
        }
        assertThat(builder.validate()).isEmpty()
    }

    @Test
    fun `validate - after duration greater than or equal to timeout produces error`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {
                after(Duration.ofDays(7)) { _ -> }
                timeout(Duration.ofDays(5)) { transitionTo(TestState.DONE) }
            }
            state(TestState.DONE) { terminal() }
        }
        val errors = builder.validate()
        assertThat(errors).anyMatch { it.contains("after[0]") && it.contains("will never fire") }
    }

    @Test
    fun `validate - after duration equal to timeout produces error`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {
                after(Duration.ofDays(5)) { _ -> }
                timeout(Duration.ofDays(5)) { transitionTo(TestState.DONE) }
            }
            state(TestState.DONE) { terminal() }
        }
        val errors = builder.validate()
        assertThat(errors).anyMatch { it.contains("after[0]") && it.contains("will never fire") }
    }

    @Test
    fun `validate - after with non-positive duration produces error`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {
                after(Duration.ZERO) { _ -> }
                timeout(Duration.ofDays(7)) { transitionTo(TestState.DONE) }
            }
            state(TestState.DONE) { terminal() }
        }
        val errors = builder.validate()
        assertThat(errors).anyMatch { it.contains("after[0]") && it.contains("non-positive") }
    }

    @Test
    fun `validate - after with negative duration produces error`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {
                after(Duration.ofDays(-1)) { _ -> }
                timeout(Duration.ofDays(7)) { transitionTo(TestState.DONE) }
            }
            state(TestState.DONE) { terminal() }
        }
        val errors = builder.validate()
        assertThat(errors).anyMatch { it.contains("after[0]") && it.contains("non-positive") }
    }

    @Test
    fun `validate - duplicate after durations produce error`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {
                after(Duration.ofDays(3)) { _ -> }
                after(Duration.ofDays(3)) { _ -> }
                timeout(Duration.ofDays(7)) { transitionTo(TestState.DONE) }
            }
            state(TestState.DONE) { terminal() }
        }
        val errors = builder.validate()
        assertThat(errors).anyMatch { it.contains("same duration") }
    }

    @Test
    fun `validate - after without timeout is valid`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {
                after(Duration.ofDays(3)) { _ -> }
                on<TestEvent.Go> { _, _ -> transitionTo(TestState.DONE) }
            }
            state(TestState.DONE) { terminal() }
        }
        assertThat(builder.validate()).isEmpty()
    }

    @Test
    fun `validate - state with only after hooks still hangs`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {
                after(Duration.ofDays(3)) { _ -> }
            }
            state(TestState.DONE) { terminal() }
        }
        val errors = builder.validate()
        assertThat(errors).anyMatch { it.contains("A") && it.contains("will hang") }
    }

    @Test
    fun `validate - multiple after hooks with valid durations pass`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {
                after(Duration.ofDays(3)) { _ -> }
                after(Duration.ofDays(5)) { _ -> }
                timeout(Duration.ofDays(10)) { transitionTo(TestState.DONE) }
            }
            state(TestState.DONE) { terminal() }
        }
        assertThat(builder.validate()).isEmpty()
    }

    @Test
    fun `validate - after hooks declared after timeout produce error`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {
                timeout(Duration.ofDays(10)) { transitionTo(TestState.DONE) }
                after(Duration.ofDays(3)) { _ -> }
            }
            state(TestState.DONE) { terminal() }
        }
        val errors = builder.validate()
        assertThat(errors).anyMatch {
            it.contains("after[0]") && it.contains("before timeout") && it.contains("execution order")
        }
    }

    @Test
    fun `validate - after hooks declared out of duration order produce error`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {
                after(Duration.ofDays(5)) { _ -> }
                after(Duration.ofDays(3)) { _ -> }
                timeout(Duration.ofDays(10)) { transitionTo(TestState.DONE) }
            }
            state(TestState.DONE) { terminal() }
        }
        val errors = builder.validate()
        assertThat(errors).anyMatch {
            it.contains("after[1]") && it.contains("increasing duration order") && it.contains("fresh execution")
        }
    }

    @Test
    fun `validate - explicit after hook ids still require increasing duration order`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {
                after(Duration.ofDays(5), id = "later") { _ -> }
                after(Duration.ofDays(3), id = "earlier") { _ -> }
                timeout(Duration.ofDays(10)) { transitionTo(TestState.DONE) }
            }
            state(TestState.DONE) { terminal() }
        }
        val errors = builder.validate()
        assertThat(errors).anyMatch { it.contains("increasing duration order") }
        assertThat(errors).noneMatch { it.contains("same id") }
    }

    // ── Rule 9: after() hook id uniqueness ──

    @Test
    fun `validate - two after hooks sharing an explicit id produce error`() {
        // Distinct durations isolate Rule 8 (duplicate id) from Rule 6 (duplicate duration): only the
        // shared explicit id is wrong here, so the sole error must be the id collision.
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {
                after(Duration.ofDays(3), id = "dup") { _ -> }
                after(Duration.ofDays(5), id = "dup") { _ -> }
                timeout(Duration.ofDays(7)) { transitionTo(TestState.DONE) }
            }
            state(TestState.DONE) { terminal() }
        }
        val errors = builder.validate()
        assertThat(errors).anyMatch { it.contains("same id") && it.contains("dup") }
        assertThat(errors).noneMatch { it.contains("same duration") }
    }

    @Test
    fun `validate - after hooks with distinct derived ordinal ids pass Rule 8`() {
        val builder = StateMachineBuilder<TestState, TestEvent, TestInput>().apply {
            state(TestState.A) {
                after(Duration.ofDays(3)) { _ -> }
                after(Duration.ofDays(5)) { _ -> }
                timeout(Duration.ofDays(7)) { transitionTo(TestState.DONE) }
            }
            state(TestState.DONE) { terminal() }
        }
        assertThat(builder.validate()).noneMatch { it.contains("same id") }
    }
}
