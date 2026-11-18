package com.example.claims;

/** The in-memory payment system, plus an outage the test can switch on: the next few reservations fail. */
class FlakyPayments extends InMemoryPayments {
  private int failuresLeft;

  synchronized void failNext(int times) {
    failuresLeft = times;
  }

  @Override
  public synchronized String reserve(String claimId, long amountCents) throws PaymentsUnavailableException {
    if (failuresLeft > 0) {
      failuresLeft--;
      throw new PaymentsUnavailableException("503 from the payment system");
    }
    return super.reserve(claimId, amountCents);
  }
}
