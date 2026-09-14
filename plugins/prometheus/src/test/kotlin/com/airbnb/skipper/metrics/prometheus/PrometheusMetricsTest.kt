package com.airbnb.skipper.metrics.prometheus

import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.model.snapshots.CounterSnapshot
import io.prometheus.metrics.model.snapshots.GaugeSnapshot
import io.prometheus.metrics.model.snapshots.HistogramSnapshot
import io.prometheus.metrics.model.snapshots.Labels
import io.prometheus.metrics.model.snapshots.MetricSnapshot
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PrometheusMetricsTest {
    private val registry = PrometheusRegistry()
    private val metrics = PrometheusMetrics(registry)

    private inline fun <reified S : MetricSnapshot> snapshot(prometheusName: String): S {
        val all = registry.scrape().toList()
        val match = all.singleOrNull { it.metadata.prometheusName == prometheusName }
        assertThat(match).describedAs("metric %s among %s", prometheusName, all.map { it.metadata.prometheusName }).isNotNull
        return match as S
    }

    @Test
    fun `counter is prefixed, underscored and labelled`() {
        metrics.counter(mapOf("result" to "error", "error" to "IOException"), "schedulerManager", "handledTasks").inc()
        metrics.counter(mapOf("result" to "success", "error" to ""), "schedulerManager", "handledTasks").inc(3)
        metrics.counter("leaseManager", "renewLeaseSucceeded").inc()

        val handled = snapshot<CounterSnapshot>("skipper_schedulerManager_handledTasks")
        assertThat(handled.dataPoints.map { it.labels to it.value }).containsExactlyInAnyOrder(
            Labels.of("error", "IOException", "result", "error") to 1.0,
            Labels.of("error", "", "result", "success") to 3.0,
        )
        val renewed = snapshot<CounterSnapshot>("skipper_leaseManager_renewLeaseSucceeded")
        assertThat(renewed.dataPoints.single().value).isEqualTo(1.0)
    }

    @Test
    fun `timer is a histogram in seconds`() {
        val timer = metrics.timer("mysqlWorkflowStore", "getPersistedSignal.latency")
        timer.update(Duration.ofMillis(250))
        timer.update(2, TimeUnit.SECONDS)
        timer.time().use { }
        assertThat(timer.time { "done" }).isEqualTo("done")

        val histogram = snapshot<HistogramSnapshot>("skipper_mysqlWorkflowStore_getPersistedSignal_latency_seconds")
        val point = histogram.dataPoints.single()
        assertThat(point.count).isEqualTo(4)
        assertThat(point.sum).isBetween(2.25, 2.35)
        assertThat(point.classicBuckets.size()).isEqualTo(PrometheusMetrics.DEFAULT_TIMER_BUCKETS.size + 1)
    }

    @Test
    fun `histogram records values with the configured buckets`() {
        val sized = PrometheusMetrics(registry, histogramBuckets = doubleArrayOf(10.0, 100.0))
        val histogram = sized.histogram("executor", "payloadSize")
        histogram.update(5)
        histogram.update(50L)

        val point = snapshot<HistogramSnapshot>("skipper_executor_payloadSize").dataPoints.single()
        assertThat(point.count).isEqualTo(2)
        assertThat(point.sum).isEqualTo(55.0)
        assertThat(point.classicBuckets.getUpperBound(0)).isEqualTo(10.0)
        assertThat(point.classicBuckets.getCount(0)).isEqualTo(1)
        assertThat(point.classicBuckets.getCount(1)).isEqualTo(1)
    }

    @Test
    fun `gauge reads the supplier on every scrape and keeps one series per label set`() {
        var backlog = 7L
        metrics.gauge(Supplier { backlog }, mapOf("shard" to "a"), "schedulerManager", "backlog")
        metrics.gauge(Supplier { 1L }, mapOf("shard" to "b"), "schedulerManager", "backlog")
        metrics.gauge(Supplier { 2L }, mapOf("shard" to "b"), "schedulerManager", "backlog")

        fun values() = snapshot<GaugeSnapshot>("skipper_schedulerManager_backlog").dataPoints.associate { it.labels.get("shard") to it.value }

        assertThat(values()).isEqualTo(mapOf("a" to 7.0, "b" to 2.0))
        backlog = 9L
        assertThat(values()).isEqualTo(mapOf("a" to 9.0, "b" to 2.0))
    }

    @Test
    fun `failing gauge supplier is omitted from the scrape`() {
        metrics.gauge(Supplier { throw IllegalStateException("boom") }, "scheduler", "failedTasksCount")
        metrics.gauge(Supplier { 3L }, "scheduler", "maxTasks")

        assertThat(snapshot<GaugeSnapshot>("skipper_scheduler_failedTasksCount").dataPoints).isEmpty()
        assertThat(snapshot<GaugeSnapshot>("skipper_scheduler_maxTasks").dataPoints.single().value).isEqualTo(3.0)
    }

    @Test
    fun `a later call with different tag keys maps onto the first label set instead of failing`() {
        metrics.counter(mapOf("result" to "ok"), "scheduler", "handledTasks").inc()
        metrics.counter(mapOf("other" to "x"), "scheduler", "handledTasks").inc()
        metrics.counter("scheduler", "handledTasks").inc()

        val points = snapshot<CounterSnapshot>("skipper_scheduler_handledTasks").dataPoints
        assertThat(points.map { it.labels to it.value }).containsExactlyInAnyOrder(
            Labels.of("result", "ok") to 1.0,
            Labels.of("result", "") to 2.0,
        )
    }

    @Test
    fun `names are sanitized for Prometheus`() {
        PrometheusMetrics(registry, prefix = "my-svc").counter(mapOf("error.type" to "x"), "1store", "op.latency").inc()

        val snapshot = snapshot<CounterSnapshot>("my_svc__1store_op_latency")
        assertThat(snapshot.dataPoints.single().labels.get("error_type")).isEqualTo("x")
    }

    @Test
    fun `the same metric is registered once across calls`() {
        repeat(3) { metrics.counter("a", "b").inc() }
        repeat(3) { metrics.timer("a", "t").update(1, TimeUnit.MILLISECONDS) }

        assertThat(snapshot<CounterSnapshot>("skipper_a_b").dataPoints.single().value).isEqualTo(3.0)
        assertThat(snapshot<HistogramSnapshot>("skipper_a_t_seconds").dataPoints.single().count).isEqualTo(3)
    }
}
