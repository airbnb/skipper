package com.example.orders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemoryPayments implements PaymentGateway {
  private final AtomicInteger seq = new AtomicInteger();
  private final List<String> log = Collections.synchronizedList(new ArrayList<>());

  @Override
  public String charge(String customerId, long amountCents) {
    String id = "pay-" + seq.incrementAndGet();
    log.add("CHARGE " + id + " " + customerId + " $" + amountCents / 100);
    return id;
  }

  @Override
  public void refund(String paymentId) {
    log.add("REFUND " + paymentId);
  }

  public List<String> entries() {
    return new ArrayList<>(log);
  }
}
