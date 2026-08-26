package com.airbnb.skipper

import java.time.Duration

/**
 * Configuration class for specifying retry policy for actions. This class supports
 * configurable exponential backoff retry strategy parameters.
 *
 * @property initialInterval The initial interval before the first retry occurs. If the backoff
 * coefficient is set to 1.0, this interval is used for all retries. If null, a default value
 * must be defined at usage or a higher configuration level.
 *
 * @property backoffCoefficient The factor by which the retry interval is multiplied after each
 * retry attempt to control the rate of interval increase. Must be 1.0 or greater, where 1.0 means
 * a fixed interval equal to the initial interval. Defaults to 2.0 if not specified.
 *
 * @property maximumAttempts The maximum number of retry attempts before giving up. A value of 1
 * or more is required; if null, retries continue indefinitely until other stopping conditions are met.
 *
 * @property maximumInterval The maximum interval between retries. This serves as a cap to the exponential
 * backoff, ensuring that retry intervals do not increase beyond this duration. Typically set to a multiple
 * of the initial interval; if null, a default cap may apply based on system or application policy.
 *
 * @property doNotRetry A set of exception types (as fully qualified class names) that should not trigger
 * a retry when thrown. Useful for defining non-recoverable errors. If null or empty, retries are attempted
 * for all exceptions except those explicitly non-retryable by the system.
 */
data class RetryOptions(
    val initialInterval: Duration? = null,
    val backoffCoefficient: Double? = 2.0,
    val maximumAttempts: Int? = null,
    val maximumInterval: Duration? = null,
    val doNotRetry: Set<String>? = null
)
