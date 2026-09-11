---
title: Invoking Workflows
description: Start workflows with the factory and the invocation builder, and receive async results through callback handlers.
section: Guides
order: 9
---

[Your First Workflow](/docs/first-workflow/) showed the simplest way to start a workflow.
This guide covers the full invocation surface: the **invocation builder** and its options,
and **callback handlers** for getting results asynchronously from long-running workflows.

## The workflow factory

Everything starts from an `IWorkflowFactory`, which you obtain from your `SkipperRuntime`
(see the [Quickstart](/docs/quickstart/)):

```kotlin
val factory: IWorkflowFactory = runtime.workflowFactory.get()
```

```java
IWorkflowFactory factory = runtime.getWorkflowFactory().get();
```

## Quick invocation

For a simple call, get a workflow handle by id and invoke its `@WorkflowMethod`. The call
runs the workflow and gives you the result (the Kotlin coroutine suspends; a blocking Java
caller can `.get()` the future):

```kotlin
val workflow = factory<OrderWorkflow>("order-42")
val result = workflow.processOrder(request)
```

```java
OrderWorkflow workflow = factory.invoke(OrderWorkflow.class, "order-42");
OrderResult result = workflow.processOrder(request).get();
```

> **The workflow id is an idempotency key.** Invoking with an id that already exists does
> **not** start a duplicate — Skipper awaits the running instance (or immediately returns its
> recorded result) and ignores the new call's arguments. Choose an id derived from your domain
> (e.g. the order id) to make retries safe.

## The invocation builder

When you need more than a bare id — a callback handler, fire-and-forget execution, a timeout —
use `builder(...)`. It is the recommended way to construct a workflow, especially from Java:

```kotlin
val workflow = factory.builder<OrderWorkflow>("order-42")
  .callbackHandler(OrderCallbackHandler::class.java)
  .runAsync()
  .build()

workflow.processOrder(request) // a suspend method still waits — see Fire-and-forget below
```

```java
OrderWorkflow workflow = factory.builder(OrderWorkflow.class, "order-42")
    .callbackHandler(OrderCallbackHandler.class)
    .runAsync()
    .build();

workflow.processOrder(request); // returns a future you can ignore; the result arrives via the callback
```

The builder options:

| Method | Purpose |
|---|---|
| `.callbackHandler(Class)` | Register a [`WorkflowCallbackHandler`](#async-responses-with-callback-handlers) to receive lifecycle events asynchronously. |
| `.runAsync()` | Hand the workflow to the persistent scheduler instead of running it in-process. |
| `.detached()` | Return as soon as the instance is persisted and scheduled, without waiting for the result — see [Fire-and-forget](#fire-and-forget). |
| `.workflowOptions(WorkflowOptions)` | Per-invocation options: `executionTimeout` (max time to completion) and `allowQueryOnNonExistentWorkflow`. |
| `.requestContext(payload)` | An opaque, persisted payload (e.g. per-request identity) surfaced to your request-context middleware. |
| `.parentWorkflowId(id)` | Link this instance to a parent workflow (for child workflows). |

## Async responses with callback handlers

Long-running workflows can wait for days. Rather than blocking on the result, register a
**`WorkflowCallbackHandler`** and let Skipper call you back as the instance changes state.
The handler is a single interface — four methods are required, the rest have default no-op
implementations you override only if you need them:

```kotlin
class OrderCallbackHandler : WorkflowCallbackHandler {
  override fun onSuccess(workflow: WorkflowInstanceView) {
    log.info("Workflow ${workflow.id} completed")
  }

  override fun onNonRetryableError(workflow: WorkflowInstanceView, error: Throwable) {
    log.error("Workflow ${workflow.id} failed", error)
  }

  override fun onWorkflowInWaitingStatus(workflow: WorkflowInstanceView) {
    // The workflow hit a waitUntil and is now hibernating.
  }

  override fun onWorkflowTimeout(workflow: WorkflowInstanceView) {
    // The workflow exceeded its execution timeout.
  }

  // Default no-ops below — override only the ones you care about:
  override fun onCompensationCompleted(workflow: WorkflowInstanceView) { /* ... */ }
}
```

```java
public class OrderCallbackHandler implements WorkflowCallbackHandler {
  @Override
  public void onSuccess(WorkflowInstanceView workflow) {
    log.info("Workflow {} completed", workflow.getId());
  }

  @Override
  public void onNonRetryableError(WorkflowInstanceView workflow, Throwable error) {
    log.error("Workflow {} failed", workflow.getId(), error);
  }

  @Override
  public void onWorkflowInWaitingStatus(WorkflowInstanceView workflow) {
    // The workflow hit a waitUntil and is now hibernating.
  }

  @Override
  public void onWorkflowTimeout(WorkflowInstanceView workflow) {
    // The workflow exceeded its execution timeout.
  }

  // Default no-ops below — override only the ones you care about:
  @Override
  public void onCompensationCompleted(WorkflowInstanceView workflow) { /* ... */ }
}
```

You pass the handler **by class** — Skipper instantiates it through its injector, so it can
have injected dependencies. Register it on the builder:

```kotlin
factory.builder<OrderWorkflow>("order-42")
  .callbackHandler(OrderCallbackHandler::class.java)
  .build()
```

```java
factory.builder(OrderWorkflow.class, "order-42")
    .callbackHandler(OrderCallbackHandler.class)
    .build();
```

### When each callback fires

| Callback | Fires when… | Required? |
|---|---|---|
| `onSuccess` | the workflow reaches a successful terminal state | yes |
| `onNonRetryableError` | a non-retryable error (or exhausted retries) fails the workflow | yes |
| `onWorkflowInWaitingStatus` | the workflow hits an unfulfilled `waitUntil` and hibernates | yes |
| `onWorkflowTimeout` | the workflow exceeds its execution timeout | yes |
| `onRetryableError` | a retryable error occurs (before a retry) | default |
| `onRetriesExhausted` | a `PersistentRetryStrategy` exhausts its retries (see [Error Handling](/docs/error-handling/)) | default |
| `onCompensationCompleted` / `onCompensationError` | [compensation](/docs/compensation/) finishes or fails | default |
| `onCancelled` | the instance is cancelled via [instance management](/docs/instance-management/) | default |

## Fire-and-forget

By default a workflow runs in-process and is also persisted as a safety net. Calling
`.runAsync()` hands it entirely to the persistent scheduler instead, so the work happens on
whichever host picks it up.

Neither choice, on its own, makes the *call* return early. For that, add `.detached()` — the
workflow method then needs no special return type, because a result-less method is enough:

```kotlin
class PayoutWorkflow : Workflow() {
  private val actions = actions<PayoutActions>()

  @WorkflowMethod
  suspend fun process(input: PayoutInput) {
    actions.submitPayout(input)
    actions.notifyRecipient(input)
  }
}
```

```java
public class PayoutWorkflow extends Workflow {
  private final PayoutActions actions = actions(PayoutActions.class);

  @WorkflowMethod
  public void process(PayoutInput input) {
    actions.submitPayout(input);
    actions.notifyRecipient(input);
  }
}
```

The call now returns as soon as the instance is persisted and scheduled:

```kotlin
factory.builder<PayoutWorkflow>(workflowId)
  .runAsync()
  .detached()
  .build()
  .process(input)   // returns immediately
```

```java
factory.builder(PayoutWorkflow.class, workflowId)
    .runAsync()
    .detached()
    .build()
    .process(input);   // returns immediately
```

`.detached()` describes the **invocation**, not the method, so the same workflow method can still
be awaited from a callsite that omits it. In Kotlin this means fire-and-forget needs no
`CompletableFuture` anywhere — a plain `suspend fun` returning `Unit` does the job.

The work is durable: the instance is persisted and handed to the scheduler before the call
returns, so it is delivered at least once even if the calling process dies immediately
afterwards. The callsite still pays for that synchronously — validation, request-context
middleware, the instance insert and the scheduler insert all run on the calling thread. What you
skip is waiting for the workflow.

Three things to know:

- **The method must produce no result** (`Unit`/`void`, or `CompletableFuture<Void>`). A detached
  call returns before the workflow runs, so there is nothing to hand back; detaching a
  result-bearing method fails validation rather than silently handing you `null`.
- **A detached caller sees neither the result nor failures.** Skipper still records the error,
  retries, and runs compensation, but nothing surfaces at the callsite. Register a callback
  handler if you need the outcome.
- **Signals and queries ignore it** — they always run synchronously.

### Ignoring a future instead

Without `.detached()`, whether the call returns early depends on the return type: a
`CompletableFuture` method hands you a handle you can drop, while a Kotlin `suspend` method hands
you nothing, because the invocation itself is the join point. Dropping a future still works, but
it attaches the result chain whether or not anyone holds it — so under `.runAsync()` every such
call polls storage for up to `resultPollingTimeLimit` (30 seconds by default) on a Skipper thread
before giving up with a `TimeoutException`. `.detached()` skips that entirely, including for Java
callers — whose future deliberately fails with `ResultUnavailable` if awaited, rather than
completing immediately and falsely signalling that the workflow had finished.

> **Don't combine the two styles.** A `suspend fun` that returns `CompletableFuture` compiles and
> passes validation, but it is not supported. The suspend path still wins at the callsite, so you
> keep the waiting and gain no handle to drop — and the engine treats your future as the workflow's
> terminal result instead of joining it, so the instance can complete before the future resolves and
> the result fails to persist. Pick one style per method.

Use a callback handler for any workflow that can wait on a signal or timer for a long time: it
lets Skipper hibernate the instance and notify you on completion, rather than holding a thread
that will time out anyway. For the Kotlin-specific picture — why `launch { }` is a poor
substitute, and how threads are held on each side of a call — see
[Kotlin Coroutines](/docs/kotlin-coroutines/).
