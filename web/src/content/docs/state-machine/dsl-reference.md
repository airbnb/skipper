---
title: DSL Reference
description: Every method on StateMachineBuilder and StateBuilder, plus TransitionResult, InvalidTransitionPolicy, and the validation rules.
section: State Machine
order: 42
---

The DSL is split across two builders: `StateMachineBuilder` (top-level, holds state definitions
and middleware registration) and `StateBuilder` (per-state, holds handlers, hooks, and timers).
Both are wired up inside the `define()` method of your `SkipperStateMachine` subclass.

## Table of Contents

- [StateMachineBuilder](#statemachinebuilder)
- [StateBuilder](#statebuilder)
- [TransitionResult](#transitionresult)
- [InvalidTransitionPolicy](#invalidtransitionpolicy)
- [StateMachineEvent base class](#statemachineevent-base-class)
- [Validation Rules](#validation-rules)

## StateMachineBuilder

The top-level DSL, parameterized as `StateMachineBuilder<StateT, EventT, InputT>`. Invoked
synchronously inside `SkipperStateMachine.define()`.

### `state(state) { ... }`

Define behavior for one state. Convention: delegate to a per-state handler function so `define()`
reads like a state table.

```kotlin
override fun StateMachineBuilder<TicketState, TicketEvent, TicketInput>.define() {
    state(TicketState.OPEN) { handleOpen() }
    state(TicketState.AWAITING_AGENT) { handleAwaitingAgent() }
    state(TicketState.RESOLVED) { terminal() }
}
```

### `input`

The workflow input, for branching the **shape** of `define()` (which states/hooks you declare)
across deploys. Because the input is fixed for an instance's life and identical on every replay,
branching on an input field is replay-stable with no checkpoint — old instances keep their original
shape, new instances get the new one.

```kotlin
override fun StateMachineBuilder<S, E, Input>.define() {
    state(OPEN) { handleOpen() }
    if (input.schemaVersion >= 2) state(REVIEW) { handleReview() }
    state(DONE) { terminal() }
}
```

Accessing `input` outside a running execution (it also runs in `getAdminSnapshot`/`isTerminal`
query contexts) throws — to branch *behavior* rather than shape, call `version()` inside a handler
body instead. See [Evolution](/docs/state-machine/evolution/).

### `middleware<M>()`

Register typed middleware for cross-cutting concerns scoped to this state machine's type
parameters. Multiple middleware run in registration order. See [Middleware](/docs/state-machine/middleware/).

```kotlin
middleware<TicketMetricsMiddleware>()
```

### `universalMiddleware<M>()`

Register middleware that works across any state machine, using type-erased context types.

```kotlin
universalMiddleware<GlobalAuditMiddleware>()
```

## StateBuilder

Per-state DSL, parameterized as `StateBuilder<StateT, EventT, InputT>`. Used inside `state(...)`
blocks.

| Method | Description |
|--------|-------------|
| `on<E> { event, input -> ... }` | Handle event `E`. Returns `transitionTo(S)`, `stay()`, or `ignore()`. |
| `on<E>(guard) { event, input -> ... }` | Handle `E` only if `guard(event, input)` is true; otherwise the event is ignored with reason `GUARD_REJECTED`. |
| `onEntry { input -> ... }` | Hook that runs when entering this state. **Not** checkpointed. |
| `onExit { input -> ... }` | Hook that runs when exiting this state. **Not** checkpointed. |
| `timeout(duration) { input -> ... }` | If no event causes a transition within `duration`, the handler fires and its `TransitionResult` is applied. |
| `after(duration) { input -> ... }` | Side-effect hook that fires at `duration` inside this state. Does **not** drive a transition. |
| `terminal()` | Mark this state as terminal. When entered, the workflow completes. |
| `immediatelyTransitionTo(target)` | After `onEntry` hooks, transition to `target` without waiting for an event. |
| `transitionTo(newState)` | Build a `TransitionResult` that moves to `newState`. Use inside handlers. |
| `stay()` | Build a `TransitionResult` that re-enters the same state. Hooks and middleware fire; timers reset. |
| `ignore()` | Build a `TransitionResult` that silently no-ops. No hooks, no middleware, no timer reset. |

### `on<E> { event, input -> ... }`

Register a handler for events of type `E` (any subtype of your `EventT`). The handler receives the
typed event and the workflow input. Inside the handler, the state machine's `@StateField`
properties are accessible and mutable; Skipper [Actions](/docs/workflow-api/)
can be called and will be checkpointed.

```kotlin
on<TicketEvent.AgentAssigned> { event, input ->
    actions.markAwaitingAgent(input.ticketId)
    transitionTo(TicketState.AWAITING_AGENT)
}
```

If the same event class has multiple handlers registered in a state, the **first** one to match
wins — guards are not searched for an alternative match.

### `on<E>(guard) { event, input -> ... }`

Register a handler with a guard predicate. The handler runs only if the guard returns `true`;
otherwise the event is ignored with reason `GUARD_REJECTED`. Guards are `suspend` and may
perform durable work via Actions, though most guards are pure.

```kotlin
on<TicketEvent.AgentAssigned>(
    guard = { event, input -> event.agentId in input.allowedAgents },
    handler = { _, _ -> transitionTo(TicketState.AWAITING_AGENT) }
)
```

### `onEntry { input -> ... }` and `onExit { input -> ... }`

Run on entry/exit of the state. **These hooks are not automatically checkpointed** — they
re-execute on every replay. Anything with a side effect must go through an `@Execute` Action.

```kotlin
state(TicketState.AWAITING_AGENT) {
    onEntry { input -> actions.notifyAgent(input.ticketId) }      // Action: checkpointed, safe
    onEntry { input -> dao.update(input.ticketId, "AWAITING") }   // BAD: not an action, re-runs on replay
}
```

### `timeout(duration) { input -> ... }`

Drive a transition if no event fires within `duration` after entering the state. Resets when
`transitionTo` or `stay()` is returned; cleared on terminal transitions.

If the state also declares `after` hooks, declare those hooks before `timeout` so the timer section
reads in execution order. Validation rejects `after` hooks declared after `timeout`.

```kotlin
state(TicketState.OPEN) {
    timeout(Duration.ofDays(5)) { input ->
        actions.markExpired(input.ticketId)
        transitionTo(TicketState.EXPIRED)
    }
}
```

### `timeout({ input -> duration }) { input -> ... }` — input-computed duration

When the timeout must vary per state entry — e.g. a looping `EXECUTE_STEP` state whose per-step
delay comes from the workflow input — pass a `(Input) -> Duration` lambda. It is evaluated **lazily**,
each time the event loop computes the state's deadline, so re-entering the state can use a different
duration:

```kotlin
state(WorkflowState.EXECUTE_STEP) {
    timeout({ input -> input.steps[currentStepIndex].waitDuration }) { input ->
        runStep(input.steps[currentStepIndex])
        if (currentStepIndex >= input.steps.lastIndex) transitionTo(WorkflowState.DONE) else stay()
    }
    onExit { currentStepIndex++ }   // local var rebuilt from the event log; advances per step
}
```

The deadline is `state-entry-time + durationFn(input)`, and the entry time is durable — so the same
state entry must compute the same deadline on every replay.

> **Contract — the duration lambda must be pure and replay-deterministic.** It may read only the
> immutable `Input` and replay-deterministic local state (a `var` rebuilt from the event log, like a
> step index). It must **not** read the wall clock, an `@Execute` Action result, a `@StateField`
> mutated by side effects, or any external mutable state. Otherwise the timeout can fire at a
> different point on replay than it originally did, diverging the workflow. Unlike the fixed-duration
> overload, a wrong duration here is **silent** — there is no compile-time value to inspect.
>
> **Limitations.** The `after < timeout` validation runs only for a fixed-duration timeout. It is
> skipped entirely for an input-computed timeout, because the duration can differ per state entry, so
> a single build-time check would validate one arbitrary iteration (and might invoke the lambda with
> an unexpected index) — you own that relationship. If the computed timeout is shorter than an
> `after` hook's deadline, the state exits at the timeout and that `after` hook simply never fires
> (no error, and replay-consistent — it's skipped the same way every replay); the framework logs a
> one-time warning when this happens so the misconfiguration is visible. Likewise, the admin UI's
> pending-timer view shows a deadline only for fixed-duration timeouts; a dynamic timeout's deadline
> isn't known without the input. The duration should be positive; a non-positive value fires the
> timeout immediately on entry.

### `after(duration, id = null) { input -> ... }`

Run a side-effect at a specific point inside the current state, without changing state. Useful
for reminders, escalations, and "halfway warnings".

Multiple `after` hooks per state are supported, but they must be declared before `timeout` and in
strictly increasing duration order. Fresh execution wakes by earliest deadline; replay can observe
several past deadlines at once, so validation keeps deadline order and declaration order aligned
before hook bodies run. Their deadlines reset on `stay()` (same rule as `timeout`) and clear on
transition. **They do not reset on `ignore()`**.

```kotlin
state(TicketState.OPEN) {
    after(Duration.ofHours(24)) { input -> actions.sendReminder(input.reporterId) }
    after(Duration.ofDays(3)) { input -> actions.sendFinalWarning(input.reporterId) }
    timeout(Duration.ofDays(5)) { transitionTo(TicketState.EXPIRED) }
}
```

Each hook is identified for replay by its declaration order unless an explicit stable id is provided.
Appending a hook preserves existing ordinal ids as long as the appended hook keeps durations in
increasing order. To insert or reorder hooks on a state that already has live instances, give the
hooks explicit stable ids so identity does not shift. Explicit ids stabilize hook identity; they do
not remove the increasing-duration ordering requirement. See
[Evolution](/docs/state-machine/evolution/):

```kotlin
after(Duration.ofDays(1), id = "reminder") { input -> actions.sendReminder(input.reporterId) }
```

> **Important**: every `after` hook must be declared before `timeout`; every `after` duration must be
> positive, unique within a state, declared in increasing order, and strictly less than the state's
> `timeout` duration; otherwise validation will fail. Each `after` id must also be unique within a
> state.

### `terminal()`

Mark this state as terminal. When the state machine enters it, the event loop exits and the
workflow completes. Terminal states should not have event handlers (any events that arrive after
the workflow completes will return errors at the signal site).

```kotlin
state(TicketState.RESOLVED) { terminal() }
```

### `immediatelyTransitionTo(target)`

After `onEntry` hooks run, transition to `target` without waiting for an event. Useful for
transient "setup" states like `PENDING → ACTIVE`.

```kotlin
state(TicketState.PENDING) {
    onEntry { input -> threadId = actions.createMessageThread(input.ticketId) }
    immediatelyTransitionTo(TicketState.AWAITING_AGENT)
}
```

The target state must be defined. Cycles between `immediatelyTransitionTo` chains are detected
by validation and rejected.

## TransitionResult

Every handler — `on<E>`, `timeout`, the guard branch — must return a `TransitionResult`. The
sealed family:

| Result | Construct with | When to use |
|--------|----------------|-------------|
| `TransitionResult.TransitionTo(newState)` | `transitionTo(newState)` | Moving to a different state |
| `TransitionResult.Stay` | `stay()` | Activity in the same state — resets timer and re-runs hooks |
| `TransitionResult.Ignore(reason)` | `ignore()` (sets reason to `EXPLICIT`) | The event is irrelevant in this state |

When the guard branch of `on<E>(guard) { ... }` rejects an event, the result is
`Ignore(GUARD_REJECTED)`. The admin view distinguishes the two reasons so operators can tell
whether an event was dropped by intent or by guard logic.

## InvalidTransitionPolicy

Controls what happens when an event arrives that has no handler in the current state. Default is
`LOG_AND_IGNORE`. Override on your state-machine subclass to opt into hard failures:

```kotlin
@SkipperOpen
open class TicketStateMachine : SkipperStateMachine<...>(...) {
    override val invalidTransitionPolicy = InvalidTransitionPolicy.THROW
    // ...
}
```

| Value | Behavior |
|-------|----------|
| `LOG_AND_IGNORE` | Log a warning, fire middleware `onInvalidTransition`, drop the event. |
| `THROW` | Throw a `NonRetryableError`, stopping the workflow permanently. |

## StateMachineEvent base class

All event types must extend `StateMachineEvent`. The base class provides class-based
`equals`/`hashCode` so Skipper's `SimplePojoSerde` round-trip validation passes for `object`
subtypes (parameterless singletons).

```kotlin
sealed class TicketEvent : StateMachineEvent() {
    object Escalate : TicketEvent()                      // class-based equals from base
    data class Resolution(val notes: String) : TicketEvent()  // data class overrides with property equals
}
```

`data class` subtypes automatically override `equals`/`hashCode` based on their properties — no
extra effort needed. If you write a non-data-class subtype with fields, you must override
`equals`/`hashCode` yourself.

### `@EventAlias("...")`

Pins the alias persisted in the event log for an event class. By default the alias is the simple
class name (stable across package moves, but not across a rename). Annotate a renamed class with its
original alias so in-flight instances keep replaying:

```kotlin
@EventAlias("ResponseCreated")        // the class's former simple name
data class ResponseSubmitted(val id: Long) : MyEvent()
```

Aliases must be unique within a state machine's event hierarchy (enforced at startup). See
[Evolution](/docs/state-machine/evolution/).

## Validation Rules

`StateMachineBuilder.validateOrThrow()` runs at workflow startup and throws
`StateMachineValidationException` listing every problem found. The rules:

1. **States must do something.** A state with no event handlers, no `timeout`, no `terminal()`,
   and no `immediatelyTransitionTo` will hang forever.
2. **`immediatelyTransitionTo` targets must exist.** The named target state must be defined.
3. **Terminal states should not have handlers.** Reported as a warning; the handlers will never
   execute because the workflow completes on terminal entry.
4. **`after` durations must be positive.** Zero and negative durations are rejected.
5. **`after` durations must be less than `timeout`.** Otherwise the after hook never fires.
6. **`after` hooks must be declared before `timeout`.** Timer definitions should read in execution
   order: reminders/escalations first, terminal backstop last.
7. **`after` durations must be unique within a state.** Two `after` hooks at the same duration
   are ambiguous and rejected.
8. **`after` hooks must be declared in increasing duration order.** This keeps fresh deadline order
   and replay declaration order aligned when several past deadlines are due at once.
9. **`after` hook ids must be unique within a state.** An explicit `after(duration, id = "…")` id
   must not collide with another explicit id or with a derived ordinal id (`#0`, `#1`, …).
10. **`immediatelyTransitionTo` chains must be acyclic.** Cycles are detected and reported once per
   cycle.

If any rule fires, the workflow throws on its first run. Fix the definition and redeploy.
