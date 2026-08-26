package com.airbnb.skipper

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import java.time.Duration
import java.util.Optional

/** A retry strategy that retries with an exponentially increasing delay between retries. */
// final class implementing the retained Java RetryStrategy interface. @JsonCreator value type;
// serialized field order follows the creator param order (initialDelay, maxRetries, multiplier,
// maxDelay), matching the pre-port Java wire format. This is locked in via @JsonPropertyOrder.
// maxRetries/multiplier serialize via their public getters (default Jackson auto-detection; this
// type has no @JsonAutoDetect override). initialDelay/maxDelay are exposed as public `val`
// properties (consumers use the property form, e.g. `.initialDelay`).
@JsonPropertyOrder("initialDelay", "maxRetries", "multiplier", "maxDelay")
class ExponentialRetryStrategy
    /**
     * Creates a new ExponentialRetryStrategy with the given parameters.
     *
     * @param initialDelay The initial delay between retries.
     * @param maxRetries The maximum number of retries.
     * @param multiplier The multiplier for the delay between retries.
     * @param maxDelay The maximum delay between retries. If the delay exceeds this value, it will be
     *   capped to this maximum delay.
     */
    @JsonCreator
    constructor(
        @JsonProperty("initialDelay") initialDelay: Duration?,
        @JsonProperty("maxRetries") maxRetries: Int,
        @JsonProperty("multiplier") multiplier: Double,
        @JsonProperty("maxDelay") maxDelay: Duration?
    ) : RetryStrategy {
        // Reproduces the original @NonNull guards verbatim: null initialDelay/maxDelay throw NPE
        // with the exact Lombok messages (NOT IllegalArgumentException).
        @get:JsonProperty("initialDelay")
        val initialDelay: Duration =
            initialDelay ?: throw NullPointerException("initialDelay is marked non-null but is null")

        @get:JsonProperty("maxDelay")
        val maxDelay: Duration =
            maxDelay ?: throw NullPointerException("maxDelay is marked non-null but is null")

        val maxRetries: Int = maxRetries

        val multiplier: Double = multiplier

        override fun nextRetryDelay(retryCount: Int): Optional<Duration> {
            if (retryCount >= maxRetries) {
                return Optional.empty()
            }
            var delayMillis = (initialDelay.toMillis() * Math.pow(multiplier, retryCount.toDouble())).toLong()
            delayMillis = Math.min(delayMillis, maxDelay.toMillis())
            return Optional.of(Duration.ofMillis(delayMillis))
        }

        override fun equals(o: Any?): Boolean {
            if (o === this) return true
            if (o !is ExponentialRetryStrategy) return false
            val other = o
            if (this.maxRetries != other.maxRetries) return false
            if (java.lang.Double.compare(this.multiplier, other.multiplier) != 0) return false
            val thisInitialDelay: Any? = this.initialDelay
            val otherInitialDelay: Any? = other.initialDelay
            if (if (thisInitialDelay == null) {
                    otherInitialDelay != null
                } else {
                    thisInitialDelay != otherInitialDelay
                }
            ) {
                return false
            }
            val thisMaxDelay: Any? = this.maxDelay
            val otherMaxDelay: Any? = other.maxDelay
            if (if (thisMaxDelay == null) {
                    otherMaxDelay != null
                } else {
                    thisMaxDelay != otherMaxDelay
                }
            ) {
                return false
            }
            return true
        }

        override fun hashCode(): Int {
            val prime = 59
            var result = 1
            result = result * prime + this.maxRetries
            val multiplierBits = java.lang.Double.doubleToLongBits(this.multiplier)
            result = result * prime + (multiplierBits ushr 32 xor multiplierBits).toInt()
            val initialDelayHash: Any? = this.initialDelay
            result = result * prime + (if (initialDelayHash == null) 43 else initialDelayHash.hashCode())
            val maxDelayHash: Any? = this.maxDelay
            result = result * prime + (if (maxDelayHash == null) 43 else maxDelayHash.hashCode())
            return result
        }

        override fun toString(): String {
            return "ExponentialRetryStrategy(initialDelay=" +
                this.initialDelay +
                ", maxDelay=" +
                this.maxDelay +
                ", maxRetries=" +
                this.maxRetries +
                ", multiplier=" +
                this.multiplier +
                ")"
        }
    }
