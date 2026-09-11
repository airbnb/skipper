package com.example.orders;

import com.airbnb.skipper.QueryMethod;
import com.airbnb.skipper.SignalMethod;
import com.airbnb.skipper.StateField;
import com.airbnb.skipper.Workflow;
import com.airbnb.skipper.WorkflowMethod;
import java.time.Duration;

/**
 * Order fulfilment: reserve stock, charge the customer, hand the parcel to a carrier. Orders at or above
 * {@link #APPROVAL_THRESHOLD_CENTS} park until someone approves them or a day passes. If the carrier refuses
 * the parcel, the charge and the reservation are compensated in reverse order.
 *
 * <p>Unlike the standalone examples, the workflow method returns nothing. A service does not hold a request
 * thread on a workflow that may park for a day: the controller starts the instance detached and callers read
 * the outcome later through {@link #result()} (or a WorkflowCallbackHandler pushes it somewhere). A detached
 * invocation requires a void workflow method.
 */
public class OrderWorkflow extends Workflow {
  static final long APPROVAL_THRESHOLD_CENTS = 50_000;
  static final Duration APPROVAL_WINDOW = Duration.ofDays(1);

  private final InventoryActions inventory = actions(InventoryActions.class);
  private final PaymentActions payments = actions(PaymentActions.class);
  private final ShippingActions shipping = actions(ShippingActions.class);

  @StateField String stage = "NEW";
  @StateField Boolean approved;
  @StateField OrderResult result;

  @WorkflowMethod
  public void placeOrder(OrderRequest order) {
    if (order.getAmountCents() >= APPROVAL_THRESHOLD_CENTS) {
      stage = "AWAITING_APPROVAL";
      boolean decided = waitUntil(() -> approved != null, APPROVAL_WINDOW);
      if (!decided) {
        finish(OrderResult.rejected("no approval within " + APPROVAL_WINDOW));
        return;
      }
      if (!approved) {
        finish(OrderResult.rejected("declined by reviewer"));
        return;
      }
    }
    stage = "RESERVING";
    String reservationId = inventory.reserve(order);
    stage = "CHARGING";
    String paymentId = payments.charge(order);
    stage = "SHIPPING";
    String trackingId = shipping.ship(order);
    finish(OrderResult.fulfilled(reservationId, paymentId, trackingId));
  }

  private void finish(OrderResult outcome) {
    stage = outcome.getStatus();
    result = outcome;
  }

  @SignalMethod(persist = true)
  public void approve(boolean decision) {
    this.approved = decision;
  }

  @QueryMethod
  public String stage() {
    return stage;
  }

  /** Null until the workflow reaches a terminal decision. */
  @QueryMethod
  public OrderResult result() {
    return result;
  }
}
