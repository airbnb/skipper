package com.example.claims;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A stand-in for the payment system: a ledger, and one account that always bounces. */
public class InMemoryPayments implements Payments {
  public static final String CLOSED_ACCOUNT = "acct-closed";

  private final List<String> ledger = new ArrayList<>();
  private final Set<String> reserved = new HashSet<>();
  private final Set<String> paid = new HashSet<>();

  /** The reservation id is derived from the claim id, so a repeated call finds the first reservation. */
  public static String reservationId(String claimId) {
    return "rsv-" + claimId;
  }

  @Override
  public synchronized String reserve(String claimId, long amountCents) throws PaymentsUnavailableException {
    String reservationId = reservationId(claimId);
    if (reserved.add(reservationId)) {
      ledger.add("RESERVE " + reservationId + " " + Money.format(amountCents));
    }
    return reservationId;
  }

  @Override
  public synchronized void release(String reservationId) {
    if (reserved.remove(reservationId)) {
      ledger.add("RELEASE " + reservationId);
    }
  }

  @Override
  public synchronized void pay(String claimId, String account, long amountCents) throws PaymentsUnavailableException {
    if (CLOSED_ACCOUNT.equals(account)) {
      throw new IllegalStateException("the claimant's bank account is closed");
    }
    if (paid.add(claimId)) {
      ledger.add("PAY " + Money.format(amountCents) + " to " + account);
    }
  }

  public synchronized List<String> ledger() {
    return new ArrayList<>(ledger);
  }
}
