package com.airbnb.skipper.internal

import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.SkipperError
import com.airbnb.skipper.internal.serde.Serializable
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A persistent retryable error is the last error in a retry strategy that won't be converted to a
 * non-retryable error. This error is mostly used internally to bubble the retryable error up the
 * workflow execution stack.
 */
// Persisted Jackson type. Keeps the SAME FQCN so the @JsonTypeInfo(@class) discriminator on the
// SkipperError base ("com.airbnb.skipper.internal.PersistentRetryableError") is stable across the
// Java->Kotlin rename, and keeps @JsonInclude(NON_NULL) + @Serializable so the wire format is
// byte-identical to pre-port output. The @JsonCreator constructor param keeps the name `cause`
// (ParameterNamesModule infers it; the Java @JsonProperty had no explicit value).
@JsonInclude(JsonInclude.Include.NON_NULL)
@Serializable
class PersistentRetryableError
    @JsonCreator
    constructor(
        @JsonProperty cause: RetryableError
    ) : SkipperError(cause.message, cause) {
        // The original Java guarded `cause == null` AFTER calling super(cause.getMessage(), cause),
        // making the guard unreachable (a null cause already NPEs at cause.getMessage()). The Kotlin
        // non-null parameter type preserves the identical observable behavior: a null from Java NPEs at
        // the cause.message access in the super(...) delegation above, before the body runs.

        override fun equals(o: Any?): Boolean {
            if (o === this) return true
            if (o !is PersistentRetryableError) return false
            val other = o
            if (!other.canEqual(this)) return false
            return true
        }

        // Preserved from the original Java (Lombok's canEqual). It is part of the public/protected
        // ABI (protected boolean canEqual(Object)) and used by equals(); keeping it avoids an ABI break.
        protected open fun canEqual(other: Any?): Boolean {
            return other is PersistentRetryableError
        }

        override fun hashCode(): Int {
            val result = 1
            return result
        }
    }
