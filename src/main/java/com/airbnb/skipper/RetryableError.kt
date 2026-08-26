package com.airbnb.skipper

import com.airbnb.skipper.internal.serde.Serializable
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Duration
import java.util.Objects
import java.util.Optional

/**
 * Represents an error that can be retried.
 *
 * These kind of errors will typically (but not exclusively) originate at the Action layer when the
 * action fails unexpectedly, Skipper will wrap the failure into a RetryableError.
 *
 * Although not common, the action or workflow layer might also explicitly raise a RetryableError if
 * needed. An explicit [retryDelay] is honored either way: at the workflow layer the thrown error is
 * used as-is, and at the action layer ActionErrorMapper preserves it when rewrapping with the
 * action's configured retryStrategy.
 */
// The serialized retry-strategy field is `retryStrategyValue` (pinned to JSON "retryStrategy") so
// the Kotlin property `retryStrategy` can keep returning Optional<RetryStrategy> (getRetryStrategy())
// exactly like the Java getter — consumers call `error.retryStrategy.orElse(...)`. `nextRetryDelay`
// is exposed as a method (getNextRetryDelay()) so consumers' `retryableError.getNextRetryDelay()`
// keeps resolving.
@JsonInclude(JsonInclude.Include.NON_NULL)
@Serializable
open class RetryableError : SkipperError {
    /** The strategy to use for retrying the process that failed. */
    @field:JsonProperty("retryStrategy")
    private val retryStrategyValue: RetryStrategy?

    /** The number of times the process has been retried. */
    @field:JsonProperty("retryCount")
    private val retryCount: Int?

    // Public (not private) so a thrower's explicit delay survives being rewrapped with an attached
    // retryStrategy -- see ActionErrorMapper.wrapInvocationException, which reads this to preserve
    // the delay while still tracking exhaustion via the action's configured strategy.
    //
    // When set, this fully overrides the attached retryStrategy's own per-attempt delay -- including
    // any cap the strategy applies, e.g. ExponentialRetryStrategy.maxDelay. The strategy is still
    // consulted for retryCount-based exhaustion, but nothing clamps this value to it: a strategy's
    // maxDelay is a property of its own formula, not a ceiling on delays supplied through this field.
    // An explicit delay derived from something outside the strategy (a rate limiter's response, a
    // Retry-After header) is the whole point of this field, so bounding it to what a config-time
    // strategy would have picked would defeat that. Bound it yourself before constructing this error
    // if the source you're deriving it from needs one.
    @field:JsonProperty("retryDelay")
    val retryDelay: Duration?

    @JsonCreator
    constructor(
        @JsonProperty("message") message: String?,
        @JsonProperty("cause") cause: SkipperError?,
        @JsonProperty("retryStrategy") retryStrategy: RetryStrategy?,
        @JsonProperty("retryCount") retryCount: Int,
        // Optional and defaulted so old checkpointed JSON (with no retryDelay field) still
        // deserializes. When set alongside a retryStrategy, getNextRetryDelay() below returns this
        // value directly while exhaustion is still decided from retryStrategy + retryCount.
        @JsonProperty("retryDelay") retryDelay: Duration? = null
    ) : super(message, cause) {
        this.retryStrategyValue = retryStrategy
        this.retryCount = retryCount
        this.retryDelay = retryDelay
    }

    constructor(message: String?, cause: SkipperError?, retryDelay: Duration?) : super(
        message,
        cause
    ) {
        Objects.requireNonNull(retryDelay, "retryDelay is required")
        this.retryStrategyValue = null
        this.retryCount = null
        this.retryDelay = retryDelay
    }

    constructor(message: String?, cause: Throwable?) : this(
        message,
        ApplicationError.fromException(cause),
        null,
        0
    )

    constructor(message: String?) : this(message, null, null, 0)

    val retryStrategy: Optional<RetryStrategy>
        get() = Optional.ofNullable(retryStrategyValue)

    /** Returns the time that Skipper should wait before retrying the operation that failed. */
    fun getNextRetryDelay(): Duration {
        if (retryStrategyValue == null && retryDelay == null) {
            throw IllegalStateException("retryStrategy or retryDelay must be set")
        }
        if (retryDelay != null) {
            return retryDelay
        }
        return retryStrategyValue!!
            .nextRetryDelay(retryCount!!)
            .orElseThrow {
                IllegalStateException(
                    "retryable error has exhausted all retries, " +
                        "error must be converted to non retryable"
                )
            }
    }

    // Preserved from the original Java (Lombok @Value-style equals): equality over retryCount,
    // getRetryStrategy() (the Optional), and retryDelay + type via canEqual. Value semantics
    // unchanged. Note the Java compared getRetryStrategy() (Optional), not the raw field.
    override fun equals(o: Any?): Boolean {
        if (o === this) return true
        if (o !is RetryableError) return false
        val other = o
        if (!other.canEqual(this)) return false
        val thisRetryCount: Any? = this.retryCount
        val otherRetryCount: Any? = other.retryCount
        if (if (thisRetryCount == null) {
                otherRetryCount != null
            } else {
                thisRetryCount != otherRetryCount
            }
        ) {
            return false
        }
        val thisRetryStrategy: Any? = this.retryStrategy
        val otherRetryStrategy: Any? = other.retryStrategy
        if (if (thisRetryStrategy == null) {
                otherRetryStrategy != null
            } else {
                thisRetryStrategy != otherRetryStrategy
            }
        ) {
            return false
        }
        val thisRetryDelay: Any? = this.retryDelay
        val otherRetryDelay: Any? = other.retryDelay
        if (if (thisRetryDelay == null) {
                otherRetryDelay != null
            } else {
                thisRetryDelay != otherRetryDelay
            }
        ) {
            return false
        }
        return true
    }

    protected open fun canEqual(other: Any?): Boolean {
        return other is RetryableError
    }

    override fun hashCode(): Int {
        val prime = 59
        var result = 1
        val retryCountHash: Any? = this.retryCount
        result = result * prime + (if (retryCountHash == null) 43 else retryCountHash.hashCode())
        val retryStrategyHash: Any? = this.retryStrategy
        result = result * prime + (if (retryStrategyHash == null) 43 else retryStrategyHash.hashCode())
        val retryDelayHash: Any? = this.retryDelay
        result = result * prime + (if (retryDelayHash == null) 43 else retryDelayHash.hashCode())
        return result
    }
}
