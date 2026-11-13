package com.example.trips;

/**
 * The provider's answer to a booking. Sold out is an ordinary answer, not an error: the workflow reads it and
 * asks the planner for a replacement. Only a result the workflow cannot route around becomes an exception.
 */
public record BookingOutcome(boolean confirmed, String confirmationId, String reason) {
  public static BookingOutcome ok(String confirmationId) {
    return new BookingOutcome(true, confirmationId, null);
  }

  public static BookingOutcome soldOut(String reason) {
    return new BookingOutcome(false, null, reason);
  }
}
