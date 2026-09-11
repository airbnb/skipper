package com.example.orders;

public interface Carrier {
  /**
   * @throws CarrierUnavailableException when the carrier is down and the call should be retried
   * @throws IllegalArgumentException when the carrier refuses the shipment for good
   */
  String ship(String orderId, String sku);
}
