package com.example.orders;

import java.util.Objects;

/**
 * Workflow input. Skipper persists it as JSON and, before the first run, round-trips it and compares with
 * {@code equals}, so a plain POJO with a no-arg constructor, getters, and equals/hashCode over every field
 * is required. Java records are not supported by the Jackson version Skipper compiles against.
 */
public class OrderRequest {
  private String orderId;
  private String customerId;
  private String sku;
  private int quantity;
  private long amountCents;

  public OrderRequest() {}

  public OrderRequest(String orderId, String customerId, String sku, int quantity, long amountCents) {
    this.orderId = orderId;
    this.customerId = customerId;
    this.sku = sku;
    this.quantity = quantity;
    this.amountCents = amountCents;
  }

  public String getOrderId() {
    return orderId;
  }

  public String getCustomerId() {
    return customerId;
  }

  public String getSku() {
    return sku;
  }

  public int getQuantity() {
    return quantity;
  }

  public long getAmountCents() {
    return amountCents;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof OrderRequest)) return false;
    OrderRequest that = (OrderRequest) o;
    return quantity == that.quantity
        && amountCents == that.amountCents
        && Objects.equals(orderId, that.orderId)
        && Objects.equals(customerId, that.customerId)
        && Objects.equals(sku, that.sku);
  }

  @Override
  public int hashCode() {
    return Objects.hash(orderId, customerId, sku, quantity, amountCents);
  }

  @Override
  public String toString() {
    return "OrderRequest{" + orderId + ", " + quantity + " x " + sku + ", $" + amountCents / 100 + "}";
  }
}
