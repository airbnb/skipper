package com.airbnb.skipper.internal;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Default implementation of {@link ExceptionClassifier}.
 *
 * <p>This classifier handles generic JDK exception types only:
 *
 * <ul>
 *   <li>Unwraps {@link ExecutionException} and {@link CompletionException} to classify the
 *       underlying cause.
 *   <li>Treats {@link TimeoutException} as retryable.
 *   <li>Everything else is non-retryable.
 * </ul>
 *
 * <p>Custom classifiers should either extend this class or use it via composition to ensure that
 * exception unwrapping and timeout handling are applied correctly — for example, to additionally
 * classify environment-specific service exceptions (backpressure, rate-limiting, etc.).
 */
public class DefaultExceptionClassifier implements ExceptionClassifier {
  @Override
  public boolean isRetryable(Throwable cause) {
    Throwable error = unwrapError(cause);
    if (error instanceof TimeoutException) {
      return true;
    }
    return false;
  }

  protected Throwable unwrapError(Throwable error) {
    if (error instanceof ExecutionException || error instanceof CompletionException) {
      return error.getCause();
    }
    return error;
  }
}
