package com.airbnb.skipper.internal

/**
 * An exception that indicates an internal error in the Skipper library. This should be used for
 * unexpected errors that are not caused by client code.
 */
// Mirrors the three Java constructor overloads exactly so existing call sites
// (throw new InternalError(message) / (message, cause) / (cause)) keep compiling and the JVM
// descriptors stay byte-identical. The supertype stays RuntimeException (matching the Java
// baseline — this is NOT java.lang.InternalError, which is an Error).
class InternalError : RuntimeException {
    constructor(message: String) : super(message)

    constructor(message: String, cause: Throwable) : super(message, cause)

    constructor(cause: Throwable) : super(cause)
}
