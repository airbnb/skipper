package com.example.claims;

public record ClaimResult(String status, long payoutCents, String explanation, String decidedBy) {
  public static ClaimResult paid(long payoutCents, String explanation, String decidedBy) {
    return new ClaimResult("PAID", payoutCents, explanation, decidedBy);
  }

  public static ClaimResult denied(String explanation, String decidedBy) {
    return new ClaimResult("DENIED", 0, explanation, decidedBy);
  }

  public static ClaimResult closed(String explanation) {
    return new ClaimResult("CLOSED", 0, explanation, "automatic");
  }
}
