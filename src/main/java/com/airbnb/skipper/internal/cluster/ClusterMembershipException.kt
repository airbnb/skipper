package com.airbnb.skipper.internal.cluster

/** Exception thrown when cluster membership operations fail. */
open class ClusterMembershipException : RuntimeException {
    constructor(message: String) : super(message)

    constructor(message: String, cause: Throwable) : super(message, cause)
}
