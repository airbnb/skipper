package com.example.claims;

import com.airbnb.skipper.Actions;
import com.airbnb.skipper.CheckpointMode;
import com.airbnb.skipper.Compensate;
import com.airbnb.skipper.Execute;
import com.airbnb.skipper.FixedRetryStrategy;
import com.airbnb.skipper.RetryStrategy;
import com.airbnb.skipper.RetryableError;
import java.time.Duration;
import javax.inject.Inject;

/**
 * The side effects. Nothing here involves a model; it only carries out a decision the rules let through.
 *
 * <p>Both steps are saved the moment they return ({@code IMMEDIATE_CHECKPOINT}), so a crash while paying
 * does not reserve again, and the reservation is on record for {@code release} to undo. Skipper delivers
 * an action at least once: a step that was running when the process died runs again. Both calls are
 * therefore keyed on the claim id, and the payment system answers a repeat with what it did the first time.
 */
public class PaymentActions extends Actions {
  @Inject Payments payments;

  RetryStrategy paymentsOutage = new FixedRetryStrategy(Duration.ofMinutes(1), 3);

  @Execute(retryStrategy = "paymentsOutage", checkpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT)
  public String reserve(PayoutRequest payout) {
    try {
      return payments.reserve(payout.claimId(), payout.amountCents());
    } catch (PaymentsUnavailableException e) {
      throw new RetryableError("payments unavailable, will retry", e);
    }
  }

  /** Runs when a later step fails: the money set aside for the claim goes back. */
  @Compensate(forExecute = "reserve")
  public void release(PayoutRequest payout, String reservationId) {
    payments.release(reservationId);
  }

  @Execute(retryStrategy = "paymentsOutage", checkpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT)
  public void pay(PayoutRequest payout) {
    try {
      // An IllegalStateException from here (a closed account) is not retryable: the workflow fails and
      // Skipper releases the reservation.
      payments.pay(payout.claimId(), payout.account(), payout.amountCents());
    } catch (PaymentsUnavailableException e) {
      throw new RetryableError("payments unavailable, will retry", e);
    }
  }
}
