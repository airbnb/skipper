package com.airbnb.skipper.util

import io.opentracing.Span
import io.opentracing.tag.StringTag

/**
 * Strategy for applying the "resource" tag (and similar deployment-specific span tags) to a
 * tracing [Span].
 *
 * The default implementation writes the OpenTracing-standard `"resource"` string tag, which is
 * sufficient for any tracer that follows OpenTracing conventions. Hosts that integrate with a
 * proprietary tracing system can plug in their own implementation
 * via [com.airbnb.skipper.SkipperConfig.spanTagger] without coupling `common/skipper` to the
 * proprietary library.
 */
fun interface SpanTagger {
    /**
     * Applies the resource tag to [span] using [resource] as the value.
     */
    fun tagResource(
        span: Span,
        resource: String
    )

    companion object {
        /** OpenTracing-only default that writes `span.resource` via [StringTag]. */
        @JvmField
        val DEFAULT: SpanTagger = object : SpanTagger {
            private val resourceTag = StringTag("resource")

            override fun tagResource(
                span: Span,
                resource: String
            ) {
                resourceTag.set(span, resource)
            }
        }
    }
}
