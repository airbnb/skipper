package com.example.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.airbnb.skipper.api.WorkflowInstanceStatusView;
import com.airbnb.skipper.testutils.Bind;
import com.airbnb.skipper.testutils.WorkflowTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Each test runs on its own in-memory Skipper runtime; Guice and Dropwizard are not involved. The @Bind fields
 * are handed to the actions' @Inject fields, so scenarios are set up by configuring the fakes, never by mocking
 * the actions themselves.
 *
 * <p>placeOrder returns nothing and is meant to be started detached, as the resource does: a direct call on the
 * test thread would block and surface the park (WaitSignal) or the failure as an exception. So every test
 * starts the instance the production way and reads the outcome through the result() query.
 *
 * <p>The bindings are captured before the test body runs: mutate a fake in place, do not reassign the field.
 */
class OrderWorkflowTest extends WorkflowTest {
  @Bind(to = InventoryService.class) InMemoryInventory inventory = new InMemoryInventory();
  @Bind(to = PaymentGateway.class) InMemoryPayments payments = new InMemoryPayments();
  @Bind(to = Carrier.class) FlakyCarrier carrier = new FlakyCarrier();

  private static final OrderRequest SMALL = new OrderRequest("o-1", "alice", "BOOK-1", 2, 3_900);
  private static final OrderRequest LARGE = new OrderRequest("o-2", "bob", "LAPTOP-9", 1, 149_900);
  private static final OrderRequest HAZMAT = new OrderRequest("o-3", "carol", "HAZMAT-7", 1, 12_000);

  private void start(OrderRequest order) {
    workflowBuilder(OrderWorkflow.class).runAsync().detached().build().placeOrder(order);
  }

  @Test
  void smallOrderIsFulfilledInOnePass() {
    start(SMALL);

    assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().getStatus());
    OrderResult result = workflow(OrderWorkflow.class).result();
    assertEquals("FULFILLED", result.getStatus());
    assertEquals("trk-1", result.getTrackingId());
    assertEquals(List.of("RESERVE res-1 2 x BOOK-1"), inventory.entries());
    assertEquals(List.of("CHARGE pay-1 alice $39"), payments.entries());
  }

  @Test
  void largeOrderWaitsForApproval() {
    start(LARGE);
    helper.expectWorkflowToWait();
    assertEquals("AWAITING_APPROVAL", workflow(OrderWorkflow.class).stage());
    assertTrue(payments.entries().isEmpty(), "nothing is charged before approval");

    workflow(OrderWorkflow.class).approve(true);

    assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().getStatus());
    assertEquals("FULFILLED", workflow(OrderWorkflow.class).result().getStatus());
    assertEquals(1, payments.entries().size());
  }

  @Test
  void declinedApprovalRejectsWithoutSideEffects() {
    start(LARGE);
    helper.expectWorkflowToWait();

    workflow(OrderWorkflow.class).approve(false);

    helper.waitForWorkflowToComplete();
    assertEquals("REJECTED", workflow(OrderWorkflow.class).result().getStatus());
    assertTrue(inventory.entries().isEmpty());
    assertTrue(payments.entries().isEmpty());
  }

  @Test
  void approvalWindowExpires() {
    start(LARGE);
    helper.expectWorkflowToWait();

    // The test clock ticks with real time; jump it past the one-day window instead of waiting.
    clock.fastForward(Duration.ofDays(1).plusMinutes(1));

    assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().getStatus());
    assertEquals("REJECTED", workflow(OrderWorkflow.class).result().getStatus());
  }

  @Test
  void carrierOutageIsRetried() {
    carrier.failNext(2); // two 503s, then success; the action's strategy allows three retries a minute apart
    start(SMALL);

    // Each retry is a timer on the test clock; step the clock until the instance is terminal.
    assertEquals(
        WorkflowInstanceStatusView.COMPLETED,
        helper.fastForwardUntilWorkflowCompletes(Duration.ofMinutes(1)).getStatus());
    assertEquals(3, carrier.calls());
    assertEquals("FULFILLED", workflow(OrderWorkflow.class).result().getStatus());
  }

  @Test
  void refusedShipmentCompensatesChargeAndReservation() {
    start(HAZMAT);

    // The instance passes through ERROR on its way here; both are terminal, so wait for the one that matters.
    helper.fastForwardUntilWorkflowReachesStatus(WorkflowInstanceStatusView.COMPENSATION_COMPLETED, Duration.ofSeconds(30));

    assertEquals(List.of("RESERVE res-1 1 x HAZMAT-7", "RELEASE res-1"), inventory.entries());
    assertEquals(List.of("CHARGE pay-1 carol $120", "REFUND pay-1"), payments.entries());
  }
}
