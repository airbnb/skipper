---
title: Your First State Machine
description: Build, invoke, signal, and test a complete Skipper state machine from scratch.
section: State Machine
order: 41
---

This builds the moderated-support-ticket machine from the [Overview](/docs/state-machine/overview/),
broken into the steps you'd follow in your own service.

## 1. Declare the state enum

States are plain Kotlin enums — there's no special base type.

```kotlin
enum class TicketState { OPEN, AWAITING_AGENT, RESOLVED, ESCALATED, EXPIRED }
```

## 2. Declare the event hierarchy

Events extend `StateMachineEvent`. Use a sealed root, `object` for events without payload, and
`data class` for events that carry data.

```kotlin
sealed class TicketEvent : StateMachineEvent() {
    data class AgentAssigned(val agentId: Long) : TicketEvent()
    data class Resolution(val notes: String) : TicketEvent()
    object Escalate : TicketEvent()
}
```

`StateMachineEvent` provides class-based `equals`/`hashCode` so Skipper's serialization round-trip
passes for `object` subtypes. `data class` subtypes override with property-based equality
automatically.

## 3. Declare the input type

The input is the data your workflow needs at every step; it's passed to every handler and hook.
It must be a serializable POJO/data class — the same rules as any Skipper `@WorkflowMethod`
argument (no top-level generics; define `equals`/`hashCode`).

```kotlin
data class TicketInput(val ticketId: Long, val reporterId: Long)
```

## 4. Define the actions

All side effects — DB writes, RPCs, notifications — go through Skipper [Actions](/docs/core-concepts/).
Actions are checkpointed, so they don't re-execute on replay.

```kotlin
class TicketActions : Actions() {
    @Inject private lateinit var dao: TicketDao
    @Inject private lateinit var notifier: TicketNotifier

    @Execute suspend fun markAwaitingAgent(id: Long) { dao.updateStatus(id, "AWAITING_AGENT") }
    @Execute suspend fun markResolved(id: Long, notes: String) { dao.resolve(id, notes) }
    @Execute suspend fun markEscalated(id: Long) { dao.escalate(id) }
    @Execute suspend fun markExpired(id: Long) { dao.expire(id) }
    @Execute suspend fun sendReminder(reporterId: Long) { notifier.reminder(reporterId) }
}
```

## 5. Implement the state machine

Subclass `SkipperStateMachine<StateT, EventT, InputT>` with your three type parameters and the
initial state, and override `define()`. Keep `define()` a flat list of `state(S) { handleS() }`
calls so it reads like a state table, and put each state's behavior in a private extension
function on `StateBuilder`.

```kotlin
class TicketStateMachine :
    SkipperStateMachine<TicketState, TicketEvent, TicketInput>(TicketState.OPEN) {

    private val actions = actions<TicketActions>()

    override fun StateMachineBuilder<TicketState, TicketEvent, TicketInput>.define() {
        state(TicketState.OPEN) { handleOpen() }
        state(TicketState.AWAITING_AGENT) { handleAwaitingAgent() }
        state(TicketState.RESOLVED) { terminal() }
        state(TicketState.ESCALATED) { terminal() }
        state(TicketState.EXPIRED) { terminal() }
    }

    private fun StateBuilder<TicketState, TicketEvent, TicketInput>.handleOpen() {
        on<TicketEvent.AgentAssigned> { _, input ->
            actions.markAwaitingAgent(input.ticketId)
            transitionTo(TicketState.AWAITING_AGENT)
        }
        after(Duration.ofHours(24)) { input -> actions.sendReminder(input.reporterId) }
        timeout(Duration.ofDays(5)) { input ->
            actions.markExpired(input.ticketId)
            transitionTo(TicketState.EXPIRED)
        }
    }

    private fun StateBuilder<TicketState, TicketEvent, TicketInput>.handleAwaitingAgent() {
        on<TicketEvent.Resolution> { event, input ->
            actions.markResolved(input.ticketId, event.notes)
            transitionTo(TicketState.RESOLVED)
        }
        on<TicketEvent.Escalate> { _, input ->
            actions.markEscalated(input.ticketId)
            transitionTo(TicketState.ESCALATED)
        }
        timeout(Duration.ofDays(2)) { input ->
            actions.markEscalated(input.ticketId)
            transitionTo(TicketState.ESCALATED)
        }
    }
}
```

## 6. Invoke the state machine

A state machine is a Skipper workflow, so you create and signal it through the standard
`IWorkflowFactory` (see [Invoking Workflows](/docs/invoking-workflows/) for how to obtain one).
Use the `workflowId<SM, IdT>()` helper to derive a stable workflow id from a domain identifier, so
every caller — creator, signaller, querier — produces the same id.

```kotlin
class TicketController(private val factory: IWorkflowFactory) {

    suspend fun openTicket(ticketId: Long, reporterId: Long) {
        val id = SkipperStateMachine.workflowId<TicketStateMachine, Long>(ticketId)
        val sm = factory.builder<TicketStateMachine>(id).build()
        sm.execute(TicketInput(ticketId, reporterId))
    }

    fun assignAgent(ticketId: Long, agentId: Long) {
        val id = SkipperStateMachine.workflowId<TicketStateMachine, Long>(ticketId)
        factory<TicketStateMachine>(id).sendEvent(TicketEvent.AgentAssigned(agentId))
    }

    fun currentState(ticketId: Long): TicketState {
        val id = SkipperStateMachine.workflowId<TicketStateMachine, Long>(ticketId)
        return factory<TicketStateMachine>(id).getState()
    }
}
```

> **`.build()` vs `.runAsync().build()`**: the default `.build()` runs via Skipper's in-memory
> queue (with a DB backup) and is picked up in milliseconds — use it when the caller will
> immediately poll the machine for a state change. `.runAsync().build()` enqueues into the
> scheduler only (~1s pickup) — use it for fire-and-forget.

## 7. Test the state machine

State machines are tested with Skipper's `SkipperTest` helpers — drive the machine through events
and assert on the resulting state. (See [Testing](/docs/testing/) for the harness basics.)

```kotlin
class TicketStateMachineTest : SkipperTest() {

    @Bind private val dao: TicketDao = mock()
    @Bind private val notifier: TicketNotifier = mock()

    @Test
    fun `agent assignment then resolution transitions to RESOLVED`() = runBlocking {
        val ticketId = 42L
        val id = SkipperStateMachine.workflowId<TicketStateMachine, Long>(ticketId)
        val sm = workflowBuilder(TicketStateMachine::class.java, id).build()

        sm.execute(TicketInput(ticketId, reporterId = 7))
        sm.sendEvent(TicketEvent.AgentAssigned(agentId = 99))
        sm.sendEvent(TicketEvent.Resolution(notes = "fixed"))

        helper.waitForWorkflowToComplete()
        assertThat(sm.getState()).isEqualTo(TicketState.RESOLVED)
    }
}
```

For fast tests of transition logic alone — handler dispatch, guards, transition results — you can
build a `StateMachineBuilder` directly and walk events through its handler chain, without spinning
up Skipper's execution engine. See [Persistence & Replay](/docs/state-machine/persistence/) for why
that works.

## Next steps

- [DSL Reference](/docs/state-machine/dsl-reference/) — every method on the builders.
- [Middleware](/docs/state-machine/middleware/) — cross-cutting hooks for metrics, audit, alerting.
- [Admin UI](/docs/state-machine/admin-ui/) — inspect any instance in the browser.
