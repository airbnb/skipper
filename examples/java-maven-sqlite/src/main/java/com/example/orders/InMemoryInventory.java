package com.example.orders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Stand-in for a real inventory system; records every call so the demo and the tests can assert on it. */
public class InMemoryInventory implements InventoryService {
  private final AtomicInteger seq = new AtomicInteger();
  private final List<String> log = Collections.synchronizedList(new ArrayList<>());

  @Override
  public String reserve(String sku, int quantity) {
    String id = "res-" + seq.incrementAndGet();
    log.add("RESERVE " + id + " " + quantity + " x " + sku);
    return id;
  }

  @Override
  public void release(String reservationId) {
    log.add("RELEASE " + reservationId);
  }

  public List<String> entries() {
    return new ArrayList<>(log);
  }
}
