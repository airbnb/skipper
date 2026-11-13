package com.example.trips;

/** Amounts are whole cents everywhere; this only formats them for people and for the model. */
public final class Money {
  private Money() {}

  public static String format(long cents) {
    return String.format("$%,d", cents / 100);
  }
}
