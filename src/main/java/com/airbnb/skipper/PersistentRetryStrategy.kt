package com.airbnb.skipper

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Duration
import java.util.Optional

/**
 * A special type of retry strategy that is used to indicate that the retryable error should not be
 * converted to a non-retryable error upon exhaustion of the underlying retry strategy.
 *
 * Use this to wrap any existing [RetryStrategy] to ensure that the retryable error is treated as
 * persistent.
 */
// final class implementing the retained Java RetryStrategy interface. Nests another RetryStrategy
// (polymorphic @class). @JsonIgnore on the isPersistentRetry() override keeps it out of the wire
// format, matching the pre-port Java.
class PersistentRetryStrategy
    @JsonCreator
    constructor(
        @JsonProperty("retryStrategy") retryStrategy: RetryStrategy?
    ) : RetryStrategy {
        // Reproduces the original @NonNull guard verbatim: NPE (NOT IllegalArgumentException).
        @field:JsonProperty("retryStrategy")
        private val retryStrategy: RetryStrategy =
            retryStrategy ?: throw NullPointerException("retryStrategy is marked non-null but is null")

        override fun nextRetryDelay(retryCount: Int): Optional<Duration> {
            return this.retryStrategy.nextRetryDelay(retryCount)
        }

        @JsonIgnore
        override fun isPersistentRetry(): Boolean {
            return true
        }

        @JsonProperty
        fun getRetryStrategy(): RetryStrategy {
            return this.retryStrategy
        }

        override fun equals(o: Any?): Boolean {
            if (o === this) return true
            if (o !is PersistentRetryStrategy) return false
            val other = o
            val thisRetryStrategy: Any? = this.getRetryStrategy()
            val otherRetryStrategy: Any? = other.getRetryStrategy()
            if (if (thisRetryStrategy == null) {
                    otherRetryStrategy != null
                } else {
                    thisRetryStrategy != otherRetryStrategy
                }
            ) {
                return false
            }
            return true
        }

        override fun hashCode(): Int {
            val prime = 59
            var result = 1
            val retryStrategyHash: Any? = this.getRetryStrategy()
            result = result * prime + (if (retryStrategyHash == null) 43 else retryStrategyHash.hashCode())
            return result
        }

        override fun toString(): String {
            return "PersistentRetryStrategy(retryStrategy=" + this.getRetryStrategy() + ")"
        }

        companion object {
            @JvmStatic
            fun of(retryStrategy: RetryStrategy): PersistentRetryStrategy {
                return PersistentRetryStrategy(retryStrategy)
            }
        }
    }
