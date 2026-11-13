package com.example.claims;

import com.airbnb.skipper.Actions;
import com.airbnb.skipper.Compensate;
import com.airbnb.skipper.Execute;
import com.airbnb.skipper.FixedRetryStrategy;
import com.airbnb.skipper.RetryStrategy;
import com.airbnb.skipper.RetryableError;
import java.time.Duration;
import javax.inject.Inject;

/** The side effects. Nothing here involves a model; it only carries out a decision the rules let through. */
public class PaymentActions extends Actions {
  @Inject Payments payments;

  RetryStrategy paymentsOutage = new FixedRetryStrategy(Duration.ofMinutes(1), 3);

  @Execute(retryStrategy = "paymentsOutage")
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

  @Execute(retryStrategy = "paymentsOutage")
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
