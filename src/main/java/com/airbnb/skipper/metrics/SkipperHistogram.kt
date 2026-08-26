package com.airbnb.skipper.metrics

/**
 * Records and tracks the distribution of values (e.g. payload sizes, queue depths).
 *
 * Implementations bridge to platform-specific metric backends (e.g. Dropwizard, Micrometer).
 * In open-source contexts the default [com.airbnb.skipper.NoOpMetrics] returns a no-op instance.
 */
interface SkipperHistogram {
    /** Record an [Int] value. */
    fun update(value: Int)

    /** Record a [Long] value. */
    fun update(value: Long)
}
