package com.example.orders;

import com.airbnb.skipper.QueryMethod;
import com.airbnb.skipper.SignalMethod;
import com.airbnb.skipper.StateField;
import com.airbnb.skipper.Workflow;
import com.airbnb.skipper.WorkflowMethod;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Order fulfilment: reserve stock, charge the customer, hand the parcel to a carrier. Orders at or above
 * {@link #APPROVAL_THRESHOLD_CENTS} park until someone approves them or a day passes. If the carrier refuses
 * the parcel, the charge and the reservation are compensated in reverse order.
 *
 * <p>Java async style: workflow methods return a CompletableFuture and name the unwrapped type on the
 * annotation. Fields marked @StateField are persisted with the instance and restored on every resume.
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

  @WorkflowMethod(returnType = OrderResult.class)
  public CompletableFuture<OrderResult> placeOrder(OrderRequest order) {
    if (order.getAmountCents() >= APPROVAL_THRESHOLD_CENTS) {
      stage = "AWAITING_APPROVAL";
      // Parks the instance (status WAITING) with no thread held. A signal or the deadline wakes it.
      boolean decided = waitUntil(() -> approved != null, APPROVAL_WINDOW);
      if (!decided) {
        return finish(OrderResult.rejected("no approval within " + APPROVAL_WINDOW));
      }
      if (!approved) {
        return finish(OrderResult.rejected("declined by reviewer"));
      }
    }
    stage = "RESERVING";
    String reservationId = inventory.reserve(order);
    stage = "CHARGING";
    String paymentId = payments.charge(order);
    stage = "SHIPPING";
    String trackingId = shipping.ship(order);
    return finish(OrderResult.fulfilled(reservationId, paymentId, trackingId));
  }

  private CompletableFuture<OrderResult> finish(OrderResult outcome) {
    stage = outcome.getStatus();
    result = outcome;
    return CompletableFuture.completedFuture(outcome);
  }

  /** Delivered from outside while the instance is parked; persisted so it can be replayed from the admin UI. */
  @SignalMethod(persist = true)
  public void approve(boolean decision) {
    this.approved = decision;
  }

  @QueryMethod
  public String stage() {
    return stage;
  }

  /** Null until the workflow reaches a terminal decision. Lets callers read the outcome without holding a future. */
  @QueryMethod
  public OrderResult result() {
    return result;
  }
}
