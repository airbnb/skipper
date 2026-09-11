---
title: Testing
description: Run workflows end to end in-memory with the WorkflowTest base class and assert on their behavior.
section: Guides
order: 18
---

You test actions like any other code. Workflows are tested with **`WorkflowTest`**, a JUnit 5
base class from `skipper-testutils` that runs each test on its own Skipper runtime backed by the
embedded **in-memory** SQLite store. There is nothing to configure and nothing shared between
tests. The artifact adds nothing to your classpath beyond `skipper-core` itself; JUnit is already
there for your tests.

```kotlin
// build.gradle.kts
dependencies {
  testImplementation("com.airbnb.skipper:skipper-testutils:0.6.0")
}
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>com.airbnb.skipper</groupId>
  <artifactId>skipper-testutils</artifactId>
  <version>0.6.0</version>
  <scope>test</scope>
</dependency>
```

```kotlin
import com.airbnb.skipper.testutils.WorkflowTest
import com.airbnb.skipper.testutils.workflowBuilder

class OrderWorkflowTest : WorkflowTest() { /* ... */ }
```

```java
import com.airbnb.skipper.testutils.WorkflowTest;

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
| `helper` | Wait helpers: `waitForWorkflowToComplete()`, `expectWorkflowToWait()`, `waitForWorkflowToReachStatus(...)`, `waitForCondition { }`, `currentView()`; and `fastForwardUntilWorkflowCompletes()` / `fastForwardUntilWorkflowReachesStatus(...)`, which also advance the clock so retries and timers fire. |
| `clock` | A `MutableClock` over real time that you can jump ahead with `clock.fastForward(...)`. `waitUntil` deadlines, `sleep`s **and retry delays** are all timers on it. Override `createClock()` to return `MutableClock()` for time fixed at the epoch that moves only when you say so. |
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

The test clock ticks with real time, but no test should sit through a one-day approval window:
jump the clock past the deadline instead.

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

## Testing retries

A retryable failure schedules the next attempt as a **timer** on the test clock. Because that clock
ticks with real time, a retry strategy measured in milliseconds (the engine's default is 100 ms,
five times) needs nothing special: await the result as in the basic test and the retries happen.

Delays a test should not sit through are different: a strategy that retries minutes apart, a
`sleep(...)`, or the compensation backoff, which starts at 30 seconds. One `fastForward` is not
enough for those either, because each retry schedules a fresh timer relative to the already-advanced
clock. `helper.fastForwardUntilWorkflowCompletes(step)` steps the clock forward between polls (one
second per poll by default) until the workflow is terminal:

```kotlin
@Test
fun carrierOutageIsRetried() {
  carrier.failuresRemaining = 2 // the action's strategy allows three retries, ten minutes apart

  workflowBuilder<ShippingWorkflow>().build().ship(order)

  assertEquals(
    WorkflowInstanceStatusView.COMPLETED,
    helper.fastForwardUntilWorkflowCompletes(Duration.ofMinutes(10)).status,
  )
  assertEquals(3, carrier.calls)
}
```

```java
@Test
public void carrierOutageIsRetried() throws Exception {
  carrier.failuresRemaining = 2; // the action's strategy allows three retries, ten minutes apart

  workflowBuilder(ShippingWorkflow.class).build().ship(order);

  assertEquals(
      WorkflowInstanceStatusView.COMPLETED,
      helper.fastForwardUntilWorkflowCompletes(Duration.ofMinutes(10)).getStatus());
  assertEquals(3, carrier.calls);
}
```

Do not await the workflow method's future before the helper returns; that is the call that
blocks. Read the result afterwards by invoking the method again on the completed instance.
Exhausted retries end in `ERROR`. When earlier actions have compensations (next section) the
workflow passes through `ERROR` on its way to `COMPENSATION_COMPLETED`, and both count as
terminal, so wait for the one you mean with
`fastForwardUntilWorkflowReachesStatus(COMPENSATION_COMPLETED)`. Pass a `step` at or above the
delay you want to skip and below any `waitUntil` deadline you do not want to expire along the way.

If you would rather time never moved unless the test says so, override `createClock()` to return
`MutableClock()`, which is fixed at the epoch. Then every retry, however short, waits for a
`fastForward`, and the helpers above are the only way a retried workflow finishes.

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

The bindings are captured when the base class sets up the runtime, before your test method runs.
Configure a scenario by mutating the fake in place (`carrier.failuresRemaining = 2`), not by
assigning a new object to the field: a reassignment inside the test body compiles, runs, and is
never seen by the actions.

## Without the base class

`WorkflowTest` is a convenience over public API. If it does not fit (a different test framework,
one shared runtime for a whole suite), build a `SkipperRuntime` yourself: `SkipperConfig.forService(name)`
already selects the in-memory store, `SimpleInjector.builder().bind(...)` supplies collaborators,
and `WorkflowTestHelper(runtime, workflowId)` gives you the same wait helpers.

The same artifact also carries the lower-level pieces Skipper's own tests use: `TestRuntime` (a
pre-wired runtime whose `FeatureGate` and `Knobs` are Mockito mocks, so Mockito must be on your
test classpath to use it) and the `SqliteTestSetupExtension` / `MySqlTestSetupExtension` JUnit
extensions (the MySQL one needs MariaDB4j and the MariaDB driver). None of those are needed for
`WorkflowTest`.
