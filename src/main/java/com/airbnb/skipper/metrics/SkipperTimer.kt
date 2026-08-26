package com.airbnb.skipper.metrics

import java.io.Closeable
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * Measures and records the duration of events.
 *
 * Typical usage is via the [Context] returned by [time], which can be used in a
 * try-with-resources / `use` block:
 * ```
 * metrics.timer("latency").time().use {
 *     // timed work
 * }
 * ```
 *
 * Implementations bridge to platform-specific metric backends (e.g. Dropwizard, Micrometer).
 * In open-source contexts the default [com.airbnb.skipper.NoOpMetrics] returns a no-op instance.
 */
interface SkipperTimer {
    /** Record a duration expressed as a [Duration]. */
    fun update(duration: Duration)

    /** Record a duration expressed in the given [unit]. */
    fun update(
        duration: Long,
        unit: TimeUnit
    )

    /** Time the execution of [event] and return its result. */
    @Throws(Exception::class)
    fun <T> time(event: Callable<T>): T

    /** Start a new timing [Context]. Call [Context.stop] or [Context.close] to record the elapsed time. */
    fun time(): Context

    /** A running timer context that records elapsed time on [stop] or [close]. */
    interface Context : Closeable {
        /** Stop the timer and return the elapsed time in nanoseconds. */
        fun stop(): Long

        /** Stop the timer (alias for [stop] that satisfies [Closeable]). */
        override fun close()
    }
}
