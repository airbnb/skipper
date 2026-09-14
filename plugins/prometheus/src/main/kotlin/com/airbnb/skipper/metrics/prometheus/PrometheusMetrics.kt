package com.airbnb.skipper.metrics.prometheus

import com.airbnb.skipper.Metrics
import com.airbnb.skipper.metrics.SkipperCounter
import com.airbnb.skipper.metrics.SkipperHistogram
import com.airbnb.skipper.metrics.SkipperTimer
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.core.metrics.GaugeWithCallback
import io.prometheus.metrics.core.metrics.Histogram
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.model.snapshots.Unit
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import org.slf4j.LoggerFactory

/**
 * [Metrics] implementation that registers collectors with a Prometheus [PrometheusRegistry]
 * (the 1.x `prometheus-metrics-core` client). Expose the registry with your existing scrape endpoint,
 * for example `prometheus-metrics-exporter-httpserver` or `prometheus-metrics-exporter-servlet-jakarta`.
 *
 * Skipper metric names become `<prefix>_<segment>_<segment>` with anything that is not a letter,
 * digit or underscore replaced by `_` (`skipper_mysqlWorkflowStore_persistSignal`); Skipper tags become
 * labels. Counters map to a Prometheus counter (`_total` is added on exposition), timers to a
 * histogram in seconds (`_seconds` suffix) with [timerBuckets], Skipper histograms to a histogram with
 * [histogramBuckets], and gauges to a callback gauge that reads the supplier on every scrape.
 *
 * Prometheus requires a fixed label-name set per metric name. Skipper uses a consistent tag set per
 * metric, so the first call for a name fixes its labels. Should a later call carry a different set,
 * unknown labels are dropped and missing ones filled with `""`, with a one-time warning, rather than
 * failing the caller.
 *
 * ```kotlin
 * config.metrics = ComponentFactory { PrometheusMetrics(PrometheusRegistry.defaultRegistry) }
 * ```
 */
class PrometheusMetrics
    @JvmOverloads
    constructor(
        val registry: PrometheusRegistry = PrometheusRegistry.defaultRegistry,
        private val prefix: String = DEFAULT_PREFIX,
        timerBuckets: DoubleArray = DEFAULT_TIMER_BUCKETS,
        histogramBuckets: DoubleArray = DEFAULT_HISTOGRAM_BUCKETS,
    ) : Metrics {
        private val timerBuckets = timerBuckets.copyOf()
        private val histogramBuckets = histogramBuckets.copyOf()

        private val counters = ConcurrentHashMap<String, Labelled<Counter>>()
        private val timers = ConcurrentHashMap<String, Labelled<Histogram>>()
        private val histograms = ConcurrentHashMap<String, Labelled<Histogram>>()
        private val gauges = ConcurrentHashMap<String, CallbackGauge>()

        override fun counter(vararg names: String): SkipperCounter = counter(emptyMap(), *names)

        override fun counter(
            tags: Map<String, String>,
            vararg names: String
        ): SkipperCounter {
            val name = metricName(names)
            val labelled =
                counters.computeIfAbsent(name) { n ->
                    Labelled(labelNames(tags)) { labels ->
                        Counter.builder().name(n).labelNames(*labels).help(help(names)).register(registry)
                    }
                }
            val child = labelled.metric.labelValues(*labelled.values(name, tags))
            return object : SkipperCounter {
                override fun inc() = child.inc()

                override fun inc(n: Long) = child.inc(n.toDouble())
            }
        }

        override fun timer(vararg names: String): SkipperTimer = timer(emptyMap(), *names)

        override fun timer(
            tags: Map<String, String>,
            vararg names: String
        ): SkipperTimer {
            val name = metricName(names)
            val labelled =
                timers.computeIfAbsent(name) { n ->
                    Labelled(labelNames(tags)) { labels ->
                        Histogram.builder()
                            .name(n)
                            .unit(Unit.SECONDS)
                            .classicUpperBounds(*timerBuckets)
                            .labelNames(*labels)
                            .help(help(names))
                            .register(registry)
                    }
                }
            val child = labelled.metric.labelValues(*labelled.values(name, tags))
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

                private fun record(nanos: Long) = child.observe(nanos / NANOS_PER_SECOND)
            }
        }

        override fun histogram(vararg names: String): SkipperHistogram = histogram(emptyMap(), *names)

        override fun histogram(
            tags: Map<String, String>,
            vararg names: String
        ): SkipperHistogram {
            val name = metricName(names)
            val labelled =
                histograms.computeIfAbsent(name) { n ->
                    Labelled(labelNames(tags)) { labels ->
                        Histogram.builder()
                            .name(n)
                            .classicUpperBounds(*histogramBuckets)
                            .labelNames(*labels)
                            .help(help(names))
                            .register(registry)
                    }
                }
            val child = labelled.metric.labelValues(*labelled.values(name, tags))
            return object : SkipperHistogram {
                override fun update(value: Int) = child.observe(value.toDouble())

                override fun update(value: Long) = child.observe(value.toDouble())
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
            val name = metricName(names)
            val callbackGauge =
                gauges.computeIfAbsent(name) { n ->
                    CallbackGauge(n, labelNames(tags), help(names))
                }
            callbackGauge.register(name, tags, gauge)
        }

        /**
         * One Prometheus metric per Skipper name. The label names are fixed by the first registration;
         * [values] maps any later tag set onto them.
         */
        private inner class Labelled<M>(
            private val labelNames: Array<String>,
            build: (Array<String>) -> M
        ) {
            val metric: M = build(labelNames)

            @Volatile
            private var warned = false

            fun values(
                name: String,
                tags: Map<String, String>
            ): Array<String> {
                val byLabel = tags.entries.associate { labelName(it.key) to it.value }
                if (!warned && byLabel.keys != labelNames.toSet()) {
                    warned = true
                    LOG.warn(
                        "Skipper metric {} was first registered with labels {} but is now reported with {}; " +
                            "unknown labels are dropped and missing ones left empty",
                        name,
                        labelNames.toList(),
                        byLabel.keys.sorted(),
                    )
                }
                return Array(labelNames.size) { byLabel[labelNames[it]] ?: "" }
            }
        }

        /** A callback gauge whose scrape reads every supplier registered under its name. */
        private inner class CallbackGauge(
            name: String,
            labelNames: Array<String>,
            help: String
        ) {
            private val suppliers = ConcurrentHashMap<List<String>, Supplier<Long>>()
            private val labelled =
                Labelled(labelNames) { labels ->
                    GaugeWithCallback.builder()
                        .name(name)
                        .labelNames(*labels)
                        .help(help)
                        .callback { cb ->
                            for ((labelValues, supplier) in suppliers) {
                                val value =
                                    try {
                                        supplier.get()
                                    } catch (e: Exception) {
                                        LOG.warn("Skipper gauge {} failed to sample; omitting it from this scrape", name, e)
                                        continue
                                    }
                                cb.call(value.toDouble(), *labelValues.toTypedArray())
                            }
                        }
                        .register(registry)
                }

            fun register(
                name: String,
                tags: Map<String, String>,
                supplier: Supplier<Long>
            ) {
                suppliers[labelled.values(name, tags).toList()] = supplier
            }
        }

        private fun metricName(names: Array<out String>): String {
            require(names.isNotEmpty()) { "A metric needs at least one name segment" }
            val joined = names.joinToString("_") { sanitize(it) }
            return if (prefix.isEmpty()) joined else "${sanitize(prefix)}_$joined"
        }

        private fun help(names: Array<out String>): String = "Skipper metric ${names.joinToString(".")}"

        private fun labelNames(tags: Map<String, String>): Array<String> = tags.keys.map { labelName(it) }.sorted().toTypedArray()

        private fun labelName(key: String): String = sanitize(key)

        private fun sanitize(segment: String): String {
            val cleaned = segment.replace(UNSAFE, "_")
            return if (cleaned.isNotEmpty() && cleaned[0].isDigit()) "_$cleaned" else cleaned
        }

        companion object {
            const val DEFAULT_PREFIX = "skipper"

            /** Latency buckets in seconds, 1 ms to 1 minute. Storage and scheduler calls sit in the low end. */
            @JvmField
            val DEFAULT_TIMER_BUCKETS: DoubleArray =
                doubleArrayOf(0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0)

            /** Value buckets for Skipper histograms (sizes and counts): powers of four from 1 to ~4 million. */
            @JvmField
            val DEFAULT_HISTOGRAM_BUCKETS: DoubleArray = DoubleArray(12) { Math.pow(4.0, it.toDouble()) }

            private const val NANOS_PER_SECOND = 1_000_000_000.0
            private val UNSAFE = Regex("[^a-zA-Z0-9_]")
            private val LOG = LoggerFactory.getLogger(PrometheusMetrics::class.java)
        }
    }
