package com.example.orders;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/** A carrier that permanently refuses any SKU starting with "HAZMAT", so the workflow compensates. */
@Service
public class StubCarrier implements Carrier {
  private final AtomicInteger seq = new AtomicInteger();

  @Override
  public String ship(String orderId, String sku) {
    if (sku.startsWith("HAZMAT")) {
      throw new IllegalArgumentException("carrier refuses hazardous goods: " + sku);
    }
    return "trk-" + seq.incrementAndGet();
  }
}
