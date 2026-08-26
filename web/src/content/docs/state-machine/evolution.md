---
title: Evolution
description: Safely change a state machine that already has live, in-flight instances — what is safe, what needs care, and what is breaking.
section: State Machine
order: 46
---

This page is about changing a state machine **that already has live in-flight instances**. A
state machine is a durable Skipper workflow: instances can live for days or weeks, and the event
loop replays each one from its initial state on every execution. So an edit you make today runs
against instances that were *started by yesterday's code*. Some edits are safe; a few will corrupt
or wedge in-flight instances if you make them naively.

The guiding design principle: **the DSL stays simple, and is smart internally.** It derives stable
identities for your states, timers, and events from what you already wrote — you do not annotate or
name anything for the common cases. Where an edit is genuinely ambiguous (a rename, or "new
instances should behave differently from in-flight ones"), there is a small, explicit escape hatch.

If you have not read [Persistence & Replay](/docs/state-machine/persistence/), read it first — this
page assumes you know that handler bodies re-run on every replay and only Action/`checkpoint{}`
calls inside them are durable.

## TL;DR — what you can change freely

- **Add a state.** Safe. It is inert until something transitions into it.
- **Add / remove / reorder `on<E>` handlers** for *distinct* event types. Safe — dispatch is by
  event class, not position.
- **Append an `after()` hook after the existing `after()` hooks and before `timeout()`, remove the
  last `after()` hook, or change an `after()`/`timeout()` duration without changing relative timer
  order.** Safe — firing identity is the hook's declaration ordinal or explicit id, and validation
  requires `after()` declarations to stay before `timeout()` and in increasing duration order. See
  [Changing a timer duration](#changing-a-timer-duration-the-1d7d-case).
- **Edit a hook body** — change the orchestration Kotlin freely. Keep it deterministic, and name
  Action calls you expect to add/remove/reorder later (see [Editing a hook body](#editing-a-hook-body)).
- **Add a nullable / primitive `@StateField`; remove any `@StateField`.** Safe — Skipper's lenient
  deserialization handles both.

## What needs care or an explicit choice

- **Behavior that must differ for new vs. in-flight instances** → gate it with
  [`version()`](#branching-behavior-across-deploys-version).
- **The *shape* of `define()` differing for new vs. in-flight instances** → branch on an immutable
  [`Input`](#branching-shape-across-deploys-input) field.
- **Renaming a state or event** → add a [migration entry](#renaming-a-state-or-an-event); a rename
  is otherwise breaking.

## What is still irreducibly breaking

These have no safe in-place edit. Use the copy/migrate fallback: introduce a new machine class, route
**new** instances to it behind a feature flag, let in-flight instances drain on the old class, then
retire it.

- **Removing a state that in-flight instances occupy or auto-transition into.** The loop reconstructs
  that state on replay and fails with "No definition for state …". You cannot define behavior for a
  state you deleted.
- **Changing a `@StateField`'s type** (e.g. `String` → `Long`). Deserialization fails. Soften with a
  dual-field migration (add a new nullable field, dual-read, drain, drop the old).
- **Changing the workflow `Input` or return type, or renaming the machine class / `execute`.** These
  are Skipper-level breaks the DSL inherits.

## Breaking-changes matrix

| Change | Safe? | What you do |
|---|---|---|
| Add a `state(NEW){}` | ✅ | Nothing. Gate any `transitionTo(NEW)` you add to *existing* handlers with `version()` if in-flight instances should not route there. |
| Reorder `on<E>` handlers (distinct event types) | ✅ | Nothing. |
| Add `on<E>` for a type already present in live event logs | ⚠️ | Replayed historical events of that type would newly be handled — gate the effect with `version()`. |
| Remove `on<E>` whose type is in live logs | ⚠️ | Neuter behind `version()`, drain, then delete. |
| Append or remove an `after()` at the end of a live state's hook list | ✅ | Put appended hooks after existing `after()` hooks and before `timeout()`. Keep the appended hook's duration greater than the previous `after()` duration and less than `timeout`. Firing identity is the hook's declaration ordinal unless an explicit id is provided. |
| Change an `after()`/`timeout()` duration without changing relative timer order | ✅ | Nothing. Not-yet-fired hooks fire on the recomputed deadline; already-fired hooks do not re-fire. |
| Change an `after()` duration so declaration order is no longer increasing by duration | ❌ | Validation rejects the definition. Named journal checkpoints keep framework records stable but do not bypass the DSL ordering contract. Drive the duration from `Input`, split behavior with `version()`, or add a new hook in the correct position and drain old instances first. See [Changing a timer duration](#changing-a-timer-duration-the-1d7d-case). |
| Insert an `after()` *before* an existing one, or reorder declarations, on a live state | ⚠️ | Keep hooks before `timeout()` and in increasing duration order. Explicit ids can preserve hook identity and framework journal identity, but Action checkpoints inside affected hook bodies are still positional unless those Action calls are named. Prefer appending, `version()`/`Input`, or draining old instances. |
| Edit a hook body — orchestration Kotlin only | ✅ | Keep it deterministic & replay-idempotent. |
| Edit a hook body — add/remove/reorder Action calls | ✅* | Name the Action calls with `actions.named("…")` so identity survives the edit. |
| Add nullable / primitive `@StateField` | ✅ | Nothing. |
| Remove a `@StateField` | ✅ | Nothing. |
| Add a non-null reference `@StateField` | ⚠️ | Make it nullable or lazy-init — the initializer is lost on deserialization for in-flight instances. |
| Change a `@StateField`'s type | ❌ | Dual-field migration. |
| Add a new event type to the sealed hierarchy | ✅ | Nothing (it is simply absent from old logs). |
| Rename a state enum constant | ✅† | Add a `stateNameMigrations` entry. |
| Rename an event class | ✅† | Add `@EventAlias` / an `eventAliasMigrations` entry. |
| Move an event class to another package | ✅ | Nothing (alias is the simple class name). |
| Remove a state an instance occupies | ❌ | Copy/migrate. |
| Behavior differs new-vs-in-flight | ✅ | `version()` in a handler body. |
| `define()` *shape* differs new-vs-in-flight | ✅ | Branch on an immutable `Input` field. |

\* requires named Action calls (see [Editing a hook body](#editing-a-hook-body)).
† requires the rename-migration entry (see [Renaming a state or an event](#renaming-a-state-or-an-event)).

## Changing a timer duration (the 1d→7d case)

Say a state has `after(Duration.ofDays(1)) { … sendReminder() }` and you change it to
`Duration.ofDays(7)`. There are two cohorts of live instances:

- **Already fired** (in the state more than a day): the reminder already went out.
- **Not yet fired** (in the state less than a day).

The DSL identifies each after-hook by its **declaration ordinal within the state** unless you provide
an explicit id. Fresh execution wakes hooks by deadline, while replay can see several past deadlines
at once and chooses among them by declaration ordinal. For those orders to match, validation requires
`after()` declarations to be in strictly increasing duration order.

Whether a duration change is in-flight-safe depends on whether it preserves that order:

- **Changing a duration without crossing another hook:** safe. Already-fired instances do not re-fire
  (the firing is suppressed by its hook id); not-yet-fired instances fire once at the recomputed
  deadline.
- **Changing a duration so the declaration order is no longer increasing:** rejected by validation.
  Named journal checkpoints keep the framework/admin records stable, but they do not bypass this
  startup validation. Do not fix the validation failure by simply reordering existing hooks in a live
  state: explicit ids can preserve hook identity, but Action checkpoints inside hook bodies are still
  positional unless those Action calls are named. Treat this as a behavioral change and gate it
  (below), drive the duration from `Input`, or add a new hook and let old instances drain.

What you **cannot** express by editing the duration: "re-fire for instances that already fired." A
duration edit never resurrects an already-fired hook; if you need a fresh firing, add a *new* hook
(it gets a new ordinal).

To make any duration change a clean **cohort split** — in-flight instances keep the old schedule,
new instances get the new one, nobody double-fires or mis-replays — treat it as a behavioral change:
drive the duration from an immutable `Input` field (durations are a build-time argument, so you
cannot gate the literal inside the body), or gate the surrounding behavior with `version()`:

```kotlin
data class Input(val ticketId: Long, val reporterId: Long, val reminderDays: Long = 1)
// new starters pass reminderDays = 7; in-flight instances keep the value they were started with
state(OPEN) {
    after(Duration.ofDays(input.reminderDays)) { input -> actions.sendReminder(input.reporterId) }
}
```

(An `after()` duration is read once at build time, so an input-derived `after` like the above is
fixed for the instance's life. A `timeout()` can also take an input-computed duration that is
re-evaluated per entry — see below.)

## Timeouts whose duration varies per entry (`timeout({ input -> … })`)

A fixed `timeout(Duration)` captures its duration once when the machine is built and reuses it for
every entry into that state. For a looping state whose wait differs per iteration — e.g. a single
`EXECUTE_STEP` state that runs a DB-defined sequence with a different delay before each step — use the
input-computed overload `timeout({ input -> … })`. It is re-evaluated each time the event loop
computes the state's deadline, so each re-entry can use a different duration:

```kotlin
data class Input(val steps: List<StepDef>)   // pinned at enrollment

state(WorkflowState.EXECUTE_STEP) {
    timeout({ input -> input.steps[currentStepIndex].waitDuration }) { input ->
        runStep(input.steps[currentStepIndex])
        if (currentStepIndex >= input.steps.lastIndex) transitionTo(WorkflowState.DONE) else stay()
    }
    onExit { currentStepIndex++ }   // local var rebuilt from the event log
}
```

This is replay-safe **only** because the deadline is `state-entry-time + durationFn(input)`, the entry
time is durable, and `durationFn` reads only the immutable input and the replay-deterministic
`currentStepIndex`. The lambda must not read the clock, an Action result, or external mutable state —
a non-deterministic duration would make the timer fire at a different point on replay. See the
[DSL Reference](/docs/state-machine/dsl-reference/) for the full contract and limitations (notably:
the `after < timeout` check and the admin pending-deadline view can't see a dynamic duration without
the input).

## Branching behavior across deploys (`version()`)

`version(changeId, minVersion, maxVersion)` (from Skipper's `Workflow`) returns the active version
for a logical change. On an instance's **first execution** it returns `maxVersion` and persists it;
on **replay** it returns the persisted value — so an instance keeps the behavior it was created with,
even after you bump `maxVersion` in a later deploy.

**Call `version()` inside a handler/hook body** (`on`, `after`, `timeout`, `onEntry`, `onExit`).
Those bodies run only while the workflow is executing, which is where version gates work. **Do not
call `version()` in the structural body of `define()`** — `define()` also runs in query contexts
(`getAdminSnapshot`, `isTerminal`) with no checkpoint engine.

```kotlin
timeout(INACTIVITY_TIMEOUT) {
    // In-flight instances persisted version 1 and keep going to EXPIRED;
    // instances created after this deploy persist 2 and go to STALE.
    if (version("inactivity-target-v2", minVersion = 1, maxVersion = 2) >= 2) {
        transitionTo(STALE)   // STALE must be added unconditionally; only new instances reach it
    } else {
        transitionTo(EXPIRED)
    }
}
```

Lifecycle (mirrors the [Skipper versioning guide](/docs/versioning/)):
deploy 1 introduces the gate at `maxVersion = 1`; deploy 2 bumps to `2` and adds the new branch;
once all v1 instances drain, raise `minVersion` to `2` and delete the old branch.

### Guards can use `version()` too — to reject events by version

The guarded form `on<E>(guard = …, handler = …)` runs its guard inside the event loop, so the guard
can call `version()`. This is a clean way to make an event apply only to instances of a new version —
in-flight instances simply ignore it:

```kotlin
on<NewKindOfEvent>(
    // Old-version instances reject this event (it is ignored with GUARD_REJECTED);
    // only instances created after the gate bump act on it.
    guard = { _, _ -> version("handle-new-event", minVersion = 1, maxVersion = 2) >= 2 },
    handler = { event, input ->
        actions.handleNewThing(input.id)
        transitionTo(NEXT)
    },
)
```

`version()` is idempotent by `changeId` — it is the named checkpoint `"version:{changeId}"`, written
once and replayed thereafter — so calling it from a guard that fires on many events is safe and
stable.

## Branching shape across deploys (`Input`)

To change the *set* of states or hooks you declare for new instances only, branch `define()` on an
immutable `Input` field. Because the input is fixed for an instance's life and identical on every
replay, this is replay-stable with no checkpoint:

```kotlin
data class Input(val ticketId: Long, val schemaVersion: Int = 1)

override fun StateMachineBuilder<S, E, Input>.define() {
    state(OPEN) { handleOpen() }
    if (input.schemaVersion >= 2) state(REVIEW) { handleReview() }   // only new instances
    state(DONE) { terminal() }
}
```

New starters pass `schemaVersion = 2`; in-flight instances were started with `1` and keep the old
shape. Always ship the new states/hooks *before* you start flipping new instances to the new value,
so any code an in-flight replay might touch already exists.

## Editing a hook body

A hook body re-runs on every replay; only the Action and `checkpoint{}` calls inside it are durable.
By default those calls are matched **positionally** by Skipper, so adding, removing, or reordering a
call to a method already used in the same workflow can shift identities and break replay for
in-flight instances.

To edit hook bodies freely, **name the Action calls** you expect to change:

```kotlin
onEntry {
    messageThreadId = actions.named("create-thread").createMessageThread(...)
    actions.named("send-card").sendInquiryCard(...)
    actions.named("create-lead").createLead(...)
}
```

Named checkpoints are matched by name, not position, so calls that were already named before live
instances reached them can later be inserted, removed, or reordered without disturbing those
instances. Adding `actions.named("…")` to an existing positional call is **not** an in-place
migration for instances that already checkpointed it: the named call has a new identity and will not
match the old positional checkpoint. Introduce names before instances depend on the calls, split
new behavior with `Input`/`version()`, or wait for positional instances to drain.

## Renaming a state or an event

A rename changes the identity the DSL persisted, so an in-flight instance's stored name no longer
resolves. Make a rename non-breaking by registering the old name:

**State rename** — supply the old → new mapping on your state machine:

```kotlin
override val stateNameMigrations = mapOf("OPEN" to "ACCEPTING")
```

**Event rename** — annotate the renamed class so it keeps its persisted alias:

```kotlin
@EventAlias("ResponseCreated")   // was the class's old simple name
data class ResponseSubmitted(...) : MyEvent()
```

An alias, once any instance has persisted it, is permanent — treat it as a wire contract. Without a
migration entry, a rename is breaking and needs the copy/migrate fallback.

Renames are reflected throughout the admin snapshot — `currentState`, `stateHistory`, and
`transitionHistory` (each row's `fromState`/`toState`, and an event-triggered row's `triggerName`),
as well as `eventHistory`'s `eventType` — all show the current name/alias, so the REST views and
Mermaid diagrams never mix old and new identities.

## How this maps to Skipper

Under the hood the DSL reuses Skipper's versioning primitives so you do not have to:

- **Named checkpoints** back the per-state timer/after-hook identities and the named Action calls.
- **Version gates** (`Workflow.version`) are exposed directly for behavioral branching.
- **Lenient `@StateField` deserialization** is what makes adding/removing your subclass `@StateField`s
  safe.

For format-level evolution (changing the compact blob the framework persists), see
[Persistence & Replay](/docs/state-machine/persistence/).
