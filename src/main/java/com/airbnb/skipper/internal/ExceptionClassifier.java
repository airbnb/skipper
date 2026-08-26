package com.airbnb.skipper.internal;

public interface ExceptionClassifier {

  /**
   * Returns true if the given throwable should be considered a retryable error, false otherwise.
   *
   * @param throwable the throwable to classify
   * @return true if the given throwable should be considered a retryable error, false otherwise.
   */
  boolean isRetryable(Throwable throwable);
}
