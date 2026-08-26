package com.airbnb.skipper.util

import io.opentracing.Span
import io.opentracing.SpanContext
import io.opentracing.Tracer
import io.opentracing.propagation.Format
import io.opentracing.propagation.TextMapAdapter
import io.opentracing.tag.Tags

/**
 * Encapsulates a collection of extra data intended to be associated with a request.
 *
 * @property dataBag A mutable map used to store and manipulate additional data related to the request.
 *                   This data may include but is not limited to trace identifiers and span contexts.
 */
data class ExtraRequestData(
    val dataBag: MutableMap<String, String> = mutableMapOf()
) {
    /**
     * Stores the current active span's context into the internal data map. This method uses the
     * provided tracer to inject the span context into the `dataBag` as a set of text-based key-value pairs.
     * This allows the span context to be serialized and propagated across process boundaries.
     *
     * @param tracer The tracer instance used to access the currently active span and to inject its context.
     */
    fun storeSpanContext(tracer: Tracer) {
        val span = tracer.activeSpan()
        if (span != null) {
            tracer.inject(span.context(), Format.Builtin.TEXT_MAP, TextMapAdapter(dataBag))
        }
    }

    /**
     * Extracts a span context from the internal data map. This method uses the provided tracer to
     * reconstruct a span context from the data stored in `dataBag`. If the data contains valid tracing
     * information, a new `SpanContext` object is returned, which can be used to continue the trace.
     *
     * @param tracer The tracer instance used to extract the span context from the stored text map.
     * @return The extracted `SpanContext`, or `null` if the data does not contain valid span information.
     */
    fun extractSpanContext(tracer: Tracer): SpanContext? {
        return tracer.extract(Format.Builtin.TEXT_MAP, TextMapAdapter(dataBag))
    }

    /**
     * Starts and returns a new span with the specified name, resource tag, and optional additional tags,
     * optionally as a child of an existing span context if one is available in the current context.
     *
     * This function can create spans in two ways:
     * 1. As a child of a span context stored in extraRequestData (default)
     * 2. As a child of the currently active span (when useActiveSpanAsParent = true)
     *
     * @param tracer The tracer object used to create spans and extract the current span context.
     * @param childSpanName The name of the new span, which should describe the operation it represents.
     * @param childSpanResourceTag A string tag providing more details about the resource or operation
     *        the span is tracking. This is used to enrich the span data for tracing analysis.
     * @param additionalTags A map of additional tags to be added to the span for enriched tracing context.
     *        Defaults to an empty map if not provided.
     * @param useActiveSpanAsParent If true, uses the tracer's active span as parent instead of extracting
     *        from extraRequestData. This is useful when creating nested spans where the parent is already
     *        active (e.g., action spans should be children of workflow spans). Defaults to false.
     * @param spanTagger Strategy used to apply the resource tag to the new span. Defaults to
     *        [SpanTagger.DEFAULT], which writes a standard OpenTracing `"resource"` string tag.
     *        Deployments that need to integrate with a proprietary tracing system can supply a
     *        custom [SpanTagger].
     * @return The newly started span, which is not yet finished. The caller is responsible for
     *         finishing the span after the tracked operation is completed.
     */
    @JvmOverloads
    fun startNewSpan(
        tracer: Tracer,
        childSpanName: String,
        childSpanResourceTag: String,
        additionalTags: Map<String, String> = emptyMap(),
        useActiveSpanAsParent: Boolean = false,
        spanTagger: SpanTagger = SpanTagger.DEFAULT
    ): Span {
        val spanBuilder = tracer
            .buildSpan(childSpanName)
            .withTag(Tags.SPAN_KIND.key, Tags.SPAN_KIND_SERVER)

        // Determine parent span strategy
        if (!useActiveSpanAsParent) {
            // Extract parent context from extraRequestData (for workflow spans)
            val spanContext = extractSpanContext(tracer)
            if (spanContext != null) {
                spanBuilder.asChildOf(spanContext)
            }
        }
        // If useActiveSpanAsParent is true, tracer.buildSpan() automatically
        // uses the active span as parent, so we don't need to do anything

        // Add additional tags to span builder
        additionalTags.forEach { (key, value) ->
            spanBuilder.withTag(key, value)
        }

        // start and activate span
        val span = spanBuilder.start()
        spanTagger.tagResource(span, childSpanResourceTag)
        return span
    }
}
