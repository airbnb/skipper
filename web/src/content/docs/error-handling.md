---
title: Error Handling & Retries
description: How Skipper classifies failures, retries them, and what happens when retries run out.
section: Guides
order: 12
---

Skipper's job is to drive workflows to a terminal state in spite of failures. Understanding
how it classifies and retries errors lets you write workflows that recover the way you want.

## Retryable vs. non-retryable

When an action throws, Skipper classifies the exception as **retryable** or
**non-retryable**. By default, exceptions are treated as non-retryable, except for a small
set of errors known to be transient.

To make a specific failure retryable, wrap it explicitly:

```kotlin
@Execute
suspend fun callService(req: Request) {
  try {
    client.call(req)
  } catch (e: TransientException) {
    throw RetryableError("Service temporarily unavailable", e)
  }
}
```

```java
@Execute
public void callService(Request req) {
  try {
    client.call(req);
  } catch (TransientException e) {
    throw new RetryableError("Service temporarily unavailable", e);
  }
}
```

> Skipper converts non-`SkipperError` exceptions into a serializable `ApplicationError` so
> they can be persisted. The original message and stack trace are kept, but the exception
> **type is not** — so don't try to `catch` a specific action exception type in workflow
> code.

If you find yourself wrapping the same exceptions repeatedly, register a custom
`ExceptionClassifier` instead (next section).

## Classifying exceptions with `ExceptionClassifier`

Wrapping in `RetryableError` works case by case. To teach Skipper which of your exceptions are
transient once and for all, provide an `ExceptionClassifier` — a single-method interface:

```kotlin
interface ExceptionClassifier {
  fun isRetryable(throwable: Throwable): Boolean
}
```

The built-in `DefaultExceptionClassifier` unwraps `ExecutionException` / `CompletionException`
to inspect the underlying cause, treats `TimeoutException` as **retryable**, and classifies
everything else as **non-retryable**.

### Writing a custom classifier

Extend `DefaultExceptionClassifier` and fall back to `super` so the default unwrapping and
timeout handling still apply — only add the cases you care about:

```kotlin
class CustomExceptionClassifier : DefaultExceptionClassifier() {
  override fun isRetryable(throwable: Throwable): Boolean =
    throwable is TransientException || super.isRetryable(throwable)
}
```

```java
public class CustomExceptionClassifier extends DefaultExceptionClassifier {
  @Override
  public boolean isRetryable(Throwable throwable) {
    return throwable instanceof TransientException || super.isRetryable(throwable);
  }
}
```

### Registering it

Set it **globally** for all actions on the config, before creating the runtime:

```kotlin
config.exceptionClassifier = ComponentFactory { CustomExceptionClassifier() }
```

```java
config.setExceptionClassifier(cfg -> new CustomExceptionClassifier());
```

Or scope one to a **single action** by naming an `ExceptionClassifier` field through the
`@Execute` annotation (Skipper resolves the field on the action class):

```kotlin
class PaymentActions : Actions() {
  val paymentClassifier = CustomExceptionClassifier()

  @Execute(exceptionClassifier = "paymentClassifier")
  suspend fun charge(req: ChargeRequest) { /* ... */ }
}
```

```java
public class PaymentActions extends Actions {
  ExceptionClassifier paymentClassifier = new CustomExceptionClassifier();

  @Execute(exceptionClassifier = "paymentClassifier")
  public void charge(ChargeRequest req) { /* ... */ }
}
```

A per-action classifier overrides the global one for that action.

## Retry strategies

A non-retryable error moves the workflow to `ERROR`. A retryable error is retried according
to a strategy. Set a default for all actions on the config:

```kotlin
config.defaultRetryStrategy = FixedRetryStrategy(Duration.ofSeconds(5), 3)
```

```java
config.setDefaultRetryStrategy(new FixedRetryStrategy(Duration.ofSeconds(5), 3));
```

Or override per action by naming a `RetryStrategy` field through the `@Execute` annotation:

```kotlin
class MyActions : Actions() {
  val backoff = ExponentialRetryStrategy(
    Duration.ofSeconds(1), 10, 2.0, Duration.ofHours(1),
  )

  @Execute(retryStrategy = "backoff")
  suspend fun flakyCall() { /* ... */ }
}
```

```java
public class MyActions extends Actions {
  RetryStrategy backoff = new ExponentialRetryStrategy(
      Duration.ofSeconds(1), 10, 2.0, Duration.ofHours(1));

  @Execute(retryStrategy = "backoff")
  public void flakyCall() { /* ... */ }
}
```

### Persistent retries

By default, once retries are exhausted the error becomes non-retryable and the workflow
fails. If instead you want to keep the workflow alive so it can be retried **manually**
later, wrap the strategy in a `PersistentRetryStrategy`:

```kotlin
val strategy = PersistentRetryStrategy.of(FixedRetryStrategy(Duration.ofSeconds(5), 3))
```

```java
RetryStrategy strategy = PersistentRetryStrategy.of(new FixedRetryStrategy(Duration.ofSeconds(5), 3));
```

The workflow then enters `RETRIES_EXHAUSTED` and waits. Handle it inline or via a callback:

```kotlin
override fun onRetriesExhausted(workflow: WorkflowInstanceView, error: Throwable) {
  // Inspect, fix the underlying issue, then re-execute later.
}
```

```java
@Override
public void onRetriesExhausted(WorkflowInstanceView workflow, Throwable error) {
  // Inspect, fix the underlying issue, then re-execute later.
}
```

See **[Instance Management](/docs/instance-management/)** for re-executing these workflows.

## Don't catch blanket exceptions in workflow code

Never catch `Throwable`, `Exception`, `RuntimeException`, or `Error` inside a
`@WorkflowMethod` (or any code it calls directly). Skipper drives execution through
exceptions, and a broad `catch` intercepts them:

- `waitUntil` suspends a workflow by throwing an internal control-flow signal when its
  condition is not yet met. Catching it makes the workflow continue as if the wait had been
  satisfied.
- A failing action surfaces as a `RetryableError` or non-retryable error. Catching it stops
  Skipper from retrying, moving the workflow to `ERROR`, or running compensation, so the
  workflow proceeds down a path it was never meant to take.

```kotlin
// Don't do this
@WorkflowMethod
suspend fun run() {
  try {
    waitUntil { isApproved == true }
    ledger.charge(order)
  } catch (e: Exception) {   // swallows the wait signal and action errors
    log.warn("something went wrong", e)
  }
}
```

```java
// Don't do this
@WorkflowMethod
public void run() {
  try {
    waitUntil(() -> Boolean.TRUE.equals(isApproved));
    ledger.charge(order);
  } catch (Exception e) {   // swallows the wait signal and action errors
    log.warn("something went wrong", e);
  }
}
```

If part of a workflow needs its own error handling, do it **inside the action**, where you
can catch the concrete exception type and decide whether to rethrow it as a
`RetryableError`. Let anything that escapes an action propagate out of the workflow
method so Skipper can handle it. The same rule applies to helpers that catch broadly on
your behalf, such as `runCatching` in Kotlin or a wrapper that logs and swallows exceptions.

## Workflow-level and unexpected failures

- Exceptions thrown by **workflow code** (not actions) are not run through the classifier —
  unless wrapped in `RetryableError`, they are treated as non-retryable.
- **Unexpected** failures (a pod crash, a storage hiccup) are retried automatically by the
  engine. If no progress can be made after many attempts, the workflow lands in a
  **dead-letter queue** for manual inspection and re-drive.
