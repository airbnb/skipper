package com.airbnb.skipper

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import java.time.Duration

/**
 * Represents a retryable error that occurs when multiple processes are trying to update the same
 * resource at the same time. When one of them wins and increases the internal version of the
 * resource, it cases the other processes to fail with this error.
 */
// Unlike RetryableError, this subtype has no own @JsonCreator, so Jackson's default property
// ordering for the concrete Kotlin type placed `message` earlier than the original Java did.
// @JsonPropertyOrder pins the exact pre-port Java wire order so persisted OptimisticLockingError
// JSON stays byte-identical (verified by the backward-compat test). Wire-neutral pin; no field/behavior change.
@JsonPropertyOrder("stackTrace", "type", "message", "retryStrategy", "retryCount")
open class OptimisticLockingError(message: String?) :
    RetryableError(message, null, FixedRetryStrategy(Duration.ZERO, 10), 0)
