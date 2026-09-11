package com.example.orders;

/** A transient failure: the action wraps it in Skipper's RetryableError so the engine retries the step. */
public class CarrierUnavailableException extends RuntimeException {
  public CarrierUnavailableException(String message) {
    super(message);
  }
}
