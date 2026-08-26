package com.airbnb.skipper

import com.airbnb.skipper.metrics.SkipperCounter
import com.airbnb.skipper.metrics.SkipperHistogram
import com.airbnb.skipper.metrics.SkipperTimer
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * Default [Metrics] implementation that discards all recorded values. Used when Skipper runs
 * outside of any specific observability infrastructure (e.g. in open-source contexts or tests).
 */
open class NoOpMetrics : Metrics {
    override fun counter(vararg names: String): SkipperCounter = NOOP_COUNTER

    override fun counter(
        tags: Map<String, String>,
        vararg names: String
    ): SkipperCounter = NOOP_COUNTER

    override fun timer(vararg names: String): SkipperTimer = NOOP_TIMER

    override fun timer(
        tags: Map<String, String>,
        vararg names: String
    ): SkipperTimer = NOOP_TIMER

    override fun histogram(vararg names: String): SkipperHistogram = NOOP_HISTOGRAM

    override fun histogram(
        tags: Map<String, String>,
        vararg names: String
    ): SkipperHistogram = NOOP_HISTOGRAM

    override fun gauge(
        gauge: Supplier<Long>,
        vararg names: String
    ) {}

    override fun gauge(
        gauge: Supplier<Long>,
        tags: Map<String, String>,
        vararg names: String
    ) {}

    companion object {
        @JvmField
        val INSTANCE: NoOpMetrics = NoOpMetrics()

        private val NOOP_COUNTER: SkipperCounter =
            object : SkipperCounter {
                override fun inc() {}

                override fun inc(n: Long) {}
            }

        private val NOOP_CONTEXT: SkipperTimer.Context =
            object : SkipperTimer.Context {
                override fun stop(): Long = 0L

                override fun close() {}
            }

        private val NOOP_TIMER: SkipperTimer =
            object : SkipperTimer {
                override fun update(duration: Duration) {}

                override fun update(
                    duration: Long,
                    unit: TimeUnit
                ) {}

                @Throws(Exception::class)
                override fun <T> time(event: Callable<T>): T = event.call()

                override fun time(): SkipperTimer.Context = NOOP_CONTEXT
            }

        private val NOOP_HISTOGRAM: SkipperHistogram =
            object : SkipperHistogram {
                override fun update(value: Int) {}

                override fun update(value: Long) {}
            }
    }
}
