@file:Suppress("ForbiddenImport")

package com.airbnb.skipper.statemachine

import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * DSL-level tests for the state machine builder pipeline.
 *
 * These tests exercise builder → state definitions → handler invocation → transition
 * results without Skipper's execution engine (no waitUntil, checkpoint, or WorkflowEngine).
 *
 * For full Skipper engine integration tests, see [SkipperStateMachineTest].
 */
class StateMachineDslTest {
    @Suppress("UNCHECKED_CAST")
    private val testSmClass = StateMachineDslTest::class.java as Class<out SkipperStateMachine<*, *, *>>

    // ── Domain model used across integration tests ──

    enum class OrderState { PENDING, ACTIVE, FULFILLED, CANCELLED, EXPIRED }

    sealed class OrderEvent : StateMachineEvent() {
        data class Pay(val amount: Int) : OrderEvent()

        data class Ship(val trackingId: String) : OrderEvent()

        object Cancel : OrderEvent()

        object Deliver : OrderEvent()
    }

    data class OrderInput(val orderId: String, val maxAmount: Int = 1000)

    // ── Helper: walk a handler chain through a list of events ──

    private suspend fun processEvents(
        builder: StateMachineBuilder<OrderState, OrderEvent, OrderInput>,
        input: OrderInput,
        events: List<OrderEvent>,
        startState: OrderState,
    ): OrderState {
        var current = startState
        for (event in events) {
            val stateDef = builder.stateDefinitions[current]
                ?: error("No definition for $current")
            val handler = stateDef.builder.handlers.firstOrNull { it.eventClass.java.isInstance(event) }
                ?: error("No handler for ${event::class.simpleName} in $current")
            val result = handler.handler(event)
            if (result is TransitionResult.TransitionTo) current = result.newState
        }
        return current
    }

    // ── Test 1: Full happy-path transition chain ──

    @Test
    fun `full happy path - PENDING to FULFILLED`() {
        val input = OrderInput(orderId = "order-1")
        val builder = StateMachineBuilder<OrderState, OrderEvent, OrderInput>().apply {
            definitionInput = input
            state(OrderState.PENDING) {
                on<OrderEvent.Pay> { event, _ ->
                    if (event.amount > 0) {
                        transitionTo(OrderState.ACTIVE)
                    } else {
                        ignore()
                    }
                }
                on<OrderEvent.Cancel> { _, _ -> transitionTo(OrderState.CANCELLED) }
            }
            state(OrderState.ACTIVE) {
                on<OrderEvent.Ship> { _, _ -> transitionTo(OrderState.FULFILLED) }
                on<OrderEvent.Cancel> { _, _ -> transitionTo(OrderState.CANCELLED) }
            }
            state(OrderState.FULFILLED) { terminal() }
            state(OrderState.CANCELLED) { terminal() }
        }

        assertThat(builder.validate()).isEmpty()

        val finalState = runBlocking {
            processEvents(
                builder,
                input,
                listOf(OrderEvent.Pay(100), OrderEvent.Ship("TRK-001")),
                OrderState.PENDING,
            )
        }
        assertThat(finalState).isEqualTo(OrderState.FULFILLED)
    }

    @Test
    fun `cancellation path - PENDING to CANCELLED`() {
        val input = OrderInput(orderId = "order-2")
        val builder = StateMachineBuilder<OrderState, OrderEvent, OrderInput>().apply {
            definitionInput = input
            state(OrderState.PENDING) {
                on<OrderEvent.Pay> { _, _ -> transitionTo(OrderState.ACTIVE) }
                on<OrderEvent.Cancel> { _, _ -> transitionTo(OrderState.CANCELLED) }
            }
            state(OrderState.ACTIVE) {
                on<OrderEvent.Ship> { _, _ -> transitionTo(OrderState.FULFILLED) }
                on<OrderEvent.Cancel> { _, _ -> transitionTo(OrderState.CANCELLED) }
            }
            state(OrderState.FULFILLED) { terminal() }
            state(OrderState.CANCELLED) { terminal() }
        }

        val finalState = runBlocking {
            processEvents(builder, input, listOf(OrderEvent.Cancel), OrderState.PENDING)
        }
        assertThat(finalState).isEqualTo(OrderState.CANCELLED)
    }

    // ── Test 2: Guard conditions filter events ──

    @Test
    fun `guard filters payment below threshold`() {
        val input = OrderInput(orderId = "order-3", maxAmount = 500)
        val builder = StateMachineBuilder<OrderState, OrderEvent, OrderInput>().apply {
            definitionInput = input
            state(OrderState.PENDING) {
                on<OrderEvent.Pay>(
                    guard = { event, inp -> event.amount > 0 && event.amount <= inp.maxAmount },
                    handler = { _, _ -> transitionTo(OrderState.ACTIVE) },
                )
                on<OrderEvent.Cancel> { _, _ -> transitionTo(OrderState.CANCELLED) }
            }
            state(OrderState.ACTIVE) { terminal() }
            state(OrderState.CANCELLED) { terminal() }
        }

        val handler = builder.stateDefinitions[OrderState.PENDING]!!.builder.handlers[0]

        // Below max — guard passes
        assertThat(runBlocking { handler.handler(OrderEvent.Pay(100)) })
            .isEqualTo(TransitionResult.TransitionTo(OrderState.ACTIVE))

        // Above max — guard fails → Ignore
        assertThat(runBlocking { handler.handler(OrderEvent.Pay(600)) })
            .isEqualTo(TransitionResult.Ignore(TransitionResult.IgnoreReason.GUARD_REJECTED))

        // Zero amount — guard fails
        assertThat(runBlocking { handler.handler(OrderEvent.Pay(0)) })
            .isEqualTo(TransitionResult.Ignore(TransitionResult.IgnoreReason.GUARD_REJECTED))
    }

    // ── Test 3: onEntry / onExit hooks run in the correct order ──

    @Test
    fun `onEntry and onExit hooks execute in registration order with correct input`() {
        val log = mutableListOf<String>()
        val input = OrderInput(orderId = "order-4")

        val builder = StateMachineBuilder<OrderState, OrderEvent, OrderInput>().apply {
            definitionInput = input
            state(OrderState.PENDING) {
                onEntry { _ -> log.add("pending-entry-1") }
                onEntry { inp -> log.add("pending-entry-2:${inp.orderId}") }
                onExit { _ -> log.add("pending-exit") }
                on<OrderEvent.Pay> { _, _ -> transitionTo(OrderState.ACTIVE) }
            }
            state(OrderState.ACTIVE) {
                onEntry { inp -> log.add("active-entry:${inp.orderId}") }
                on<OrderEvent.Ship> { _, _ -> transitionTo(OrderState.FULFILLED) }
            }
            state(OrderState.FULFILLED) { terminal() }
        }

        // Simulate entering PENDING
        val pendingDef = builder.stateDefinitions[OrderState.PENDING]!!.builder
        runBlocking { pendingDef.onEntryHooks.forEach { it(input) } }
        assertThat(log).containsExactly("pending-entry-1", "pending-entry-2:order-4")

        // Simulate Pay → transition: exit PENDING, enter ACTIVE
        log.clear()
        runBlocking { pendingDef.onExitHooks.forEach { it(input) } }
        val activeDef = builder.stateDefinitions[OrderState.ACTIVE]!!.builder
        runBlocking { activeDef.onEntryHooks.forEach { it(input) } }
        assertThat(log).containsExactly("pending-exit", "active-entry:order-4")
    }

    // ── Test 4: timeout handler returns correct transition ──

    @Test
    fun `timeout handler fires with correct transition`() {
        val input = OrderInput(orderId = "order-5")
        val builder = StateMachineBuilder<OrderState, OrderEvent, OrderInput>().apply {
            definitionInput = input
            state(OrderState.PENDING) {
                timeout(Duration.ofDays(7)) { transitionTo(OrderState.EXPIRED) }
                on<OrderEvent.Pay> { _, _ -> transitionTo(OrderState.ACTIVE) }
            }
            state(OrderState.ACTIVE) { terminal() }
            state(OrderState.EXPIRED) { terminal() }
        }

        val timeout = builder.stateDefinitions[OrderState.PENDING]!!.builder.timeout!!
        assertThat(timeout.staticDuration).isEqualTo(Duration.ofDays(7))
        val result = runBlocking { timeout.handler(input) }
        assertThat(result).isEqualTo(TransitionResult.TransitionTo(OrderState.EXPIRED))
    }

    // ── Test 5: stay() keeps state unchanged ──

    @Test
    fun `stay result leaves state unchanged`() {
        val input = OrderInput(orderId = "order-6")
        val builder = StateMachineBuilder<OrderState, OrderEvent, OrderInput>().apply {
            definitionInput = input
            state(OrderState.ACTIVE) {
                on<OrderEvent.Deliver> { _, _ -> stay() }
                on<OrderEvent.Ship> { _, _ -> transitionTo(OrderState.FULFILLED) }
            }
            state(OrderState.FULFILLED) { terminal() }
        }

        val handler = builder.stateDefinitions[OrderState.ACTIVE]!!.builder.handlers[0]
        val result = runBlocking { handler.handler(OrderEvent.Deliver) }
        assertThat(result).isEqualTo(TransitionResult.Stay)
    }

    // ── Test 6: ignore() from guard vs explicit ignore ──

    @Test
    fun `explicit ignore and guard-rejected ignore carry distinct reasons`() {
        val input = OrderInput(orderId = "order-7")
        val builder = StateMachineBuilder<OrderState, OrderEvent, OrderInput>().apply {
            definitionInput = input
            state(OrderState.PENDING) {
                on<OrderEvent.Pay>({ _, _ -> false }, { _, _ -> transitionTo(OrderState.ACTIVE) })
                on<OrderEvent.Cancel> { _, _ -> ignore() }
            }
            state(OrderState.ACTIVE) { terminal() }
        }
        val stateDef = builder.stateDefinitions[OrderState.PENDING]!!.builder

        val guardIgnore = runBlocking { stateDef.handlers[0].handler(OrderEvent.Pay(10)) }
        val explicitIgnore = runBlocking { stateDef.handlers[1].handler(OrderEvent.Cancel) }

        assertThat(guardIgnore).isEqualTo(TransitionResult.Ignore(TransitionResult.IgnoreReason.GUARD_REJECTED))
        assertThat(explicitIgnore).isEqualTo(TransitionResult.Ignore(TransitionResult.IgnoreReason.EXPLICIT))
        assertThat(guardIgnore).isNotEqualTo(explicitIgnore)
    }

    // ── Test 7: middleware classes registered correctly ──

    @Test
    fun `middleware registration order is preserved`() {
        class AuditMiddleware : StateMachineMiddleware<OrderState, OrderEvent, OrderInput>()

        class MetricsMiddleware : StateMachineMiddleware<OrderState, OrderEvent, OrderInput>()

        class LoggingMiddleware : StateMachineMiddleware<OrderState, OrderEvent, OrderInput>()

        val builder = StateMachineBuilder<OrderState, OrderEvent, OrderInput>().apply {
            state(OrderState.PENDING) { terminal() }
            middleware<AuditMiddleware>()
            middleware<MetricsMiddleware>()
            middleware<LoggingMiddleware>()
        }

        assertThat(builder.middlewareClasses).containsExactly(
            AuditMiddleware::class.java,
            MetricsMiddleware::class.java,
            LoggingMiddleware::class.java,
        )
    }

    // ── Test 8: middleware context data is assembled correctly ──

    @Test
    fun `BeforeTransitionContext is assembled with correct fields before a transition`() {
        val input = OrderInput(orderId = "order-8")
        val capturedContexts = mutableListOf<BeforeTransitionContext<OrderState, OrderEvent, OrderInput>>()

        class RecordingMiddleware : StateMachineMiddleware<OrderState, OrderEvent, OrderInput>() {
            override suspend fun beforeTransition(ctx: BeforeTransitionContext<OrderState, OrderEvent, OrderInput>) {
                capturedContexts.add(ctx)
            }
        }

        val middleware = RecordingMiddleware()
        val event: OrderEvent = OrderEvent.Pay(100)
        val trigger = TransitionTrigger.Event(event)
        val ctx = BeforeTransitionContext(OrderState.PENDING, trigger, input, "test-sm", testSmClass)
        runBlocking { middleware.beforeTransition(ctx) }

        assertThat(capturedContexts).hasSize(1)
        assertThat(capturedContexts[0].fromState).isEqualTo(OrderState.PENDING)
        assertThat(capturedContexts[0].trigger).isEqualTo(trigger)
        assertThat(capturedContexts[0].input).isEqualTo(input)
    }

    @Test
    fun `AfterTransitionContext is assembled with correct fields after a transition`() {
        val input = OrderInput(orderId = "order-9")
        val capturedContexts = mutableListOf<AfterTransitionContext<OrderState, OrderEvent, OrderInput>>()

        class RecordingMiddleware : StateMachineMiddleware<OrderState, OrderEvent, OrderInput>() {
            override suspend fun afterTransition(ctx: AfterTransitionContext<OrderState, OrderEvent, OrderInput>) {
                capturedContexts.add(ctx)
            }
        }

        val middleware = RecordingMiddleware()
        val event: OrderEvent = OrderEvent.Pay(100)
        val trigger = TransitionTrigger.Event(event)
        val ctx = AfterTransitionContext(OrderState.PENDING, OrderState.ACTIVE, trigger, input, "test-sm", testSmClass)
        runBlocking { middleware.afterTransition(ctx) }

        assertThat(capturedContexts).hasSize(1)
        assertThat(capturedContexts[0].fromState).isEqualTo(OrderState.PENDING)
        assertThat(capturedContexts[0].toState).isEqualTo(OrderState.ACTIVE)
        assertThat(capturedContexts[0].trigger).isEqualTo(trigger)
    }

    @Test
    fun `InvalidTransitionContext is assembled correctly for unhandled events`() {
        val input = OrderInput(orderId = "order-10")
        val capturedContexts = mutableListOf<InvalidTransitionContext<OrderState, OrderEvent, OrderInput>>()

        class RecordingMiddleware : StateMachineMiddleware<OrderState, OrderEvent, OrderInput>() {
            override suspend fun onInvalidTransition(ctx: InvalidTransitionContext<OrderState, OrderEvent, OrderInput>) {
                capturedContexts.add(ctx)
            }
        }

        val middleware = RecordingMiddleware()
        val event: OrderEvent = OrderEvent.Ship("TRK-999")
        val ctx = InvalidTransitionContext(OrderState.PENDING, event, input, "test-sm", testSmClass)
        runBlocking { middleware.onInvalidTransition(ctx) }

        assertThat(capturedContexts).hasSize(1)
        assertThat(capturedContexts[0].fromState).isEqualTo(OrderState.PENDING)
        assertThat(capturedContexts[0].event).isEqualTo(event)
    }

    // ── Test 9: chained multi-middleware invocation ──

    @Test
    fun `multiple middleware execute in registration order`() {
        val callLog = mutableListOf<String>()

        class FirstMiddleware : StateMachineMiddleware<OrderState, OrderEvent, OrderInput>() {
            override suspend fun beforeTransition(ctx: BeforeTransitionContext<OrderState, OrderEvent, OrderInput>) {
                callLog.add("first-before")
            }

            override suspend fun afterTransition(ctx: AfterTransitionContext<OrderState, OrderEvent, OrderInput>) {
                callLog.add("first-after")
            }
        }

        class SecondMiddleware : StateMachineMiddleware<OrderState, OrderEvent, OrderInput>() {
            override suspend fun beforeTransition(ctx: BeforeTransitionContext<OrderState, OrderEvent, OrderInput>) {
                callLog.add("second-before")
            }

            override suspend fun afterTransition(ctx: AfterTransitionContext<OrderState, OrderEvent, OrderInput>) {
                callLog.add("second-after")
            }
        }

        val middlewares = listOf(FirstMiddleware(), SecondMiddleware())
        val input = OrderInput(orderId = "order-11")
        val payEvent: OrderEvent = OrderEvent.Pay(50)
        val trigger = TransitionTrigger.Event(payEvent)
        val beforeCtx = BeforeTransitionContext(OrderState.PENDING, trigger, input, "test-sm", testSmClass)
        val afterCtx = AfterTransitionContext(OrderState.PENDING, OrderState.ACTIVE, trigger, input, "test-sm", testSmClass)

        runBlocking {
            middlewares.forEach { it.beforeTransition(beforeCtx) }
            middlewares.forEach { it.afterTransition(afterCtx) }
        }

        assertThat(callLog).containsExactly("first-before", "second-before", "first-after", "second-after")
    }

    // ── Test 9b: universal middleware receives context alongside typed middleware ──

    @Test
    fun `universal middleware receives context alongside typed middleware`() {
        val log = mutableListOf<String>()

        class TypedRecorder : StateMachineMiddleware<OrderState, OrderEvent, OrderInput>() {
            override suspend fun beforeTransition(ctx: BeforeTransitionContext<OrderState, OrderEvent, OrderInput>) {
                log.add("typed:${ctx.fromState}")
            }
        }

        class UniversalRecorder : UniversalStateMachineMiddleware() {
            override suspend fun beforeTransition(ctx: BeforeTransitionContext<Enum<*>, Any, Any>) {
                log.add("universal:${ctx.fromState.name}")
            }
        }

        val input = OrderInput(orderId = "order-u1")
        val middlewares: List<ErasedStateMachineMiddleware> = listOf(TypedRecorder(), UniversalRecorder())
        val trigger = TransitionTrigger.Event(OrderEvent.Pay(100))
        val ctx = BeforeTransitionContext(OrderState.PENDING, trigger, input, "test-sm", testSmClass)
        val checkpoint = MiddlewareCheckpoint(ctx)

        runBlocking {
            middlewares.forEach { it.beforeTransitionMiddleware(checkpoint) }
        }

        assertThat(log).containsExactly("typed:PENDING", "universal:PENDING")
    }

    @Test
    fun `universal and typed middleware execute in registration order`() {
        val log = mutableListOf<String>()

        class TypedFirst : StateMachineMiddleware<OrderState, OrderEvent, OrderInput>() {
            override suspend fun afterTransition(ctx: AfterTransitionContext<OrderState, OrderEvent, OrderInput>) {
                log.add("typed-after")
            }
        }

        class UniversalSecond : UniversalStateMachineMiddleware() {
            override suspend fun afterTransition(ctx: AfterTransitionContext<Enum<*>, Any, Any>) {
                log.add("universal-after")
            }
        }

        val middlewares: List<ErasedStateMachineMiddleware> = listOf(TypedFirst(), UniversalSecond())
        val input = OrderInput(orderId = "order-u2")
        val payEvent: OrderEvent = OrderEvent.Pay(50)
        val trigger = TransitionTrigger.Event(payEvent)
        val ctx = AfterTransitionContext(OrderState.PENDING, OrderState.ACTIVE, trigger, input, "test-sm", testSmClass)
        val checkpoint = MiddlewareCheckpoint(ctx)

        runBlocking {
            middlewares.forEach { it.afterTransitionMiddleware(checkpoint) }
        }

        assertThat(log).containsExactly("typed-after", "universal-after")
    }

    // ── Test 10: immediatelyTransitionTo with onEntry/onExit ──

    @Test
    fun `immediatelyTransitionTo causes no timeout registered on source state`() {
        val input = OrderInput(orderId = "order-12")
        val builder = StateMachineBuilder<OrderState, OrderEvent, OrderInput>().apply {
            definitionInput = input
            state(OrderState.PENDING) { immediatelyTransitionTo(OrderState.ACTIVE) }
            state(OrderState.ACTIVE) {
                on<OrderEvent.Ship> { _, _ -> transitionTo(OrderState.FULFILLED) }
            }
            state(OrderState.FULFILLED) { terminal() }
        }

        val pendingDef = builder.stateDefinitions[OrderState.PENDING]!!.builder
        assertThat(pendingDef.immediateTransitionTo).isEqualTo(OrderState.ACTIVE)
        assertThat(pendingDef.timeout).isNull()
        assertThat(pendingDef.handlers).isEmpty()
        assertThat(builder.validate()).isEmpty()
    }

    // ── Test 11: complete validation of a realistic multi-state machine ──

    @Test
    fun `complex valid state machine passes validation`() {
        val input = OrderInput(orderId = "order-13")
        val builder = StateMachineBuilder<OrderState, OrderEvent, OrderInput>().apply {
            definitionInput = input
            state(OrderState.PENDING) {
                on<OrderEvent.Pay> { _, _ -> transitionTo(OrderState.ACTIVE) }
                on<OrderEvent.Cancel> { _, _ -> transitionTo(OrderState.CANCELLED) }
                timeout(Duration.ofDays(30)) { transitionTo(OrderState.EXPIRED) }
            }
            state(OrderState.ACTIVE) {
                on<OrderEvent.Ship> { _, _ -> transitionTo(OrderState.FULFILLED) }
                on<OrderEvent.Cancel> { _, _ -> transitionTo(OrderState.CANCELLED) }
            }
            state(OrderState.FULFILLED) { terminal() }
            state(OrderState.CANCELLED) { terminal() }
            state(OrderState.EXPIRED) { terminal() }
        }

        assertThat(builder.validate()).isEmpty()
    }

    // ── Test 12: handler polymorphism — base type matches subtypes ──

    @Test
    fun `event handler matches event by runtime type`() {
        val input = OrderInput(orderId = "order-14")
        val builder = StateMachineBuilder<OrderState, OrderEvent, OrderInput>().apply {
            definitionInput = input
            state(OrderState.PENDING) {
                on<OrderEvent.Pay> { _, _ -> transitionTo(OrderState.ACTIVE) }
                on<OrderEvent.Cancel> { _, _ -> transitionTo(OrderState.CANCELLED) }
            }
            state(OrderState.ACTIVE) { terminal() }
            state(OrderState.CANCELLED) { terminal() }
        }

        val handlers = builder.stateDefinitions[OrderState.PENDING]!!.builder.handlers

        // Pay matches first handler
        val payEvent: OrderEvent = OrderEvent.Pay(50)
        val payHandler = handlers.first { it.eventClass.java.isInstance(payEvent) }
        assertThat(runBlocking { payHandler.handler(payEvent) })
            .isEqualTo(TransitionResult.TransitionTo(OrderState.ACTIVE))

        // Cancel matches second handler
        val cancelEvent: OrderEvent = OrderEvent.Cancel
        val cancelHandler = handlers.first { it.eventClass.java.isInstance(cancelEvent) }
        assertThat(runBlocking { cancelHandler.handler(cancelEvent) })
            .isEqualTo(TransitionResult.TransitionTo(OrderState.CANCELLED))

        // Ship has no handler — no match
        val shipEvent: OrderEvent = OrderEvent.Ship("TRK")
        assertThat(handlers.any { it.eventClass.java.isInstance(shipEvent) }).isFalse()
    }
}
