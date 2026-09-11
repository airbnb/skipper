package com.airbnb.skipper.metrics.datadog

import com.timgroup.statsd.NoOpStatsDClient
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class DatadogMetricsTest {
    /** Records every emission instead of sending it. */
    private class RecordingClient : NoOpStatsDClient() {
        data class Emission(val type: String, val name: String, val value: Double, val tags: List<String>)

        val emissions = mutableListOf<Emission>()

        override fun count(
            aspect: String,
            delta: Long,
            vararg tags: String
        ) {
            emissions += Emission("count", aspect, delta.toDouble(), tags.toList())
        }

        override fun recordDistributionValue(
            aspect: String,
            value: Double,
            vararg tags: String
        ) {
            emissions += Emission("distribution", aspect, value, tags.toList())
        }

        override fun recordHistogramValue(
            aspect: String,
            value: Double,
            vararg tags: String
        ) {
            emissions += Emission("histogram", aspect, value, tags.toList())
        }

        override fun gauge(
            aspect: String,
            value: Long,
            vararg tags: String
        ) {
            emissions += Emission("gauge", aspect, value.toDouble(), tags.toList())
        }

        fun only() = emissions.single()
    }

    private val client = RecordingClient()
    private val metrics = DatadogMetrics(client)

    @AfterEach
    fun tearDown() = metrics.close()

    @Test
    fun `counter is prefixed, dotted and tagged`() {
        metrics.counter(mapOf("result" to "error", "error" to "IOException"), "schedulerManager", "handledTasks").inc()
        metrics.counter("leaseManager", "renewLeaseSucceeded").inc(3)

        assertThat(client.emissions).containsExactly(
            RecordingClient.Emission("count", "skipper.schedulerManager.handledTasks", 1.0, listOf("error:IOException", "result:error")),
            RecordingClient.Emission("count", "skipper.leaseManager.renewLeaseSucceeded", 3.0, emptyList()),
        )
    }

    @Test
    fun `timer records milliseconds as a distribution by default`() {
        val timer = metrics.timer("mysqlWorkflowStore", "persistSignal")
        timer.update(Duration.ofMillis(250))
        timer.update(2, TimeUnit.SECONDS)

        assertThat(client.emissions.map { it.type }).containsOnly("distribution")
        assertThat(client.emissions.map { it.name }).containsOnly("skipper.mysqlWorkflowStore.persistSignal")
        assertThat(client.emissions.map { it.value }).containsExactly(250.0, 2000.0)
    }

    @Test
    fun `timer context and callable record the elapsed time`() {
        val timer = metrics.timer("store", "op")
        timer.time().use { Thread.sleep(5) }
        val result = timer.time { "done" }

        assertThat(result).isEqualTo("done")
        assertThat(client.emissions).hasSize(2)
        assertThat(client.emissions[0].value).isGreaterThanOrEqualTo(5.0)
    }

    @Test
    fun `histogram mode is configurable`() {
        val hostMetrics = DatadogMetrics(client, histogramMode = DatadogMetrics.ValueMode.HISTOGRAM)
        hostMetrics.histogram("executor", "payloadSize").update(42)

        assertThat(client.only()).isEqualTo(RecordingClient.Emission("histogram", "skipper.executor.payloadSize", 42.0, emptyList()))
    }

    @Test
    fun `gauge is sampled from its supplier`() {
        var backlog = 7L
        metrics.gauge(Supplier { backlog }, mapOf("shard" to "a"), "schedulerManager", "backlog")

        metrics.sampleGauges()
        backlog = 9L
        metrics.sampleGauges()

        assertThat(client.emissions).containsExactly(
            RecordingClient.Emission("gauge", "skipper.schedulerManager.backlog", 7.0, listOf("shard:a")),
            RecordingClient.Emission("gauge", "skipper.schedulerManager.backlog", 9.0, listOf("shard:a")),
        )
    }

    @Test
    fun `re-registering a gauge replaces the supplier and a failing supplier is skipped`() {
        metrics.gauge(Supplier { 1L }, "scheduler", "maxTasks")
        metrics.gauge(Supplier { 2L }, "scheduler", "maxTasks")
        metrics.gauge(Supplier { throw IllegalStateException("boom") }, "scheduler", "failedTasksCount")

        metrics.sampleGauges()

        assertThat(client.emissions).containsExactly(
            RecordingClient.Emission("gauge", "skipper.scheduler.maxTasks", 2.0, emptyList()),
        )
    }

    @Test
    fun `gauges are sampled on a schedule until closed`() {
        val fast = DatadogMetrics(client, gaugeInterval = Duration.ofMillis(20))
        fast.gauge(Supplier { 1L }, "scheduler", "maxTasks")

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (client.emissions.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        fast.close()

        assertThat(client.emissions).isNotEmpty
        assertThat(client.emissions.first().name).isEqualTo("skipper.scheduler.maxTasks")
    }

    @Test
    fun `empty prefix emits bare names`() {
        DatadogMetrics(client, prefix = "").counter("a", "b").inc()

        assertThat(client.only().name).isEqualTo("a.b")
    }
}
