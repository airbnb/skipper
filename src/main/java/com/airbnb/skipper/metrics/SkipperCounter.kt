package com.airbnb.skipper.metrics

/**
 * A monotonic counter that can be incremented by one or by a specified amount.
 *
 * Implementations bridge to platform-specific metric backends (e.g. Dropwizard, Micrometer).
 * In open-source contexts the default [com.airbnb.skipper.NoOpMetrics] returns a no-op instance.
 */
interface SkipperCounter {
    /** Increment the counter by one. */
    fun inc()

    /** Increment the counter by [n]. */
    fun inc(n: Long)
}
