package com.airbnb.skipper

/**
 * Host-supplied serializer for the opaque request-context payload Skipper persists alongside
 * each workflow.
 *
 * Skipper is fully agnostic about what the payload looks like — it hands the in-memory `Any?`
 * to this plugin on write and reads it back on load. Host integrations downcast inside their
 * serde implementation to whatever concrete type they own.
 *
 * Pair this with a [RawRequestContextMiddleware] of the same shape: the middleware owns the
 * lifecycle hooks; the serde owns the wire format. Together they make the engine fully
 * type-blind about the request-context payload.
 *
 * Implementations must round-trip: `deserialize(serialize(ctx))` should produce a value that
 * the host's middleware can use indistinguishably from the original. Implementations should
 * tolerate `null` and unexpected types on the serialize side (e.g. by emitting empty bytes)
 * since Skipper's own tests and OSS deployments may pass payloads the host doesn't recognize.
 */
interface RequestContextSerde {
    /**
     * Serialize the opaque request-context payload to bytes for storage.
     *
     * Called once per workflow create / update. Returning an empty array is valid and means
     * "no payload" — [deserialize] will be called with the same bytes on read.
     */
    fun serialize(ctx: Any?): ByteArray

    /**
     * Deserialize the persisted bytes back into the opaque request-context payload.
     *
     * The return value is fed into [WorkflowInstance.getRequestContext] and then into the
     * host's [RawRequestContextMiddleware] hooks — Skipper itself does not inspect it.
     */
    fun deserialize(bytes: ByteArray): Any?

    companion object {
        /**
         * No-op serde for deployments that carry no per-request identity. Drops the payload on
         * write and reads back `null`.
         */
        @JvmField
        val NOOP: RequestContextSerde = object : RequestContextSerde {
            override fun serialize(ctx: Any?): ByteArray = ByteArray(0)

            override fun deserialize(bytes: ByteArray): Any? = null
        }
    }
}
