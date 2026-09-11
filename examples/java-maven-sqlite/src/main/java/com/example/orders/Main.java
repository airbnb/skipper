package com.example.orders;

import com.airbnb.skipper.IWorkflowFactory;
import com.airbnb.skipper.api.WorkflowInstanceStatusView;
import com.airbnb.skipper.api.WorkflowInstanceView;
import com.airbnb.skipper.factory.SkipperRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Runs three orders through {@link OrderWorkflow} against a file-backed SQLite store:
 *
 * <ol>
 *   <li>a small order that completes in one pass;
 *   <li>a large order that parks for approval, is approved by a signal, and then completes;
 *   <li>a hazardous order the carrier refuses, whose charge and reservation are compensated.
 * </ol>
 *
 * Re-run it and the earlier instances are still in orders.db: the store is durable across processes.
 */
public final class Main {
  public static void main(String[] args) throws Exception {
    String dbPath = args.length > 0 ? args[0] : "orders.db";
    InMemoryInventory inventory = new InMemoryInventory();
    InMemoryPayments payments = new InMemoryPayments();
    FlakyCarrier carrier = new FlakyCarrier();

    SkipperRuntime runtime = SkipperSetup.start(dbPath, inventory, payments, carrier);
    IWorkflowFactory factory = runtime.getWorkflowFactory().get();
    String run = UUID.randomUUID().toString().substring(0, 8);
    try {
      // 1. Small order: the future completes when the workflow does.
      String smallId = "order-" + run + "-small";
      OrderResult small = factory.builder(OrderWorkflow.class, smallId)
          .callbackHandler(OrderCallbackHandler.class)
          .build()
          .placeOrder(new OrderRequest(smallId, "alice", "BOOK-1", 2, 3_900))
          .get();
      System.out.println(smallId + " -> " + small);

      // 2. Large order: parks in WAITING. The future does not complete while it is parked, so do not block
      //    on it; watch the instance status instead and read the outcome through the @QueryMethod.
      String largeId = "order-" + run + "-large";
      OrderWorkflow large = factory.builder(OrderWorkflow.class, largeId)
          .callbackHandler(OrderCallbackHandler.class)
          .build();
      large.placeOrder(new OrderRequest(largeId, "bob", "LAPTOP-9", 1, 149_900));
      awaitStatus(factory, largeId, EnumSet.of(WorkflowInstanceStatusView.WAITING), Duration.ofSeconds(20));
      System.out.println(largeId + " -> WAITING, stage=" + large.stage());

      // Any process holding the same store can signal it; here it is the same one.
      factory.invoke(OrderWorkflow.class, largeId).approve(true);
      awaitStatus(factory, largeId, EnumSet.of(WorkflowInstanceStatusView.COMPLETED), Duration.ofSeconds(20));
      System.out.println(largeId + " -> " + factory.invoke(OrderWorkflow.class, largeId).result());

      // 3. Hazardous order: reserve and charge succeed, the carrier refuses, both are undone.
      String hazId = "order-" + run + "-hazmat";
      factory.builder(OrderWorkflow.class, hazId)
          .callbackHandler(OrderCallbackHandler.class)
          .build()
          .placeOrder(new OrderRequest(hazId, "carol", "HAZMAT-7", 1, 12_000));
      WorkflowInstanceView view = awaitStatus(factory, hazId,
          EnumSet.of(WorkflowInstanceStatusView.COMPENSATION_COMPLETED), Duration.ofSeconds(90));
      System.out.println(hazId + " -> " + view.getStatus());
      System.out.println("inventory: " + inventory.entries());
      System.out.println("payments:  " + payments.entries());
    } finally {
      runtime.getSkipperSchedulerManager().get().stop();
    }
  }

  /** Polls the persisted status. Terminal statuses other than the expected ones end the wait early. */
  static WorkflowInstanceView awaitStatus(IWorkflowFactory factory, String id,
      Set<WorkflowInstanceStatusView> expected, Duration timeout) throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    WorkflowInstanceView view;
    while (true) {
      view = factory.invoke(OrderWorkflow.class, id).getWorkflowInstanceView();
      if (expected.contains(view.getStatus())) return view;
      if (Instant.now().isAfter(deadline)) {
        throw new IllegalStateException(id + " is " + view.getStatus() + " after " + timeout + ", expected " + expected);
      }
      Thread.sleep(200);
    }
  }
}
