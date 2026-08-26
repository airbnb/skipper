package com.airbnb.skipper

import com.airbnb.skipper.internal.PersistentRetryableError

/**
 * This exception will be surfaced to the client code when all retries for a given workflow have
 * been exhausted. It indicates that the workflow is in a waiting state and can be retried manually.
 *
 * The cause of this exception is typically the last retryable exception that occurred, which is a
 * [PersistentRetryableError].
 */
open class RetriesExhaustedError : RuntimeException {
    constructor(message: String) : super(message)

    // Faithful port of the Java ctor `RetriesExhaustedError(Throwable cause)` which did
    // `super(cause.getMessage(), cause)`. The param stays nullable to preserve the Java platform-type
    // signature (JVM descriptor unchanged: (Ljava/lang/Throwable;)) and to accept the nullable
    // Throwable? that existing skipper callers pass. `cause!!.message` reproduces Java's behavior
    // exactly: a null cause NPEs at message access, just as `cause.getMessage()` did.
    constructor(cause: Throwable?) : super(cause!!.message, cause)
}
