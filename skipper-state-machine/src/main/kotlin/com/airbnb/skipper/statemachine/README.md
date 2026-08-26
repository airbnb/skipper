# Skipper State Machine

Declarative `Kotlin` DSL for durable state machines on Skipper. Subclass `SkipperStateMachine<StateT, EventT, InputT>`, implement `define()`, and the base class handles replay, checkpointing, compact serde, and admin visualization.

This module lives in `skipper-state-machine/` and exposes APIs under `com.airbnb.skipper.statemachine`.

## Persistence Model

The state machine stores workflow-visible data in a compact `@StateField` blob encoded with `Smile` binary plus `Zstd` compression. The blob uses dictionaries for repeated state/event names and nano timestamps for compact precision.

Admin history is also compact. Transition, timeout, after-hook, lifecycle-progress, and timestamp-marker records are written as small `Smile` + `Zstd` journal segments wrapped in serializable Skipper action checkpoint results. The workflow rebuilds replay-derived histories from those durable segments on each execution instead of relying on checkpoint lambdas that mutate `@StateField` history. This keeps retry timelines stable and avoids duplicating large event payloads.

Do not replace this with generic `JSON`/object serialization without measuring size. State machine payloads must stay within reasonable storage limits; large workflows can approach hundreds of KB quickly otherwise.

User actions can use either `Skipper` checkpoint mode. Use `IMMEDIATE_CHECKPOINT` for side effects that need stronger idempotency across workflow update failures. The framework uses `IMMEDIATE_CHECKPOINT` for transition starts, transition progress markers, transition completion, timeout firings, handler-start timestamp markers, after-hook start markers, and middleware action records. These records are framework-owned metadata/callbacks, not product side effects. They preserve the first-attempt start timestamp when an event handler, timeout handler, middleware callback, `onExit`, `onEntry`, `after`, or terminal callback fails and retries. After-hook completion records and ignored-transition records still use `EVENTUAL_CHECKPOINT`; those commit with workflow state after the visible start marker is already durable. Hook and middleware spans remain the measured execution spans: they do not include time spent waiting for later events or retry scheduling.

The admin timeline and sequence diagram render middleware from transition-log spans, gated by the presence of middleware action checkpoints, rather than positioning middleware directly from raw action-history timestamps. This keeps middleware visually scoped to the transition that executed it. The Action History table still shows the raw checkpoint rows for debugging Skipper persistence behavior; those middleware rows are immediate checkpoints, so they should line up with the same execution attempt as the transition.

### Retry and Admin Timeline Behavior

If an `onEntry` hook runs several `Action`s and a later `Action` fails retryably, `Skipper` normally persists earlier successful `EVENTUAL_CHECKPOINT` results when it updates the workflow to `TRANSIENT_ERROR`. Those successful `Action`s are then replayed from checkpoints and do not re-execute; the failed transient `Action` retries. If the process crashes or the workflow update fails before dirty checkpoints are flushed, earlier eventual `Action`s can execute again, so they still need to be idempotent.

Mixing `EVENTUAL_CHECKPOINT` and `IMMEDIATE_CHECKPOINT` `Action`s is supported. Immediate `Action` checkpoints can become durable before the state entry completes. On retry, the admin timeline uses immediate transition and phase-start journals as the durable envelope, then merges completion back into those same records. Successful checkpoints from earlier attempts remain scoped under the same transition/hook span instead of being rendered later in the timeline. If the process crashes or Skipper cannot flush dirty eventual checkpoints after transition completion, eventual user actions can run again on retry; those actions must still be idempotent or use `IMMEDIATE_CHECKPOINT`.

Internally, the public `SkipperStateMachine` facade delegates replay and persistence to small package-private collaborators:

| Class | Responsibility |
|-------|----------------|
| `StateMachineEventLoop` | Deterministic replay from the initial state through the durable event log. |
| `StateMachineTransitionExecutor` | One transition attempt: handler span, middleware, hooks, and transition progress records. |
| `StateMachineJournal` | Compact journal segment encoding, checkpoint-mode choice, and replay-safe upserts. |
| `StateMachineRuntimeStore` | Decode/cache/persist for the compact `@StateField` blob. |
| `StateMachineStateCodec` | Pure Smile + Zstd snapshot/journal format and materialization helpers. |

## Quick Start

```kotlin
// 1. State enum, event sealed class, input
enum class OrderState { PENDING, ACTIVE, FULFILLED, EXPIRED }

sealed class OrderEvent : StateMachineEvent() {
    object Activate : OrderEvent()
    data class Complete(val reason: String) : OrderEvent()
}

data class OrderInput(val orderId: Long)

// 2. Actions — all side effects go here (checkpointed by Skipper, safe on replay)
@SkipperOpen
open class OrderActions : Actions() {
    @Inject private lateinit var dao: OrderDao
    @Execute open suspend fun updateStatus(id: Long, status: String) { dao.update(id, status) }
}

// 3. State machine
@SkipperOpen
open class OrderStateMachine : SkipperStateMachine<OrderState, OrderEvent, OrderInput>(OrderState.PENDING) {
    private val actions = actions<OrderActions>()

    override fun StateMachineBuilder<OrderState, OrderEvent, OrderInput>.define() {
        state(OrderState.PENDING) { handlePending() }
        state(OrderState.ACTIVE) { handleActive() }
        state(OrderState.FULFILLED) { terminal() }
        state(OrderState.EXPIRED) { terminal() }
        middleware<OrderMetricsMiddleware>()
    }

    private fun StateBuilder<OrderState, OrderEvent, OrderInput>.handlePending() {
        on<OrderEvent.Activate> { _, input ->
            actions.updateStatus(input.orderId, "ACTIVE")
            transitionTo(OrderState.ACTIVE)
        }
        after(Duration.ofDays(5)) { actions.sendReminderNotification() }
        timeout(Duration.ofDays(7)) { transitionTo(OrderState.EXPIRED) }
    }

    private fun StateBuilder<OrderState, OrderEvent, OrderInput>.handleActive() {
        on<OrderEvent.Complete> { _, input ->
            actions.updateStatus(input.orderId, "FULFILLED")
            transitionTo(OrderState.FULFILLED)
        }
    }
}
```

## `stay()` vs `ignore()` vs `transitionTo()`

This is the most important thing to understand:

| Result | onExit/onEntry | Middleware | Timeout Reset | Use when... |
|--------|---------------|------------|---------------|-------------|
| `transitionTo(S)` | Yes | Yes | Yes | Moving to a different state |
| `stay()` | Yes (re-enter same) | Yes | Yes | Something happened, still in same state (e.g., activity resets inactivity timer) |
| `ignore()` | No | No | No | Event is irrelevant, pretend nothing happened |

`stay()` is a **full transition back to self**. `ignore()` is a **silent no-op**.

## `runAsync()` vs Default (Synchronous)

When starting a workflow via `workflowFactory.builder(...)`:

- **`.runAsync().build()`** — Workflow is enqueued in the DB scheduler only. Picked up by the scheduler poller (~1s latency). Use when **callers don't need to poll for immediate state changes**.
- **`.build()` (default)** — Workflow runs via the in-memory queue with DB backup. Picked up in milliseconds. Use when **callers poll for state transitions** (e.g., API must return a field populated by an onEntry side effect).

## `@StateField` vs Local `var` — Replay Safety

**Critical rule**: Never use `@StateField` for variables that drive transition decisions (counters, accumulators, flags). Use **local `var`** fields on the class instead — they're rebuilt deterministically from the event sequence on every replay.

```kotlin
// BAD — @StateField counter can diverge under replay edge cases
@StateField var activeCount = 0

// GOOD — local var, rebuilt from events on every replay
var activeCount = 0  // NOT @StateField
```

Use `@StateField` only for **data you need to query externally** (via `@QueryMethod`) or **data populated by Actions** that you need to persist.

## `onEntry`/`onExit` Are Not Checkpointed

Hooks re-execute on replay. For durable side effects, always call Actions inside hooks:

```kotlin
state(ACTIVE) {
    onEntry { input ->
        // BAD: dao.update(...) — will re-execute on replay
        // GOOD: goes through checkpointed action
        actions.updateStatus(input.id, "ACTIVE")
    }
}
```

## DSL Reference

**Top-level** (`StateMachineBuilder`): `state(S) {}`, `middleware<M>()`, `universalMiddleware<M>()`

**Per-state** (`StateBuilder`):

| Method | Description |
|--------|-------------|
| `on<E> { event, input -> ... }` | Handle event E → return `transitionTo(S)`, `stay()`, or `ignore()` |
| `on<E>(guard) { event, input -> ... }` | Handle E with guard; false → `ignore()` |
| `onEntry { input -> ... }` | Hook on entering state (not checkpointed) |
| `onExit { input -> ... }` | Hook on exiting state (not checkpointed) |
| `timeout(duration) { input -> ... }` | Timeout handler → return `TransitionResult` |
| `after(duration) { input -> ... }` | Side-effect action at duration (no transition). Resets on `stay()`. |
| `terminal()` | Mark as terminal (workflow completes) |
| `immediatelyTransitionTo(S)` | Auto-transition after onEntry, no event wait |

## Things That Will Bite You

1. **Missing `@SkipperOpen` / `open`** on SM, Actions, or Middleware classes → runtime proxy errors.

2. **Calling DB/RPC directly from handlers** instead of through `@Execute` Actions → duplicate calls on replay.

3. **`@StateField` with generic types** (e.g., `Map<K, V>`) → Skipper serde rejects them. Wrap in a non-generic data class with `@JsonTypeInfo(CLASS, WRAPPER_ARRAY)` or use a compact binary blob like `SkipperStateMachine.PersistedStateBlob`.

4. **Non-idempotent `@StateField` mutations** → use `putIfAbsent` for map creation. Handlers re-run on replay.

5. **Cross-workflow signals without try-catch** → target may have completed. Always catch `IllegalStateException` / `IllegalArgumentException`.

6. **`immediatelyTransitionTo` cycles** → validator catches them, but they infinite-loop if missed.

7. **Confusing `stay()` and `ignore()`** → `stay()` resets the timeout and fires hooks. If that's not what you want, use `ignore()`.
