package com.airbnb.skipper;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Duration;
import java.util.Optional;

@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public interface RetryStrategy {

  /**
   * Returns the next retry delay for the given retry count.
   *
   * @param retryCount The current retry count.
   * @return The next retry delay, or empty Optional if the maximum number of retries has been
   *     reached and the operation should not be retried anymore.
   */
  Optional<Duration> nextRetryDelay(int retryCount);

  /**
   * A persistent retry strategy is one that will retry indefinitely until the operation succeeds. A
   * non-persistent retry strategy will stop retrying after a certain number of attempts and will
   * convert the retryable error to a non-retryable error.
   */
  @JsonIgnore
  default boolean isPersistentRetry() {
    return false;
  }
}
