package com.example.claims;

public interface Payments {
  /** Sets the money aside. Returns a reservation id. */
  String reserve(String claimId, long amountCents) throws PaymentsUnavailableException;

  void release(String reservationId);

  /** Sends the reserved money. Throws IllegalStateException when the account cannot receive it. */
  void pay(String claimId, String account, long amountCents) throws PaymentsUnavailableException;
}
