---
title: Versioning & Evolution
description: Change running workflows safely — which changes are breaking, and the primitives that make most of them safe.
section: Guides
order: 14
---

A workflow that may be in-flight for days or weeks will outlive many deploys. This guide
explains which code changes are safe for in-flight instances and the primitives that make
most structural changes non-breaking.

## Why some changes are breaking

To resume a workflow, Skipper matches each step in your code against the record of steps it
already ran. By default that matching is **positional**: each action call is identified by
its class, method, and an iteration counter. Insert, remove, or reorder calls and the
positions shift, so saved results map to the wrong steps. Two opt-in primitives remove this
fragility.

## Named checkpoints

Give an action invocation a stable name and Skipper matches by name instead of position —
so reordering or adding calls no longer breaks in-flight instances.

```kotlin
@WorkflowMethod
suspend fun processBooking(input: BookingInput) {
  bookingActions.named("prepare-order").prepareOrder(input)
  bookingActions.named("initiate-payment").initiatePayment(input)
}
```

```java
@WorkflowMethod
public CompletableFuture<Void> processBooking(BookingInput input) {
  bookingActions.named(BookingActions.class, "prepare-order").prepareOrder(input);
  bookingActions.named(BookingActions.class, "initiate-payment").initiatePayment(input);
  return CompletableFuture.completedFuture(null);
}
```

Names are unique within a workflow method (duplicates fail fast). Names can include runtime
values, which is useful in loops:

```kotlin
items.forEachIndexed { i, item ->
  actions.named("process-item-$i").process(item)
}
```

```java
for (int i = 0; i < items.size(); i++) {
  actions.named(ItemActions.class, "process-item-" + i).process(items.get(i));
}
```

`checkpoint` and `waitUntil` have named overloads too — `checkpoint(name) { ... }` and
`waitUntil(condition, timeout, timerId)` — which is the recommended pattern for
evolution-safe workflows.

## Version gates

To branch logic for new instances while leaving in-flight ones on the old path, use
`Workflow.version()` instead of copying the whole method:

```kotlin
// Introduced earlier at maxVersion = 1 so in-flight instances persist a baseline,
// then bumped to maxVersion = 2 alongside the new branch.
if (version("add-fraud-check", minVersion = 1, maxVersion = 2) >= 2) {
  fraud.check(order)   // new instances only; in-flight instances replay version 1 and skip it
}
```

```java
// Introduced earlier at maxVersion = 1 so in-flight instances persist a baseline,
// then bumped to maxVersion = 2 alongside the new branch.
if (version("add-fraud-check", 1, 2) >= 2) {
  fraud.check(order);  // new instances only; in-flight instances replay version 1 and skip it
}
```

`version(changeId, minVersion, maxVersion)` returns `maxVersion` on a workflow's first
execution (and persists it), and the persisted value on every subsequent resume.

## Evolving state fields

- **Adding** a nullable or primitive `@StateField` is safe — old state deserializes and the
  new field gets `null` / a default.
- **Removing** a `@StateField` is safe — the unknown value is ignored.
- **Changing the type** of a `@StateField` is **breaking** (deserialization fails).
- Adding a **non-null reference** field with no default-handling path will throw on first
  access for in-flight instances — provide a fallback.

## Breaking changes at a glance

| Change | Positional (default) | With named primitives |
|---|---|---|
| Add an action with a distinct class/method | Safe | Safe |
| Reorder calls to the **same** action | Breaking | Non-breaking (named) |
| Add / remove / reorder a `checkpoint` | Breaking | Non-breaking (named) |
| Add or move a `waitUntil` | Breaking | Non-breaking (`timerId`) |
| Branch logic for new vs. in-flight | Copy the method | Use `version()` |
| Change an action's return type | Breaking | Breaking |
| Rename the workflow class, package, or method | Breaking | Breaking |

When in doubt, prefer named checkpoints and version gates from the start — they cost
nothing and keep your options open.
