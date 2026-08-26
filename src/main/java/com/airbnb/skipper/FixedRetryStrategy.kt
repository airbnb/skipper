package com.airbnb.skipper

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import java.time.Duration
import java.util.Optional

/** A retry strategy that retries a fixed number of times with a fixed delay between retries. */
// final class implementing the retained Java RetryStrategy interface (Kotlin implementing a Java
// interface is fine). @JsonCreator/@JsonProperty value type; delay/maxRetries are exposed as public
// `val` properties (consumers use the property form) whose generated getters keep their JVM names
// (getDelay/getMaxRetries) so the ABI is byte-identical. @JsonPropertyOrder locks the serialized
// field order to the creator param order (delay, maxRetries), matching the pre-port wire format.
@JsonPropertyOrder("delay", "maxRetries")
class FixedRetryStrategy
    @JsonCreator
    constructor(
        @JsonProperty("delay") delay: Duration?,
        @JsonProperty("maxRetries") maxRetries: Int
    ) : RetryStrategy {
        // Reproduces the original @NonNull guard verbatim: a null delay throws NPE with the exact
        // Lombok message (NOT IllegalArgumentException). The field stays non-null and serialized.
        @get:JsonProperty("delay")
        val delay: Duration =
            delay ?: throw NullPointerException("delay is marked non-null but is null")

        val maxRetries: Int = maxRetries

        override fun nextRetryDelay(retryCount: Int): Optional<Duration> {
            if (retryCount >= maxRetries) {
                return Optional.empty()
            }
            return Optional.of(delay)
        }

        override fun equals(o: Any?): Boolean {
            if (o === this) return true
            if (o !is FixedRetryStrategy) return false
            val other = o
            if (this.maxRetries != other.maxRetries) return false
            val thisDelay: Any? = this.delay
            val otherDelay: Any? = other.delay
            if (if (thisDelay == null) otherDelay != null else thisDelay != otherDelay) {
                return false
            }
            return true
        }

        override fun hashCode(): Int {
            val prime = 59
            var result = 1
            result = result * prime + this.maxRetries
            val delayValue: Any? = this.delay
            result = result * prime + (if (delayValue == null) 43 else delayValue.hashCode())
            return result
        }

        override fun toString(): String {
            return "FixedRetryStrategy(delay=" +
                this.delay +
                ", maxRetries=" +
                this.maxRetries +
                ")"
        }
    }
