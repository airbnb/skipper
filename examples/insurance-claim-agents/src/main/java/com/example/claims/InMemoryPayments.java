package com.example.claims;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A stand-in for the payment system: a ledger, one account that always bounces, and a switch to fail calls. */
public class InMemoryPayments implements Payments {
  public static final String CLOSED_ACCOUNT = "acct-closed";

  private final List<String> ledger = new ArrayList<>();
  private final AtomicInteger reservations = new AtomicInteger();
  private int failuresLeft;

  @Override
  public synchronized String reserve(String claimId, long amountCents) throws PaymentsUnavailableException {
    if (failuresLeft > 0) {
      failuresLeft--;
      throw new PaymentsUnavailableException("503 from the payment system");
    }
    String reservationId = "rsv-" + reservations.incrementAndGet();
    ledger.add("RESERVE " + reservationId + " " + Money.format(amountCents));
    return reservationId;
  }

  @Override
  public synchronized void release(String reservationId) {
    ledger.add("RELEASE " + reservationId);
  }

  @Override
  public synchronized void pay(String claimId, String account, long amountCents) {
    if (CLOSED_ACCOUNT.equals(account)) {
      throw new IllegalStateException("the claimant's bank account is closed");
    }
    ledger.add("PAY " + Money.format(amountCents) + " to " + account);
  }

  public synchronized void failNext(int times) {
    failuresLeft = times;
  }

  public synchronized List<String> ledger() {
    return new ArrayList<>(ledger);
  }
}
