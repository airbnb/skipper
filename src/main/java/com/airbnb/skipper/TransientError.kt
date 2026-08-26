package com.airbnb.skipper

/**
 * Represent an error that happened during workflow execution that will be retried by the workflow.
 */
open class TransientError(message: String) : RuntimeException(message)
