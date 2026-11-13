package com.example.claims;

/** Pay or deny, with the amount and a reason that can be shown to the claimant. */
public record Decision(boolean pay, long payoutCents, String explanation) {
  public static Decision pay(long payoutCents, String explanation) {
    return new Decision(true, payoutCents, explanation);
  }

  public static Decision deny(String explanation) {
    return new Decision(false, 0, explanation);
  }
}
