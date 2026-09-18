---
title: Workflow API
description: The core building blocks you use inside a workflow method — waiting, checkpointing, sleeping, and identity.
section: Guides
order: 10
---

These are the primitives you reach for inside a `@WorkflowMethod`.

## Waiting for data with `waitUntil`

`waitUntil` pauses a workflow until a condition becomes true — typically because a
[signal](/docs/signals-and-queries/) updated a `@StateField`. While waiting, the workflow
**consumes no resources**: it is not occupying a thread or actively running.

```kotlin
@StateField var isApproved: Boolean? = null

@WorkflowMethod
suspend fun processRequest(request: Request): String {
  // Wait up to one day for an approval signal to arrive.
  val received = waitUntil({ isApproved != null }, Duration.ofDays(1))
  if (!received || isApproved != true) return "Rejected or timed out"
  return "Approved"
}
```

```java
@StateField Boolean isApproved;

@WorkflowMethod(returnType = String.class)
public CompletableFuture<String> processRequest(Request request) {
  // Wait up to one day for an approval signal to arrive.
  boolean received = waitUntil(() -> isApproved != null, Duration.ofDays(1));
  if (!received || !Boolean.TRUE.equals(isApproved)) {
    return CompletableFuture.completedFuture("Rejected or timed out");
  }
  return CompletableFuture.completedFuture("Approved");
}
```

`waitUntil` returns `true` if the condition was met within the timeout, or `false` if it
timed out. Omit the timeout to wait indefinitely. The condition should reference
`@StateField` values.

## Checkpointing with `checkpoint`

`checkpoint` runs a block **once** and persists its effect, so it is not repeated when the
workflow resumes. Use it for expensive computations, one-time state mutations, and any
non-deterministic code that isn't a natural action.

```kotlin
@StateField var total: Long = 0

@WorkflowMethod
suspend fun process(): Long {
  checkpoint { total = expensiveCalculation() } // runs once
  waitUntil({ ready }, Duration.ofHours(1))
  return total
}
```

```java
@StateField long total = 0;

@WorkflowMethod(returnType = Long.class)
public CompletableFuture<Long> process() {
  checkpoint(() -> { total = expensiveCalculation(); }); // runs once
  waitUntil(() -> ready, Duration.ofHours(1));
  return CompletableFuture.completedFuture(total);
}
```

`checkpoint` is also the correct way to mutate a `@StateField` from workflow code when a
**signal** may mutate the same field — without it, the two writers can race in surprising
ways.

## Sleeping

To pause for a fixed duration, use `sleep`:

```kotlin
sleep(Duration.ofMinutes(30))
```

```java
sleep(Duration.ofMinutes(30));
```

It is shorthand for `waitUntil({ false }, duration)`.

## Workflow identity

The unique id you started the workflow with is available as `this.id` inside any workflow
method.

```kotlin
val workflowId = this.id
```

```java
String workflowId = this.id;
```

## Best practices

- Use `@StateField` for data that must survive across waits.
- Use `checkpoint` for expensive or non-deterministic one-time work, and for state shared
  with signals.
- Combine `waitUntil` with [signals](/docs/signals-and-queries/) to bring in external data.
- Keep timeouts meaningful — they are your safety net against waiting forever.
- Never wrap `waitUntil` or action calls in a blanket `catch` (`Throwable`, `Exception`,
  `Error`). `waitUntil` suspends the workflow by throwing, and action failures propagate as
  exceptions Skipper needs to see. See
  [Error Handling](/docs/error-handling/#dont-catch-blanket-exceptions-in-workflow-code).
