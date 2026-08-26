package com.airbnb.skipper

import com.airbnb.skipper.internal.serde.Serializable
import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.Arrays

/** Represents any type of workflow execution error. */
// Persisted polymorphic Jackson base. Reproduces the Java wire format byte-for-byte:
// - @JsonTypeInfo(use=CLASS, property="@class") embeds the concrete FQCN. A Kotlin port keeps the
//   same FQCN, so the discriminator is stable across the rename.
// - @JsonAutoDetect(all NONE) disables getter/setter/creator/field auto-detection so ONLY the
//   explicitly-@JsonProperty backing fields serialize (never the inherited Throwable getters).
// - `cause` is an `override val` so Kotlin/Java consumers keep `.cause`/getCause() returning a
//   SkipperError (covariant over Throwable.getCause()). `message`/`stackTrace` cannot reuse the
//   Throwable names as Kotlin properties (visibility/type collisions), so their VALUES live in
//   distinct backing fields pinned to the "message"/"stackTrace" JSON keys; Throwable's own message
//   (set via super) holds the same value, so `.message` returns identical data.
@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE,
    creatorVisibility = JsonAutoDetect.Visibility.NONE
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@Serializable
abstract class SkipperError(
    message: String?,
    cause: SkipperError?
) : RuntimeException(message, cause) {
    // Field DECLARATION order (clazz, stackTrace, type, message, cause) is load-bearing: Jackson
    // serializes @JsonCreator params first (in creator order), then the remaining fields in
    // declaration order. This exact order reproduces the pre-port Java wire format for every
    // subtype byte-for-byte (verified by the backward-compat tests + the pre-existing PersistentRetryableError
    // backward-compat test), so persisted data stays readable.
    @field:JsonProperty("clazz")
    protected var clazz: String? = null

    @field:JsonProperty("stackTrace")
    protected var serializedStackTrace: MutableList<StackTraceElement> =
        ArrayList(Arrays.asList(*(this as Throwable).stackTrace))

    // Public getter getType() (external Skipper consumers call appError.getType()); the setter is
    // protected so subclasses (ApplicationError) can re-assign it to the wrapped error's class name.
    @field:JsonProperty("type")
    var type: String? = this.javaClass.name
        protected set

    @field:JsonProperty("message")
    protected var serializedMessage: String? = message

    @field:JsonProperty("cause")
    override val cause: SkipperError? = cause

    constructor(message: String?) : this(message, null)

    override fun setStackTrace(stackTrace: Array<StackTraceElement>) {
        super.setStackTrace(stackTrace)
        this.serializedStackTrace = ArrayList(Arrays.asList(*stackTrace))
    }
}
