package com.airbnb.skipper.util

import io.opentracing.mock.MockSpan
import io.opentracing.mock.MockTracer
import java.util.Objects
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertNull

class ExtraRequestDataTest {
    private lateinit var mockTracer: MockTracer

    @Before
    fun init() {
        mockTracer = MockTracer()
        mockTracer.reset()
    }

    @Test
    fun storeExtract() {
        val span = mockTracer.buildSpan("root").start()
        val scope = mockTracer.activateSpan(span)

        val extraRequestData = ExtraRequestData()
        extraRequestData.storeSpanContext(mockTracer)
        val spanContext = extraRequestData.extractSpanContext(mockTracer)

        span.finish()
        scope.close()

        assertEquals(
            span.context().toTraceId(),
            Objects.requireNonNull(spanContext)?.toTraceId()
        )
        assertEquals(span.context().toSpanId(), spanContext?.toSpanId())
    }

    @Test
    fun storeExtract_inactiveSpan() {
        val extraRequestData = ExtraRequestData()
        extraRequestData.storeSpanContext(mockTracer)
        val spanContext = extraRequestData.extractSpanContext(mockTracer)
        assertNull(spanContext)
    }

    @Test
    fun storeExtract_emptyDataBag() {
        val spanContext = ExtraRequestData().extractSpanContext(mockTracer)
        assertNull(spanContext)
    }

    @Test
    fun startNewSpan() {
        val span = mockTracer.buildSpan("root").start()
        val scope = mockTracer.activateSpan(span)
        val extraRequestData = ExtraRequestData()
        extraRequestData.storeSpanContext(mockTracer)
        span.finish()
        scope.close()

        val resultSpan = extraRequestData.startNewSpan(mockTracer, "child", "resource") as MockSpan
        assertEquals("child", resultSpan.operationName())
        assertEquals(span.context().traceId(), resultSpan.context().traceId())
        assertEquals(span.context().spanId(), resultSpan.parentId())
    }
}
