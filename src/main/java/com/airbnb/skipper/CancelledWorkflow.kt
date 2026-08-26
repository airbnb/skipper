package com.airbnb.skipper

import com.airbnb.skipper.internal.serde.Serializable
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Represents an error result when a workflow is cancelled by a process explicitly triggering the
 * cancellation for a provided reason.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Serializable
open class CancelledWorkflow
    @JsonCreator
    constructor(
        @JsonProperty("message") message: String?
    ) : SkipperError(message) {
        // Preserved from the original Java (Lombok @Value-style equals): type-only equality via
        // canEqual, constant hashCode. Value semantics unchanged.
        override fun equals(o: Any?): Boolean {
            if (o === this) return true
            if (o !is CancelledWorkflow) return false
            val other = o
            if (!other.canEqual(this)) return false
            return true
        }

        protected open fun canEqual(other: Any?): Boolean {
            return other is CancelledWorkflow
        }

        override fun hashCode(): Int {
            val result = 1
            return result
        }
    }
