package com.airbnb.skipper

import com.airbnb.skipper.internal.serde.Serializable
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Represents an error that will not be retried and will move the workflow to a failed state.
 *
 * This error might be raised by the client code explicitly either on the Action layer or Workflow
 * layer when the app code knows up front that the error is not recoverable, although it is
 * recommended to just use it on unexpected type of errors.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Serializable
open class NonRetryableError : SkipperError {
    constructor(message: String?) : super(message)

    @JsonCreator
    constructor(
        @JsonProperty("message") message: String?,
        @JsonProperty("cause") cause: SkipperError?,
        @JsonProperty("stackTrace") stackTrace: List<StackTraceElement>
    ) : super(message, cause) {
        if (stackTrace.isNotEmpty()) {
            this.serializedStackTrace = stackTrace.toMutableList()
            val stackTraceElements = arrayOfNulls<StackTraceElement>(stackTrace.size)
            for (i in stackTrace.indices) {
                stackTraceElements[i] = stackTrace[i]
            }
            @Suppress("UNCHECKED_CAST")
            this.setStackTrace(stackTraceElements as Array<StackTraceElement>)
        }
    }

    constructor(message: String?, cause: SkipperError?) : this(message, cause, ArrayList())

    /**
     * Creates a new NonRetryableError with the given message and cause.
     *
     * @param message The error message.
     * @param cause The cause of the error. This will be converted to an ApplicationError.
     */
    constructor(message: String?, cause: Throwable?) : this(
        message,
        ApplicationError.fromException(cause)
    )

    // Preserved from the original Java (Lombok @Value-style equals): type-only equality via
    // canEqual, constant hashCode. Value semantics unchanged.
    override fun equals(o: Any?): Boolean {
        if (o === this) return true
        if (o !is NonRetryableError) return false
        val other = o
        if (!other.canEqual(this)) return false
        return true
    }

    protected open fun canEqual(other: Any?): Boolean {
        return other is NonRetryableError
    }

    override fun hashCode(): Int {
        val result = 1
        return result
    }
}
