package com.example.trips;

/** The model could not be reached or was overloaded. Worth retrying; says nothing about the request. */
public class PlannerUnavailableException extends Exception {
  public PlannerUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
