---
title: Testing
description: Run workflows end to end in-memory and assert on their behavior with Skipper's test helpers.
section: Guides
order: 18
---

You test actions like any other code. Workflows are tested with Skipper's helpers, which run
a workflow end to end against an in-memory backend and let you drive it through its states.

## Test setup

Tests run against Skipper's default **in-memory** store, so there is no backend to configure —
extend the test base and write your test.

```kotlin
class OrderWorkflowTest : SkipperTest() { /* ... */ }
```

```java
public class OrderWorkflowTest extends SkipperTest { /* ... */ }
```

> The test harness is being finalized for the public release; the exact base class may change,
> but the helpers below are the stable surface.

## A basic test

Build the workflow (the helper generates a unique id), drive it, wait for the state you
expect, then assert. Don't block on the result while the workflow may still be running — use
the wait helpers instead.

```kotlin
@Test
fun happyPath() = runBlocking {
  val workflow = workflowBuilder<OrderWorkflow>().build()

  workflow.processOrder(OrderRequest("cust1", 100))
  helper.waitForWorkflowToComplete()

  val result = workflow.processOrder(OrderRequest("cust1", 100)) // safe after completion
  assertEquals("completed", result.status)
}
```

```java
@Test
public void happyPath() {
  OrderWorkflow workflow = workflowBuilder(OrderWorkflow.class).build();

  workflow.processOrder(new OrderRequest("cust1", 100));
  helper.waitForWorkflowToComplete();

  OrderResult result = workflow.processOrder(new OrderRequest("cust1", 100)); // safe after completion
  assertEquals("completed", result.getStatus());
}
```

Useful helpers include `waitForWorkflowToComplete()`, `waitForWorkflowToReachStatus(...)`,
`expectWorkflowToWait()`, and `printEvents()` for debugging.

## Testing waits and signals

Drive a workflow to a wait, assert it's waiting, send the signal, then let it finish.

```kotlin
@Test
fun requiresApproval() = runBlocking {
  val workflow = workflowBuilder<TransferWorkflow>().build()
  workflow.transfer(TransferRequest("Mary", "Bob", 1001))

  helper.expectWorkflowToWait()   // amount > 1000 requires approval
  workflow.approve(true)          // signal
  helper.waitForWorkflowToComplete()
}
```

```java
@Test
public void requiresApproval() {
  TransferWorkflow workflow = workflowBuilder(TransferWorkflow.class).build();
  workflow.transfer(new TransferRequest("Mary", "Bob", 1001));

  helper.expectWorkflowToWait();  // amount > 1000 requires approval
  workflow.approve(true);         // signal
  helper.waitForWorkflowToComplete();
}
```

## Testing compensation

Make a later action fail and assert that earlier actions were compensated.

```kotlin
@Test
fun compensatesOnFailure() = runBlocking {
  whenever(shippingService.schedule(any())).thenThrow(RuntimeException("Shipping failed"))

  val workflow = workflowBuilder<OrderWorkflow>().build()
  workflow.processOrder(orderRequest)

  helper.waitForWorkflowToReachStatus(Status.COMPENSATION_COMPLETED)
  verify(paymentService).refund(any())
  verify(inventoryService).cancelReservation(any())
}
```

```java
@Test
public void compensatesOnFailure() {
  when(shippingService.schedule(any())).thenThrow(new RuntimeException("Shipping failed"));

  OrderWorkflow workflow = workflowBuilder(OrderWorkflow.class).build();
  workflow.processOrder(orderRequest);

  helper.waitForWorkflowToReachStatus(Status.COMPENSATION_COMPLETED);
  verify(paymentService).refund(any());
  verify(inventoryService).cancelReservation(any());
}
```

## Mocking dependencies

Action **dependencies** (the services your actions call) are mocked normally — bind a mock
in the test:

```kotlin
@Bind private val paymentService: PaymentService = mock()
```

```java
@Bind private final PaymentService paymentService = mock();
```

Note that the actions themselves are **not** mocked, so a workflow test runs the real
workflow-and-action path end to end (an integration test). Mock the collaborators behind
your actions to control the scenario.
