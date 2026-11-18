package com.example.claims;

/**
 * The payment system. Each call is keyed on the claim id: asking twice for the same claim does the work
 * once and returns the first answer, so an action that Skipper runs again after a crash is harmless.
 */
public interface Payments {
  /** Sets the money aside. Returns the claim's reservation id, the same one however often it is asked. */
  String reserve(String claimId, long amountCents) throws PaymentsUnavailableException;

  void release(String reservationId);

  /** Sends the reserved money, once per claim. Throws IllegalStateException when the account cannot receive it. */
  void pay(String claimId, String account, long amountCents) throws PaymentsUnavailableException;
}
