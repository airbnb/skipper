package com.airbnb.skipper

import com.airbnb.skipper.metrics.SkipperCounter
import com.airbnb.skipper.metrics.SkipperHistogram
import com.airbnb.skipper.metrics.SkipperTimer
import java.util.function.Supplier

/**
 * Helper for creating metrics emitted by Skipper. Implementations control how and where metrics are
 * published. The default implementation ([NoOpMetrics]) is a no-op to keep `common/skipper` free of
 * any environment-specific observability dependencies; host services can provide a custom
 * implementation that publishes to their own metrics registry.
 */
interface Metrics {
    fun counter(vararg names: String): SkipperCounter

    fun counter(
        tags: Map<String, String>,
        vararg names: String
    ): SkipperCounter

    fun timer(vararg names: String): SkipperTimer

    fun timer(
        tags: Map<String, String>,
        vararg names: String
    ): SkipperTimer

    fun histogram(vararg names: String): SkipperHistogram

    fun histogram(
        tags: Map<String, String>,
        vararg names: String
    ): SkipperHistogram

    fun gauge(
        gauge: Supplier<Long>,
        vararg names: String
    )

    fun gauge(
        gauge: Supplier<Long>,
        tags: Map<String, String>,
        vararg names: String
    )
}
