package com.example.claims;

import com.airbnb.skipper.NonRetryableError;

/**
 * Thrown by the adjuster's signal when the rules do not accept the decision. The message says why.
 *
 * <p>It extends Skipper's NonRetryableError so that it reaches the signal's caller as itself. Any other
 * exception thrown from a signal method arrives wrapped in a NonRetryableError, with the original as its cause.
 */
public class DecisionRefusedException extends NonRetryableError {
  public DecisionRefusedException(String reason) {
    super(reason);
  }
}
