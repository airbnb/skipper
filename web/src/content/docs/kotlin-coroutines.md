---
title: Kotlin Coroutines
description: Write idiomatic Kotlin workflows with suspend functions — no CompletableFuture wrapping.
section: Guides
order: 16
---

Skipper fully supports Kotlin `suspend` functions for workflows, actions, signals, queries,
and compensation. Suspend functions read top-to-bottom as ordinary sequential code, with no
`CompletableFuture` wrapping and no `returnType` annotation parameter.

## Setup

All workflow and action classes must be `open` so Skipper can subclass them. The base
`Workflow` and `Actions` classes carry `@SkipperOpen`, and the Kotlin **AllOpen** compiler
plugin propagates it to your subclasses — so you don't annotate anything yourself. Just
enable the plugin in your build.

## Execution styles

Skipper supports four styles; pick whichever fits.

| | Java sync | Java async (CF) | Kotlin sync | Kotlin suspend |
|---|---|---|---|---|
| Return type | `T` | `CompletableFuture<T>` | `T` | `T` (`suspend fun`) |
| Annotation | `@WorkflowMethod` | `@WorkflowMethod(returnType=T.class)` | `@WorkflowMethod` | `@WorkflowMethod` |
| Caller blocks? | Yes | Only on `.get()` | Yes | No — coroutine suspends |
| Fire-and-forget? | `.detached()` | `.detached()`, or drop the future | `.detached()` | `.detached()` — see [below](#fire-and-forget) |

With suspend, the caller's coroutine suspends and its thread is freed while the workflow
runs — the most ergonomic option for Kotlin callers.

## Suspend workflows and actions

```kotlin
class OrderWorkflow : Workflow() {
  private val payments = actions<PaymentActions>()

  @WorkflowMethod
  suspend fun processOrder(req: OrderRequest): OrderResult {
    val paymentId = payments.charge(req.customerId, req.amount)
    return OrderResult(paymentId, "completed")
  }
}

class PaymentActions : Actions() {
  @Execute
  suspend fun charge(req: ChargeRequest): String = paymentService.charge(req)
}
```

Skipper extracts the return type from Kotlin metadata, so no `returnType` parameter is
needed.

> **Signals and queries always run synchronously**, even when declared `suspend`. The
> keyword is allowed for API consistency but doesn't change execution semantics.

## `runAsync` and long waits

`runAsync=false` (the default) runs the workflow in-memory and the caller's coroutine
suspends until it completes. `runAsync=true` hands the workflow entirely to the persistent
scheduler, so it executes on whichever host picks it up.

Unless the invocation is detached, a suspend caller waits for the result in **both** modes. Under
`runAsync=true` that wait is served by polling storage, which gives up after
`resultPollingTimeLimit` (30 seconds by default) and throws `TimeoutException`. So for workflows
that wait on signals for **hours or days**, don't try to wait at all — detach the invocation and
take the outcome from a `WorkflowCallbackHandler`:

```kotlin
val workflow = workflowFactory.builder<OrderWorkflow>(id)
  .callbackHandler(MyCallbackHandler::class.java)
  .runAsync()
  .detached()
  .build()
```

## Fire-and-forget

`.detached()` returns as soon as the instance is persisted and scheduled, so a result-less
`suspend fun` is all you need — no `CompletableFuture` in the signature:

```kotlin
@WorkflowMethod
suspend fun process(input: PayoutInput) {
  actions.submitPayout(input)
}
```

Prefer this over `launch { }`. Launching a non-detached suspend call is the structural equivalent
of dropping a future, but under `runAsync=true` the polling `TimeoutException` above propagates
into your coroutine scope — you'd add a `SupervisorJob` and a `CoroutineExceptionHandler` purely
to suppress an error you never wanted.

Don't reach for both styles at once, either: a `suspend fun` returning `CompletableFuture` compiles
but is not supported, because the suspend path still wins at the callsite while the engine treats
your future as the workflow's terminal result instead of joining it. See
[Fire-and-forget](/docs/invoking-workflows/#fire-and-forget) for the requirements and the costs of
each option.

## Testing

Wrap suspend calls in `runBlocking` (or a suspend test function):

```kotlin
@Test
fun happyPath() = runBlocking {
  val workflow = workflowBuilder<OrderWorkflow>().build()
  workflow.processOrder(OrderRequest("cust1", 100))
  helper.waitForWorkflowToComplete()
  assertEquals("completed", workflow.processOrder(OrderRequest("cust1", 100)).status)
}
```

## Recommendations

- Prefer `suspend fun` for new Kotlin workflows and actions — it's cleaner than
  `CompletableFuture`.
- Use `actions<T>()` for type-safe action handles.
- Reserve `CompletableFuture` for methods that must interoperate with existing Java callers.
