package com.example.claims;

/** The model could not be reached or was overloaded. Worth retrying; says nothing about the claim. */
public class ModelUnavailableException extends Exception {
  public ModelUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
