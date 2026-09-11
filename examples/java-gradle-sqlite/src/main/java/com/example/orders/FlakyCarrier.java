package com.example.orders;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A carrier that can be told to be down for the next N calls (transient) and that permanently refuses any
 * SKU starting with "HAZMAT" (non-retryable, so the workflow compensates).
 */
public class FlakyCarrier implements Carrier {
  private final AtomicInteger failuresRemaining = new AtomicInteger();
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicInteger seq = new AtomicInteger();

  public void failNext(int times) {
    failuresRemaining.set(times);
  }

  public int calls() {
    return calls.get();
  }

  @Override
  public String ship(String orderId, String sku) {
    calls.incrementAndGet();
    if (failuresRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
      throw new CarrierUnavailableException("carrier API returned 503");
    }
    if (sku.startsWith("HAZMAT")) {
      throw new IllegalArgumentException("carrier refuses hazardous goods: " + sku);
    }
    return "trk-" + seq.incrementAndGet();
  }
}
