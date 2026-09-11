package com.example.orders;

/** A collaborator the actions call. Hand-written fakes stand in for it in tests. */
public interface InventoryService {
  String reserve(String sku, int quantity);

  void release(String reservationId);
}
