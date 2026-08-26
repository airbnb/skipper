@file:Suppress("ForbiddenImport")

package com.airbnb.skipper.statemachine

import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for the type-erased middleware bridge logic.
 *
 * Verifies that:
 * - [ErasedStateMachineMiddleware] erased methods are no-ops by default
 * - [StateMachineMiddleware] bridges erased `<Any, Any, Any>` context to typed context
 * - [UniversalStateMachineMiddleware] bridges erased context to `<Any, Any, Any>` typed context
 * - [onTerminalStateReached] hook works across all three tiers
 */
class ErasedMiddlewareTest {
    enum class TestState { A, B }

    sealed class TestEvent : StateMachineEvent() {
        object Go : TestEvent()
    }

    data class TestInput(val id: String)

    private val smId = "test-sm-id"

    @Suppress("UNCHECKED_CAST")
    private val smClass = ErasedMiddlewareTest::class.java as Class<out SkipperStateMachine<*, *, *>>

    // ── Test 1: ErasedStateMachineMiddleware default no-ops ──

    @Test
    fun `ErasedStateMachineMiddleware erased methods are no-ops by default`() {
        val erased = object : ErasedStateMachineMiddleware() {
            override suspend fun beforeTransitionMiddlewareHook(ctx: BeforeTransitionContext<Enum<*>, Any, Any>) = Unit

            override suspend fun afterTransitionMiddlewareHook(ctx: AfterTransitionContext<Enum<*>, Any, Any>) = Unit

            override suspend fun onInvalidTransitionMiddlewareHook(ctx: InvalidTransitionContext<Enum<*>, Any, Any>) = Unit

            override suspend fun onTerminalStateReachedMiddlewareHook(ctx: TerminalStateReachedContext<Enum<*>, Any, Any>) = Unit

            override suspend fun onInitialStateEnteredMiddlewareHook(ctx: InitialStateEnteredContext<Enum<*>, Any>) = Unit
        }
        val trigger = TransitionTrigger.Event(TestEvent.Go)
        val beforeCtx = BeforeTransitionContext<TestState, TestEvent, TestInput>(TestState.A, trigger, TestInput(""), smId, smClass)
        val afterCtx = AfterTransitionContext<TestState, TestEvent, TestInput>(TestState.A, TestState.B, trigger, TestInput(""), smId, smClass)
        val invalidCtx = InvalidTransitionContext<TestState, TestEvent, TestInput>(TestState.A, TestEvent.Go, TestInput(""), smId, smClass)
        val terminalCtx = TerminalStateReachedContext<TestState, TestEvent, TestInput>(TestState.B, trigger, TestInput(""), smId, smClass)

        runBlocking {
            erased.beforeTransitionMiddleware(MiddlewareCheckpoint(beforeCtx))
            erased.afterTransitionMiddleware(MiddlewareCheckpoint(afterCtx))
            erased.onInvalidTransitionMiddleware(MiddlewareCheckpoint(invalidCtx))
            erased.onTerminalStateReachedMiddleware(MiddlewareCheckpoint(terminalCtx))
        }
    }

    // ── Test 2: StateMachineMiddleware bridges erased context to typed context ──

    @Test
    fun `StateMachineMiddleware bridges erased context to typed context`() {
        val captured = mutableListOf<BeforeTransitionContext<TestState, TestEvent, TestInput>>()

        class BridgingMiddleware : StateMachineMiddleware<TestState, TestEvent, TestInput>() {
            override suspend fun beforeTransition(ctx: BeforeTransitionContext<TestState, TestEvent, TestInput>) {
                captured.add(ctx)
            }
        }

        val middleware = BridgingMiddleware()
        val input = TestInput("x")
        val trigger = TransitionTrigger.Event(TestEvent.Go)
        val typedCtx = BeforeTransitionContext(TestState.A, trigger, input, smId, smClass)

        runBlocking { middleware.beforeTransitionMiddleware(MiddlewareCheckpoint(typedCtx)) }

        assertThat(captured).hasSize(1)
        assertThat(captured[0].fromState).isEqualTo(TestState.A)
        assertThat(captured[0].trigger).isEqualTo(trigger)
        assertThat(captured[0].input).isEqualTo(input)
        assertThat(captured[0].stateMachineId).isEqualTo(smId)
        assertThat(captured[0].stateMachineClass).isEqualTo(smClass)
    }

    // ── Test 3: UniversalStateMachineMiddleware bridges erased context to Any-typed context ──

    @Test
    fun `UniversalStateMachineMiddleware bridges erased context to Any-typed context`() {
        val captured = mutableListOf<BeforeTransitionContext<Enum<*>, Any, Any>>()

        class GlobalMiddleware : UniversalStateMachineMiddleware() {
            override suspend fun beforeTransition(ctx: BeforeTransitionContext<Enum<*>, Any, Any>) {
                captured.add(ctx)
            }
        }

        val middleware = GlobalMiddleware()
        val input = TestInput("y")
        val trigger = TransitionTrigger.Event(TestEvent.Go)
        val typedCtx = BeforeTransitionContext(TestState.A, trigger, input, smId, smClass)

        runBlocking { middleware.beforeTransitionMiddleware(MiddlewareCheckpoint(typedCtx)) }

        assertThat(captured).hasSize(1)
        assertThat(captured[0].fromState).isEqualTo(TestState.A)
        assertThat(captured[0].trigger).isEqualTo(trigger)
        assertThat(captured[0].input).isEqualTo(input)
    }

    // ── Test 4: StateMachineMiddleware bridge handles Timeout trigger correctly ──

    @Test
    fun `StateMachineMiddleware bridge handles Timeout trigger correctly`() {
        val captured = mutableListOf<BeforeTransitionContext<TestState, TestEvent, TestInput>>()

        class BridgingMiddleware : StateMachineMiddleware<TestState, TestEvent, TestInput>() {
            override suspend fun beforeTransition(ctx: BeforeTransitionContext<TestState, TestEvent, TestInput>) {
                captured.add(ctx)
            }
        }

        val middleware = BridgingMiddleware()
        val input = TestInput("z")
        val trigger = TransitionTrigger.Timeout(Duration.ofMinutes(5))
        val typedCtx = BeforeTransitionContext<TestState, TestEvent, TestInput>(TestState.A, trigger, input, smId, smClass)

        runBlocking { middleware.beforeTransitionMiddleware(MiddlewareCheckpoint(typedCtx)) }

        assertThat(captured).hasSize(1)
        assertThat(captured[0].trigger).isInstanceOf(TransitionTrigger.Timeout::class.java)
        assertThat((captured[0].trigger as TransitionTrigger.Timeout).duration).isEqualTo(Duration.ofMinutes(5))
        assertThat(captured[0].fromState).isEqualTo(TestState.A)
    }

    // ── Test 5: StateMachineMiddleware bridges afterTransitionMiddleware to typed afterTransition ──

    @Test
    fun `StateMachineMiddleware bridges afterTransitionMiddleware to typed afterTransition`() {
        val captured = mutableListOf<AfterTransitionContext<TestState, TestEvent, TestInput>>()

        class BridgingMiddleware : StateMachineMiddleware<TestState, TestEvent, TestInput>() {
            override suspend fun afterTransition(ctx: AfterTransitionContext<TestState, TestEvent, TestInput>) {
                captured.add(ctx)
            }
        }

        val middleware = BridgingMiddleware()
        val input = TestInput("a")
        val trigger = TransitionTrigger.Event(TestEvent.Go)
        val typedCtx = AfterTransitionContext(TestState.A, TestState.B, trigger, input, smId, smClass)

        runBlocking { middleware.afterTransitionMiddleware(MiddlewareCheckpoint(typedCtx)) }

        assertThat(captured).hasSize(1)
        assertThat(captured[0].fromState).isEqualTo(TestState.A)
        assertThat(captured[0].toState).isEqualTo(TestState.B)
        assertThat(captured[0].trigger).isEqualTo(trigger)
    }

    // ── Test 6: StateMachineMiddleware bridges onInvalidTransitionMiddleware ──

    @Test
    fun `StateMachineMiddleware bridges onInvalidTransitionMiddleware to typed onInvalidTransition`() {
        val captured = mutableListOf<InvalidTransitionContext<TestState, TestEvent, TestInput>>()

        class BridgingMiddleware : StateMachineMiddleware<TestState, TestEvent, TestInput>() {
            override suspend fun onInvalidTransition(ctx: InvalidTransitionContext<TestState, TestEvent, TestInput>) {
                captured.add(ctx)
            }
        }

        val middleware = BridgingMiddleware()
        val input = TestInput("b")
        val typedCtx = InvalidTransitionContext(TestState.A, TestEvent.Go, input, smId, smClass)

        runBlocking { middleware.onInvalidTransitionMiddleware(MiddlewareCheckpoint(typedCtx)) }

        assertThat(captured).hasSize(1)
        assertThat(captured[0].fromState).isEqualTo(TestState.A)
        assertThat(captured[0].event).isEqualTo(TestEvent.Go)
    }

    // ── Test 7: StateMachineMiddleware bridges onTerminalStateReachedMiddleware ──

    @Test
    fun `StateMachineMiddleware bridges onTerminalStateReachedMiddleware to typed onTerminalStateReached`() {
        val captured = mutableListOf<TerminalStateReachedContext<TestState, TestEvent, TestInput>>()

        class BridgingMiddleware : StateMachineMiddleware<TestState, TestEvent, TestInput>() {
            override suspend fun onTerminalStateReached(ctx: TerminalStateReachedContext<TestState, TestEvent, TestInput>) {
                captured.add(ctx)
            }
        }

        val middleware = BridgingMiddleware()
        val trigger = TransitionTrigger.Event(TestEvent.Go)
        val typedCtx = TerminalStateReachedContext(TestState.B, trigger, TestInput("done"), smId, smClass)

        runBlocking { middleware.onTerminalStateReachedMiddleware(MiddlewareCheckpoint(typedCtx)) }

        assertThat(captured).hasSize(1)
        assertThat(captured[0].terminalState).isEqualTo(TestState.B)
        assertThat(captured[0].input).isEqualTo(TestInput("done"))
        assertThat(captured[0].stateMachineId).isEqualTo(smId)
        assertThat(captured[0].stateMachineClass).isEqualTo(smClass)
    }

    // ── Test 8: StateMachineMiddleware default typed methods are no-ops ──

    @Test
    fun `StateMachineMiddleware default typed methods are no-ops`() {
        val middleware = object : StateMachineMiddleware<TestState, TestEvent, TestInput>() {}
        val input = TestInput("c")
        val trigger = TransitionTrigger.Event(TestEvent.Go)

        runBlocking {
            middleware.beforeTransition(BeforeTransitionContext(TestState.A, trigger, input, smId, smClass))
            middleware.afterTransition(AfterTransitionContext(TestState.A, TestState.B, trigger, input, smId, smClass))
            middleware.onInvalidTransition(InvalidTransitionContext(TestState.A, TestEvent.Go, input, smId, smClass))
            middleware.onTerminalStateReached(TerminalStateReachedContext(TestState.B, trigger, input, smId, smClass))
        }
    }

    // ── Test 9: UniversalStateMachineMiddleware default typed methods are no-ops ──

    @Test
    fun `UniversalStateMachineMiddleware default typed methods are no-ops`() {
        val middleware = object : UniversalStateMachineMiddleware() {}
        val input = TestInput("d")
        val trigger = TransitionTrigger.Event(TestEvent.Go)

        runBlocking {
            middleware.beforeTransition(BeforeTransitionContext(TestState.A, trigger, input, smId, smClass))
            middleware.afterTransition(AfterTransitionContext(TestState.A, TestState.B, trigger, input, smId, smClass))
            middleware.onInvalidTransition(InvalidTransitionContext(TestState.A, TestEvent.Go, input, smId, smClass))
            middleware.onTerminalStateReached(TerminalStateReachedContext(TestState.B, trigger, input, smId, smClass))
        }
    }

    // ── Test 10: UniversalStateMachineMiddleware all four hooks are invoked ──

    @Test
    fun `UniversalStateMachineMiddleware all four hooks are invoked`() {
        val log = mutableListOf<String>()

        class GlobalMiddleware : UniversalStateMachineMiddleware() {
            override suspend fun beforeTransition(ctx: BeforeTransitionContext<Enum<*>, Any, Any>) {
                log.add("before")
            }

            override suspend fun afterTransition(ctx: AfterTransitionContext<Enum<*>, Any, Any>) {
                log.add("after")
            }

            override suspend fun onInvalidTransition(ctx: InvalidTransitionContext<Enum<*>, Any, Any>) {
                log.add("invalid")
            }

            override suspend fun onTerminalStateReached(ctx: TerminalStateReachedContext<Enum<*>, Any, Any>) {
                log.add("terminal")
            }
        }

        val middleware = GlobalMiddleware()
        val input = TestInput("w")
        val trigger = TransitionTrigger.Event(TestEvent.Go)

        runBlocking {
            middleware.beforeTransitionMiddleware(MiddlewareCheckpoint(BeforeTransitionContext(TestState.A, trigger, input, smId, smClass)))
            middleware.afterTransitionMiddleware(
                MiddlewareCheckpoint(AfterTransitionContext(TestState.A, TestState.B, trigger, input, smId, smClass))
            )
            middleware.onInvalidTransitionMiddleware(MiddlewareCheckpoint(InvalidTransitionContext(TestState.A, TestEvent.Go, input, smId, smClass)))
            middleware.onTerminalStateReachedMiddleware(MiddlewareCheckpoint(TerminalStateReachedContext(TestState.B, trigger, input, smId, smClass)))
        }

        assertThat(log).containsExactly("before", "after", "invalid", "terminal")
    }
}
