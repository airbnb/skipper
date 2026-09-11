package com.example.orders;

import java.util.Objects;

/** Workflow output. Same serialization rules as {@link OrderRequest}. */
public class OrderResult {
  private String status;
  private String reason;
  private String reservationId;
  private String paymentId;
  private String trackingId;

  public OrderResult() {}

  private OrderResult(String status, String reason, String reservationId, String paymentId, String trackingId) {
    this.status = status;
    this.reason = reason;
    this.reservationId = reservationId;
    this.paymentId = paymentId;
    this.trackingId = trackingId;
  }

  public static OrderResult fulfilled(String reservationId, String paymentId, String trackingId) {
    return new OrderResult("FULFILLED", null, reservationId, paymentId, trackingId);
  }

  public static OrderResult rejected(String reason) {
    return new OrderResult("REJECTED", reason, null, null, null);
  }

  public String getStatus() {
    return status;
  }

  public String getReason() {
    return reason;
  }

  public String getReservationId() {
    return reservationId;
  }

  public String getPaymentId() {
    return paymentId;
  }

  public String getTrackingId() {
    return trackingId;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof OrderResult)) return false;
    OrderResult that = (OrderResult) o;
    return Objects.equals(status, that.status)
        && Objects.equals(reason, that.reason)
        && Objects.equals(reservationId, that.reservationId)
        && Objects.equals(paymentId, that.paymentId)
        && Objects.equals(trackingId, that.trackingId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(status, reason, reservationId, paymentId, trackingId);
  }

  @Override
  public String toString() {
    return "OrderResult{" + status + (reason != null ? ", " + reason : "")
        + (trackingId != null ? ", tracking=" + trackingId : "") + "}";
  }
}
