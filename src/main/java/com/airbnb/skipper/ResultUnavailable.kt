package com.airbnb.skipper

/** Signals the caller that the result cannot be obtained through using the current mechanism. */
open class ResultUnavailable(message: String) : RuntimeException(message)
