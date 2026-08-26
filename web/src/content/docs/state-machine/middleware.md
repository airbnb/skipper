---
title: Middleware
description: Cross-cutting lifecycle hooks for metrics, audit, and alerting, attached to a state machine.
section: State Machine
order: 44
---

Middleware lets you attach cross-cutting hooks to a state machine — metrics, audit logs,
alerting, structured tracing — without sprinkling that code through every handler. The framework
runs your hooks in registration order at five lifecycle points: before/after every transition,
on invalid transitions, on terminal-state entry, and on initial-state entry.

There are two base classes to subclass: `StateMachineMiddleware<StateT, EventT, InputT>` (typed,
bound to one state machine) and `UniversalStateMachineMiddleware` (type-erased, usable across any
state machine).

## Lifecycle Hooks

Every middleware subclass can override these five hooks. The framework dispatches the appropriate
context object to each call.

| Hook | Fires when | Context |
|------|------------|---------|
| `beforeTransition(ctx)` | A handler resolved to `Stay` or `TransitionTo`, before hooks run | `BeforeTransitionContext` (no `toState` yet) |
| `afterTransition(ctx)` | A successful `Stay` or `TransitionTo` finished. **Not** for `Ignore`. | `AfterTransitionContext` (full from/to/trigger) |
| `onInvalidTransition(ctx)` | No handler matched the event in the current state | `InvalidTransitionContext` |
| `onTerminalStateReached(ctx)` | A terminal state was entered, after `onEntry` hooks | `TerminalStateReachedContext` |
| `onInitialStateEntered(ctx)` | The state machine just entered its initial state (cold start) | `InitialStateEnteredContext` |

`onInitialStateEntered` is the symmetric counterpart of `onTerminalStateReached`. It fires
exactly once per state machine, on the very first execution, before the event loop begins.

## TransitionTrigger

The `trigger` field on the context tells you what caused the transition. It is a sealed family:

| Trigger | Meaning |
|---------|---------|
| `TransitionTrigger.Event(event)` | A user event arrived and matched a handler |
| `TransitionTrigger.Timeout(duration)` | The state's `timeout` fired |
| `TransitionTrigger.AutoTransition` | `immediatelyTransitionTo` fired after `onEntry` |
| `TransitionTrigger.InitialState` | The very first state entry; only valid in `onInitialStateEntered` / `onTerminalStateReached` (when the initial state itself is terminal) |

Always exhaustively `when` on the trigger so you handle each kind explicitly — the compiler will
catch missing branches because the family is `sealed`.

## Typed Middleware

`StateMachineMiddleware<StateT, EventT, InputT>` is the right choice when your middleware is
scoped to one state machine and you want compile-time access to the concrete state/event/input
types.

> Middleware is an `Actions` subclass under the hood, so dependency injection follows the same
> rules as for Actions: use `@Inject lateinit var` (or `@Inject` on a Java field) — **not**
> constructor injection. Skipper's action proxy instantiates middleware via its no-arg
> constructor and then injects members.

```kotlin
@SkipperOpen
open class TicketMetricsMiddleware : StateMachineMiddleware<TicketState, TicketEvent, TicketInput>() {
    @Inject private lateinit var metrics: MetricRegistry

    override suspend fun afterTransition(
        ctx: AfterTransitionContext<TicketState, TicketEvent, TicketInput>,
    ) {
        val triggerName = when (val trigger = ctx.trigger) {
            is TransitionTrigger.Event -> trigger.event::class.simpleName ?: "Unknown"
            is TransitionTrigger.Timeout -> "timeout"
            is TransitionTrigger.AutoTransition -> "auto"
            is TransitionTrigger.InitialState -> "initial"
        }
        metrics.counterWithFixedSchema("ticket.transition", listOf("from", "to", "trigger"))
            .tag("from", ctx.fromState.name)
            .tag("to", ctx.toState.name)
            .tag("trigger", triggerName)
            .inc()
    }

    override suspend fun onInvalidTransition(
        ctx: InvalidTransitionContext<TicketState, TicketEvent, TicketInput>,
    ) {
        metrics.counterWithFixedSchema("ticket.invalid_transition", listOf("state", "event"))
            .tag("state", ctx.fromState.name)
            .tag("event", ctx.event::class.simpleName ?: "Unknown")
            .inc()
    }
}
```

Register it in `define()`:

```kotlin
override fun StateMachineBuilder<TicketState, TicketEvent, TicketInput>.define() {
    state(TicketState.OPEN) { handleOpen() }
    // ... other states
    middleware<TicketMetricsMiddleware>()
}
```

> A fixed-schema metrics pattern is the right choice here — tag values are dynamic (state and
> event names), so declare the metric schema once at build time and bind tag values per emit.

## Universal Middleware

`UniversalStateMachineMiddleware` is the right choice when you want one middleware to apply to
every state machine in the service, regardless of their type parameters — for example, a global
audit logger that just needs the state name (a `String`) rather than the typed state.

```kotlin
@SkipperOpen
open class GlobalAuditMiddleware : UniversalStateMachineMiddleware() {
    @Inject private lateinit var audit: AuditLog

    override suspend fun afterTransition(ctx: AfterTransitionContext<Enum<*>, Any, Any>) {
        audit.record(
            workflowId = ctx.stateMachineId,
            machineClass = ctx.stateMachineClass.simpleName,
            from = ctx.fromState.name,
            to = ctx.toState.name,
        )
    }
}
```

Register it with `universalMiddleware<...>()`:

```kotlin
universalMiddleware<GlobalAuditMiddleware>()
```

## Registration Order and Skipped Hooks

- Middleware execute in the order they are registered.
- `beforeTransition` and `afterTransition` both fire only on successful transitions (`Stay` or
  `TransitionTo`). **Neither** fires when a handler returns `ignore()` or when a guard rejects
  an event — `ignore()` is a silent no-op by design (see
  [Key Concepts](/docs/state-machine/overview/)). If you need to
  observe ignored events, read the event history in the [Admin UI](/docs/state-machine/admin-ui/) — the outcome
  column distinguishes `IGNORE_EXPLICIT` from `IGNORE_GUARD`.
- `onInvalidTransition` fires only when **no handler is registered** for the event in the
  current state — it is the "the DSL author forgot a case" signal, not the "the handler chose to
  ignore this event" signal.
- All five middleware hooks are immediately checkpointed by the framework. They are durable at
  the point they run; they will not duplicate on retry as long as your hook body is itself
  idempotent or its side effects route through `@Execute` Actions.

## Guice Injection

Middleware are instantiated through Skipper's action proxy (`actions(MiddlewareClass::class.java)`),
which means **Guice `@Inject` works** the same way it does for Actions. You can inject metrics
registries, audit clients, configuration, etc.

## Where Middleware Show Up in the Admin UI

Middleware spans appear in the transition timeline and the Mermaid sequence diagram in the
[Admin UI](/docs/state-machine/admin-ui/). They are visually scoped to the transition that ran them, so it is easy
to see what fired before/after each state change.
