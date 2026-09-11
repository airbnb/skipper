package com.airbnb.skipper.metrics.datadog

import com.airbnb.skipper.Metrics
import com.airbnb.skipper.metrics.SkipperCounter
import com.airbnb.skipper.metrics.SkipperHistogram
import com.airbnb.skipper.metrics.SkipperTimer
import com.timgroup.statsd.StatsDClient
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import org.slf4j.LoggerFactory

/**
 * [Metrics] implementation that reports through a DogStatsD [StatsDClient] to the Datadog agent.
 *
 * Skipper metric names are dotted paths under [prefix] (`skipper.mysqlWorkflowStore.persistSignal`),
 * and Skipper tags become `key:value` DogStatsD tags. Counters are emitted as `count`; timers and
 * histograms as `distribution` by default (see [ValueMode]), with timers recorded in milliseconds.
 *
 * DogStatsD is push-based, so a gauge registered with [gauge] is sampled on a daemon thread every
 * [gaugeInterval] and sent as a `gauge`. Call [close] on shutdown to stop that thread. The
 * [StatsDClient] is owned by the caller and is never closed here, so several components can share it.
 *
 * ```kotlin
 * val client = NonBlockingStatsDClientBuilder().hostname("localhost").port(8125).build()
 * config.metrics = ComponentFactory { DatadogMetrics(client) }
 * ```
 */
class DatadogMetrics
    @JvmOverloads
    constructor(
        private val client: StatsDClient,
        private val prefix: String = DEFAULT_PREFIX,
        private val timerMode: ValueMode = ValueMode.DISTRIBUTION,
        private val histogramMode: ValueMode = ValueMode.DISTRIBUTION,
        private val gaugeInterval: Duration = DEFAULT_GAUGE_INTERVAL,
    ) : Metrics, AutoCloseable {
        /** How a sampled value (timer duration or histogram observation) is sent to the agent. */
        enum class ValueMode {
            /** `distribution`: aggregated server-side with global percentiles. The default. */
            DISTRIBUTION,

            /** `histogram`: aggregated by the local agent, per host. */
            HISTOGRAM,
        }

        private val gauges = ConcurrentHashMap<GaugeKey, Supplier<Long>>()

        // Started lazily by the first gauge registration so a runtime that never registers a gauge
        // never starts a thread.
        @Volatile
        private var sampler: ScheduledExecutorService? = null

        override fun counter(vararg names: String): SkipperCounter = counter(emptyMap(), *names)

        override fun counter(
            tags: Map<String, String>,
            vararg names: String
        ): SkipperCounter {
            val name = metricName(names)
            val ddTags = ddTags(tags)
            return object : SkipperCounter {
                override fun inc() = client.count(name, 1L, *ddTags)

                override fun inc(n: Long) = client.count(name, n, *ddTags)
            }
        }

        override fun timer(vararg names: String): SkipperTimer = timer(emptyMap(), *names)

        override fun timer(
            tags: Map<String, String>,
            vararg names: String
        ): SkipperTimer {
            val name = metricName(names)
            val ddTags = ddTags(tags)
            return object : SkipperTimer {
                override fun update(duration: Duration) = record(duration.toNanos())

                override fun update(
                    duration: Long,
                    unit: TimeUnit
                ) = record(unit.toNanos(duration))

                override fun <T> time(event: Callable<T>): T {
                    val start = System.nanoTime()
                    try {
                        return event.call()
                    } finally {
                        record(System.nanoTime() - start)
                    }
                }

                override fun time(): SkipperTimer.Context {
                    val start = System.nanoTime()
                    return object : SkipperTimer.Context {
                        override fun stop(): Long {
                            val elapsed = System.nanoTime() - start
                            record(elapsed)
                            return elapsed
                        }

                        override fun close() {
                            stop()
                        }
                    }
                }

                private fun record(nanos: Long) = send(timerMode, name, nanos / NANOS_PER_MILLI, ddTags)
            }
        }

        override fun histogram(vararg names: String): SkipperHistogram = histogram(emptyMap(), *names)

        override fun histogram(
            tags: Map<String, String>,
            vararg names: String
        ): SkipperHistogram {
            val name = metricName(names)
            val ddTags = ddTags(tags)
            return object : SkipperHistogram {
                override fun update(value: Int) = send(histogramMode, name, value.toDouble(), ddTags)

                override fun update(value: Long) = send(histogramMode, name, value.toDouble(), ddTags)
            }
        }

        override fun gauge(
            gauge: Supplier<Long>,
            vararg names: String
        ) = gauge(gauge, emptyMap(), *names)

        override fun gauge(
            gauge: Supplier<Long>,
            tags: Map<String, String>,
            vararg names: String
        ) {
            gauges[GaugeKey(metricName(names), ddTags(tags).toList())] = gauge
            ensureSamplerStarted()
        }

        /**
         * Sample every registered gauge once and send the values. Runs on the sampler thread every
         * [gaugeInterval]; exposed so hosts can force a sample (for example right before shutdown).
         */
        fun sampleGauges() {
            for ((key, supplier) in gauges) {
                val value =
                    try {
                        supplier.get()
                    } catch (e: Exception) {
                        LOG.warn("Skipper gauge {} failed to sample; skipping this interval", key.name, e)
                        continue
                    }
                client.gauge(key.name, value, *key.tags.toTypedArray())
            }
        }

        /** Stops the gauge sampler thread. Does not close the [StatsDClient], which the caller owns. */
        override fun close() {
            sampler?.shutdownNow()
        }

        private fun ensureSamplerStarted() {
            if (sampler != null) return
            synchronized(this) {
                if (sampler != null) return
                val executor =
                    Executors.newSingleThreadScheduledExecutor { runnable ->
                        Thread(runnable, "skipper-datadog-gauges").apply { isDaemon = true }
                    }
                val periodMillis = gaugeInterval.toMillis()
                executor.scheduleAtFixedRate({ sampleGauges() }, periodMillis, periodMillis, TimeUnit.MILLISECONDS)
                sampler = executor
            }
        }

        private fun send(
            mode: ValueMode,
            name: String,
            value: Double,
            tags: Array<String>
        ) {
            when (mode) {
                ValueMode.DISTRIBUTION -> client.recordDistributionValue(name, value, *tags)
                ValueMode.HISTOGRAM -> client.recordHistogramValue(name, value, *tags)
            }
        }

        private fun metricName(names: Array<out String>): String {
            require(names.isNotEmpty()) { "A metric needs at least one name segment" }
            return if (prefix.isEmpty()) names.joinToString(".") else names.joinToString(".", prefix = "$prefix.")
        }

        private fun ddTags(tags: Map<String, String>): Array<String> = tags.entries.sortedBy { it.key }.map { "${it.key}:${it.value}" }.toTypedArray()

        private data class GaugeKey(val name: String, val tags: List<String>)

        companion object {
            const val DEFAULT_PREFIX = "skipper"

            @JvmField
            val DEFAULT_GAUGE_INTERVAL: Duration = Duration.ofSeconds(10)

            private const val NANOS_PER_MILLI = 1_000_000.0
            private val LOG = LoggerFactory.getLogger(DatadogMetrics::class.java)
        }
    }
