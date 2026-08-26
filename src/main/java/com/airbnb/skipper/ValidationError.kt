package com.airbnb.skipper

/**
 * Represents a validation error caused by an invalid input while creating or running a workflow
 * instance.
 *
 * An example of a validation error is when a required field is missing or when a value passed in as
 * workflow argument has an incorrect type.
 */
// Faithful port of the three Java constructors (verified against baseline javap):
//   ValidationError(String, Object...)
//   ValidationError(String, Throwable, Object...)
//   ValidationError(String, Throwable)
// The two String.format-based ctors preserve the Java message-formatting behavior exactly. `open`
// mirrors the non-final Java class (additive, ABI-safe).
open class ValidationError : RuntimeException {
    constructor(message: String, vararg args: Any?) : super(String.format(message, *args))

    constructor(
        message: String,
        cause: Throwable?,
        vararg args: Any?
    ) : super(String.format(message, *args), cause)

    constructor(message: String, cause: Throwable?) : super(message, cause)
}
