package com.airbnb.skipper

import com.airbnb.skipper.internal.serde.Serializable
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Any error thrown by the workflow client code that is not a [SkipperError] will be converted to
 * this type of error.
 */
// Faithful port: `type`/`message`/`cause` are NOT re-declared as new serialized fields (that would
// collide with the base's fields). The base's protected `type`/`serializedMessage` are re-assigned
// to the WRAPPED error's values, exactly as the original Java did via field shadowing (the
// subclass `type` held error.getClass().getName(), not "ApplicationError"). `cause` is overridden
// covariantly to preserve the Java getCause():ApplicationError ABI.
@Serializable
open class ApplicationError : SkipperError {
    private constructor(error: Throwable, cause: ApplicationError?) : super(error.message, cause) {
        this.type = error.javaClass.name
        this.serializedMessage = error.message
    }

    @JsonCreator
    constructor(
        @JsonProperty("type") type: String?,
        @JsonProperty("message") message: String?,
        @JsonProperty("cause") cause: ApplicationError?
    ) : super(message, cause) {
        this.type = type
        this.serializedMessage = message
    }

    override val cause: ApplicationError?
        get() = super.cause as ApplicationError?

    companion object {
        /**
         * Creates a WrappedError from an exception.
         *
         * This will recursively wrap the exception and all its causes.
         *
         * @param error The exception to wrap.
         * @return The wrapped error.
         */
        @JvmStatic
        fun fromException(error: Throwable?): ApplicationError? {
            if (error is ApplicationError) {
                return error
            }
            if (error == null) {
                return null
            }
            val newError = ApplicationError(error, fromException(error.cause))
            newError.setStackTrace(error.stackTrace)
            return newError
        }
    }
}
