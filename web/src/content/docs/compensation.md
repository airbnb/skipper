---
title: Compensation (Saga)
description: Automatically undo completed work when a workflow fails, the saga way.
section: Guides
order: 13
---

When a workflow fails partway through, the actions that already succeeded may have left side
effects — a charge, a reservation, a created record. **Compensation** is Skipper's mechanism
for automatically undoing that work, giving you the saga pattern with almost no boilerplate.

## How it works

When a workflow hits a non-retryable error (directly, or after retries are exhausted),
Skipper automatically:

1. Identifies every successfully executed action that has a compensation method.
2. Runs those compensations in **reverse order** of execution.
3. Moves the workflow to `COMPENSATION_COMPLETED`.
4. Notifies any registered callback handler.

```
Executed:    [charge] → [reserve] → [ship ✗ fails]
Compensated: [un-reserve] → [refund]
```

## Writing compensation methods

Annotate a method with `@Compensate(forExecute = "...")`, naming the action it undoes.

```kotlin
class PaymentActions : Actions() {
  @Execute
  suspend fun charge(request: ChargeRequest): String =
    paymentService.charge(request)

  @Compensate(forExecute = "charge")
  suspend fun refund(request: ChargeRequest, paymentId: String) {
    // First param: the original action input.
    // Second param (optional): the action's result.
    paymentService.refund(paymentId)
  }
}
```

```java
public class PaymentActions extends Actions {
  @Execute
  public String charge(ChargeRequest request) {
    return paymentService.charge(request);
  }

  @Compensate(forExecute = "charge")
  public void refund(ChargeRequest request, String paymentId) {
    // First param: the original action input.
    // Second param (optional): the action's result.
    paymentService.refund(paymentId);
  }
}
```

**Rules:**

- A compensable action takes **at most one argument**, and it must be serializable.
- The compensation's first parameter matches the action's **input** type.
- An optional second parameter matches the action's **return** type (the unwrapped value —
  not a `CompletableFuture`). These rules are validated when the workflow is created.

## Compensation is automatic

You don't call compensation yourself — Skipper runs it when the workflow fails. The workflow
body stays clean:

```kotlin
@WorkflowMethod
suspend fun processOrder(req: OrderRequest) {
  payments.charge(req.charge)       // compensated if a later step fails
  inventory.reserve(req.items)      // compensated if a later step fails
  shipping.schedule(req)            // if this fails, the two above are undone
}
```

```java
@WorkflowMethod
public CompletableFuture<Void> processOrder(OrderRequest req) {
  payments.charge(req.getCharge());   // compensated if a later step fails
  inventory.reserve(req.getItems());  // compensated if a later step fails
  shipping.schedule(req);             // if this fails, the two above are undone
  return CompletableFuture.completedFuture(null);
}
```

## Compensating a cancelled workflow

By default a cancel is terminal: the instance stops as `CANCELLED` and nothing is undone. A
workflow method can opt in to the same rollback on cancel:

```kotlin
@WorkflowMethod(compensateOnCancel = true)
suspend fun processOrder(req: OrderRequest) { /* ... */ }
```

```java
@WorkflowMethod(compensateOnCancel = true)
public CompletableFuture<Void> processOrder(OrderRequest req) { /* ... */ }
```

Cancelling such an instance runs the compensations of its completed actions in reverse order and
ends it as `COMPENSATION_COMPLETED` (or `COMPENSATION_ERROR` if a compensation fails), so the cancel
is no longer instantaneous. `onCancelled` and `onCompensationCompleted` both fire, in no guaranteed
order. An action that an [in-flight cancel](/docs/instance-management/#a-single-instance) interrupted
has no checkpoint and is not compensated; the engine names it in a `CANCEL_COMPENSATION` event. Also
not compensated: actions whose checkpoints had not been written yet (`EVENTUAL_CHECKPOINT`) when the
worker died between the cancel and the end of the run, since a cancelled instance is not replayed.
Use `IMMEDIATE_CHECKPOINT` on compensable actions where that matters. This needs the
`INFLIGHT_CANCELLATION_CHECKPOINTS` feature gate on.

## Reacting to compensation

Implement callback methods to be notified when compensation finishes or itself fails (which
may need manual intervention):

```kotlin
override fun onCompensationCompleted(workflow: WorkflowInstanceView) { /* ... */ }
override fun onCompensationError(workflow: WorkflowInstanceView, error: SkipperError) { /* ... */ }
```

```java
@Override
public void onCompensationCompleted(WorkflowInstanceView workflow) { /* ... */ }

@Override
public void onCompensationError(WorkflowInstanceView workflow, SkipperError error) { /* ... */ }
```

This is how Skipper workflows achieve eventual data consistency across services.
