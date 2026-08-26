package com.airbnb.skipper

/**
 * Thrown when dependency injection fails, such as when a type cannot be instantiated or a field
 * cannot be injected.
 */
class InjectionException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}
