---
title: Testing
description: Run workflows end to end in-memory with the WorkflowTest base class and assert on their behavior.
section: Guides
order: 18
---

You test actions like any other code. Workflows are tested with **`WorkflowTest`**, a JUnit 5
base class shipped in `skipper-core` that runs each test on its own Skipper runtime backed by the
embedded **in-memory** SQLite store. There is nothing to configure and nothing shared between
tests. It pulls in no dependencies of its own: JUnit is already on your test classpath, and the
class is in the jar you already have.

```kotlin
import com.airbnb.skipper.testing.WorkflowTest
import com.airbnb.skipper.testing.workflowBuilder

class OrderWorkflowTest : WorkflowTest() { /* ... */ }
```

```java
import com.airbnb.skipper.testing.WorkflowTest;

public class OrderWorkflowTest extends WorkflowTest { /* ... */ }
```

Because the whole path from workflow to actions runs for real, these are integration tests:
control the scenario by supplying fakes for the collaborators your actions call, not by mocking
the actions themselves.

## What the base class gives you

| Member | Purpose |
| --- | --- |
| `workflowBuilder<T>()` / `workflowBuilder(T.class)` | An `InvocationBuilder` for this test's workflow instance. |
| `workflow<T>()` / `workflow(T.class)` | A handle on that same instance, for signals, queries, or reading the result. |
| `workflowId` | The instance id, unique per test. |
| `helper` | Wait helpers: `waitForWorkflowToComplete()`, `expectWorkflowToWait()`, `waitForWorkflowToReachStatus(...)`, `waitForCondition { }`, `currentView()`. |
| `clock` | A `MutableClock` fixed at the epoch. Timers and `waitUntil` deadlines fire only when you call `clock.fastForward(...)`. |
| `@Bind` | Annotate a field to hand its value to workflows and actions that `@Inject` that type. |
| `configure(config)` | Override to adjust the `SkipperConfig` (retry strategy, checkpoint mode, ...) before the runtime starts. |
| `printHistory()` | Dumps the instance's status, state and checkpoints; useful when a wait times out. |

If you add your own `@BeforeEach`, there is nothing to call: the base class's setup runs on its
own and yours runs after it.

## A basic test

Drive the workflow, wait for the status you expect, then assert. For a workflow that completes
without waiting, awaiting the result is enough.

```kotlin
@Test
fun happyPath() {
  val workflow = workflowBuilder<OrderWorkflow>().build()

  val result = workflow.processOrder(OrderRequest("cust1", 100)).get()

  assertEquals("completed", result.status)
  assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().status)
}
```

```java
@Test
public void happyPath() throws Exception {
  OrderWorkflow workflow = workflowBuilder(OrderWorkflow.class).build();

  OrderResult result = workflow.processOrder(new OrderRequest("cust1", 100)).get();

  assertEquals("completed", result.getStatus());
  assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().getStatus());
}
```

`waitForWorkflowToComplete()` returns once the instance is in any terminal status and hands back
its `WorkflowInstanceView`, so assert on `status` rather than assuming success.

## Testing waits and signals

Start the workflow, wait until the engine reports it is parked, send the signal, then let it
finish. Re-invoking the workflow method on a completed instance returns the recorded result.

```kotlin
@Test
fun requiresApproval() {
  val workflow = workflowBuilder<TransferWorkflow>().build()
  workflow.transfer(TransferRequest("Mary", "Bob", 1001)) // amount > 1000 requires approval

  helper.expectWorkflowToWait()
  workflow<TransferWorkflow>().approve(true)                // signal
  helper.waitForWorkflowToComplete()

  assertEquals("approved", workflow<TransferWorkflow>().transfer(TransferRequest("Mary", "Bob", 1001)).get().status)
}
```

```java
@Test
public void requiresApproval() throws Exception {
  TransferWorkflow workflow = workflowBuilder(TransferWorkflow.class).build();
  workflow.transfer(new TransferRequest("Mary", "Bob", 1001)); // amount > 1000 requires approval

  helper.expectWorkflowToWait();
  workflow(TransferWorkflow.class).approve(true);              // signal
  helper.waitForWorkflowToComplete();

  assertEquals("approved", workflow(TransferWorkflow.class).transfer(new TransferRequest("Mary", "Bob", 1001)).get().getStatus());
}
```

## Testing timeouts

Time does not pass on its own inside a `WorkflowTest`, so a `waitUntil` deadline is tested by
moving the clock past it:

```kotlin
@Test
fun approvalTimesOut() {
  workflowBuilder<TransferWorkflow>().build().transfer(TransferRequest("Mary", "Bob", 1001))
  helper.expectWorkflowToWait()

  clock.fastForward(Duration.ofDays(2)) // past the one-day approval window

  assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().status)
}
```

```java
@Test
public void approvalTimesOut() {
  workflowBuilder(TransferWorkflow.class).build().transfer(new TransferRequest("Mary", "Bob", 1001));
  helper.expectWorkflowToWait();

  clock.fastForward(Duration.ofDays(2)); // past the one-day approval window

  assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().getStatus());
}
```

## Testing compensation

Make a later action fail and assert that earlier actions were compensated.

```kotlin
@Test
fun compensatesOnFailure() {
  shippingService.failNext = true

  workflowBuilder<OrderWorkflow>().build().processOrder(orderRequest)

  helper.waitForWorkflowToReachStatus(WorkflowInstanceStatusView.COMPENSATION_COMPLETED)
  assertTrue(paymentService.refunded)
  assertTrue(inventoryService.reservationCancelled)
}
```

```java
@Test
public void compensatesOnFailure() {
  shippingService.failNext = true;

  workflowBuilder(OrderWorkflow.class).build().processOrder(orderRequest);

  helper.waitForWorkflowToReachStatus(WorkflowInstanceStatusView.COMPENSATION_COMPLETED);
  assertTrue(paymentService.refunded);
  assertTrue(inventoryService.reservationCancelled);
}
```

## Supplying collaborators with `@Bind`

Skipper instantiates your `Actions` classes and fills their `@Inject` fields from an injector. In
a `WorkflowTest`, every field annotated with `@Bind` becomes a binding keyed by the field's
declared type, so a fake or a mock declared on the test reaches the action:

```kotlin
class OrderWorkflowTest : WorkflowTest() {
  @Bind val paymentService: PaymentService = mock()          // any mocking library, or a hand-written fake
  @Bind(to = ShippingService::class) val shipping = FakeShippingService()
}
```

```java
public class OrderWorkflowTest extends WorkflowTest {
  @Bind PaymentService paymentService = mock(PaymentService.class);
  @Bind(to = ShippingService.class) FakeShippingService shipping = new FakeShippingService();
}
```

```java
public class PaymentActions extends Actions {
  @Inject PaymentService paymentService;   // filled from the @Bind field

  @Execute
  public String charge(ChargeRequest req) { return paymentService.charge(req); }
}
```

Use `to` when the field's type is the concrete fake but the action injects an interface, and
`qualifier` to match a `@Named` injection point. Types without a binding are created through
their no-arg constructor, as in production.

## Without the base class

`WorkflowTest` is a convenience over public API. If it does not fit (a different test framework,
one shared runtime for a whole suite), build a `SkipperRuntime` yourself: `SkipperConfig.forService(name)`
already selects the in-memory store, `SimpleInjector.builder().bind(...)` supplies collaborators,
and `WorkflowTestHelper(runtime, workflowId)` gives you the same wait helpers.
