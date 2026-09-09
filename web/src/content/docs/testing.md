---
title: Testing
description: Run workflows end to end in-memory and assert on their behavior.
section: Guides
order: 18
---

You test actions like any other code. Workflows are tested by running them end to end against
Skipper's default **in-memory** store, which needs no setup: build a `SkipperRuntime`, start its
scheduler, drive the workflow, and assert. Because the whole path from workflow to actions runs
for real, these are integration tests; control the scenario by mocking the collaborators your
actions call, not the actions themselves.

## Test setup

One runtime per test class (or per test) is enough. Stop the scheduler when you are done.

```kotlin
class OrderWorkflowTest {
  private lateinit var runtime: SkipperRuntime
  private lateinit var factory: IWorkflowFactory

  @BeforeEach
  fun setUp() {
    runtime = SkipperRuntime(SkipperConfig.forService("order-test")) // in-memory SQLite
    runtime.skipperSchedulerManager.get().start()
    factory = runtime.workflowFactory.get()
  }

  @AfterEach
  fun tearDown() = runtime.skipperSchedulerManager.get().stop()
}
```

```java
public class OrderWorkflowTest {
  private SkipperRuntime runtime;
  private IWorkflowFactory factory;

  @BeforeEach
  void setUp() {
    runtime = new SkipperRuntime(SkipperConfig.forService("order-test")); // in-memory SQLite
    runtime.getSkipperSchedulerManager().get().start();
    factory = runtime.getWorkflowFactory().get();
  }

  @AfterEach
  void tearDown() throws Exception { runtime.getSkipperSchedulerManager().get().stop(); }
}
```

Use a fresh workflow id per test (a UUID suffix works well); ids are the idempotency key, so
reusing one across tests would join the earlier instance instead of starting a new one.

## A basic test

A workflow that completes without waiting can simply be awaited.

```kotlin
@Test
fun happyPath() = runBlocking {
  val workflow = factory<OrderWorkflow>("order-${UUID.randomUUID()}")
  val result = workflow.processOrder(OrderRequest("cust1", 100))
  assertEquals("completed", result.status)
}
```

```java
@Test
public void happyPath() throws Exception {
  OrderWorkflow workflow = factory.invoke(OrderWorkflow.class, "order-" + UUID.randomUUID());
  OrderResult result = workflow.processOrder(new OrderRequest("cust1", 100)).get();
  assertEquals("completed", result.getStatus());
}
```

## Waiting for a status

For workflows that pause, drive them with `.runAsync()` and poll the engine's own view of the
instance rather than your `@StateField`s: the engine status is what tells you the workflow has
actually parked. A small helper covers every test:

```kotlin
private fun awaitStatus(id: String, expected: WorkflowInstanceStatusView) {
  val deadline = Instant.now().plusSeconds(30)
  while (Instant.now().isBefore(deadline)) {
    if (factory<OrderWorkflow>(id).getWorkflowInstanceView().status == expected) return
    Thread.sleep(50)
  }
  fail("$id never reached $expected")
}
```

```java
private void awaitStatus(String id, WorkflowInstanceStatusView expected) throws InterruptedException {
  Instant deadline = Instant.now().plusSeconds(30);
  while (Instant.now().isBefore(deadline)) {
    if (factory.invoke(OrderWorkflow.class, id).getWorkflowInstanceView().getStatus() == expected) return;
    Thread.sleep(50);
  }
  fail(id + " never reached " + expected);
}
```

`WorkflowInstanceStatusView` has `RUNNING`, `WAITING`, `COMPLETED`, `ERROR`, `RETRIES_EXHAUSTED`,
`TIMEOUT`, `COMPENSATION_COMPLETED`, and the other statuses you will assert on.

## Testing waits and signals

Drive the workflow to its wait, assert it is waiting, send the signal, then let it finish.

```kotlin
@Test
fun requiresApproval() = runBlocking {
  val id = "transfer-${UUID.randomUUID()}"
  factory.builder<TransferWorkflow>(id).runAsync().build()
    .transfer(TransferRequest("Mary", "Bob", 1001))

  awaitStatus(id, WorkflowInstanceStatusView.WAITING)   // amount > 1000 requires approval
  factory<TransferWorkflow>(id).approve(true)            // signal
  awaitStatus(id, WorkflowInstanceStatusView.COMPLETED)
}
```

```java
@Test
public void requiresApproval() throws Exception {
  String id = "transfer-" + UUID.randomUUID();
  factory.builder(TransferWorkflow.class, id).runAsync().build()
      .transfer(new TransferRequest("Mary", "Bob", 1001)); // returns a future we ignore

  awaitStatus(id, WorkflowInstanceStatusView.WAITING);   // amount > 1000 requires approval
  factory.invoke(TransferWorkflow.class, id).approve(true); // signal
  awaitStatus(id, WorkflowInstanceStatusView.COMPLETED);
}
```

## Testing compensation

Make a later action fail and assert that earlier actions were compensated.

```kotlin
@Test
fun compensatesOnFailure() = runBlocking {
  whenever(shippingService.schedule(any())).thenThrow(RuntimeException("Shipping failed"))

  val id = "order-${UUID.randomUUID()}"
  factory.builder<OrderWorkflow>(id).runAsync().build().processOrder(orderRequest)

  awaitStatus(id, WorkflowInstanceStatusView.COMPENSATION_COMPLETED)
  verify(paymentService).refund(any())
  verify(inventoryService).cancelReservation(any())
}
```

```java
@Test
public void compensatesOnFailure() throws Exception {
  when(shippingService.schedule(any())).thenThrow(new RuntimeException("Shipping failed"));

  String id = "order-" + UUID.randomUUID();
  factory.builder(OrderWorkflow.class, id).runAsync().build().processOrder(orderRequest);

  awaitStatus(id, WorkflowInstanceStatusView.COMPENSATION_COMPLETED);
  verify(paymentService).refund(any());
  verify(inventoryService).cancelReservation(any());
}
```

## Mocking dependencies

Skipper instantiates your `Actions` classes through the config's `SkipperInjector`, and the
default `SimpleInjector` fills `@Inject` fields from what you bind. Bind mocks of the services
your actions call before building the runtime:

```kotlin
val paymentService: PaymentService = mock()
val config = SkipperConfig.forService("order-test").apply {
  injector = SimpleInjector.builder()
    .bind(PaymentService::class.java, paymentService)
    .build()
}
```

```java
PaymentService paymentService = mock(PaymentService.class);
SkipperConfig config = SkipperConfig.forService("order-test");
config.setInjector(SimpleInjector.builder()
    .bind(PaymentService.class, paymentService)
    .build());
```

```java
public class PaymentActions extends Actions {
  @Inject PaymentService paymentService;   // filled by the injector

  @Execute
  public String charge(ChargeRequest req) { return paymentService.charge(req); }
}
```

The actions themselves are **not** mocked, so a workflow test exercises the real
workflow-and-action path end to end. If your service already uses a DI framework, implement
`SkipperInjector` over it once and set that on the config instead.
