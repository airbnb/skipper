package com.airbnb.skipper.internal

import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.InMemoryFeatureGate
import com.airbnb.skipper.NoOpMetrics
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.metrics.SkipperCounter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test

/**
 * Unit tests for [VersionGateEnforcer]. The replay path through `version()` is tested in
 * `BaseWorkflowIntegTest.TestVersionGate`.
 */
class VersionGateEnforcerTest {
    /** [Metrics] that records the tags/names of each counter it hands out and counts `inc()`. */
    private class RecordingMetrics : NoOpMetrics() {
        val counters = mutableListOf<RecordingCounter>()

        override fun counter(
            tags: Map<String, String>,
            vararg names: String
        ): SkipperCounter = RecordingCounter(tags, names.toList()).also { counters.add(it) }

        class RecordingCounter(
            val tags: Map<String, String>,
            val names: List<String>
        ) : SkipperCounter {
            var increments = 0
                private set

            override fun inc() {
                increments++
            }

            override fun inc(n: Long) {
                increments += n.toInt()
            }
        }
    }

    private fun enforcer(
        enforce: Boolean,
        metrics: RecordingMetrics = RecordingMetrics()
    ): Pair<VersionGateEnforcer, RecordingMetrics> =
        VersionGateEnforcer(InMemoryFeatureGate(mapOf(FeatureGate.Keys.ENFORCE_VERSION_GATE_MIN_VERSION to enforce)), metrics) to metrics

    @Test
    fun `in-range version passes with no metric`() {
        val (enforcer, metrics) = enforcer(enforce = true)
        enforcer.enforce("wf-1", "change", storedVersion = 2, minVersion = 1, maxVersion = 3)
        assertThat(metrics.counters).isEmpty()
    }

    @Test
    fun `both range boundaries are in range`() {
        val (enforcer, metrics) = enforcer(enforce = true)
        enforcer.enforce("wf-1", "change", storedVersion = 1, minVersion = 1, maxVersion = 3)
        enforcer.enforce("wf-1", "change", storedVersion = 3, minVersion = 1, maxVersion = 3)
        assertThat(metrics.counters).isEmpty()
    }

    @Test
    fun `stored below minVersion throws NonRetryableError when enforcement enabled`() {
        val (enforcer, metrics) = enforcer(enforce = true)
        val thrown = catchThrowable {
            enforcer.enforce("wf-42", "payment-migration", storedVersion = 1, minVersion = 2, maxVersion = 2)
        }
        assertThat(thrown).isInstanceOf(NonRetryableError::class.java)
        assertThat(thrown.message)
            .contains("payment-migration")
            .contains("wf-42")
            .contains("version 1")
            .contains("2..2")

        assertThat(metrics.counters).hasSize(1)
        val counter = metrics.counters.single()
        assertThat(counter.increments).isEqualTo(1)
        assertThat(counter.names).containsExactly("versionGate", "storedVersionOutOfRange")
        assertThat(counter.tags)
            .containsEntry("changeId", "payment-migration")
            .containsEntry("direction", "below_min")
            .containsEntry("enforced", "true")
    }

    @Test
    fun `stored above maxVersion throws NonRetryableError when enforcement enabled (rollback)`() {
        val (enforcer, metrics) = enforcer(enforce = true)
        assertThatThrownBy {
            enforcer.enforce("wf-7", "rolled-back", storedVersion = 3, minVersion = 1, maxVersion = 2)
        }
            .isInstanceOf(NonRetryableError::class.java)
            .hasMessageContaining("version 3")
            .hasMessageContaining("1..2")

        val counter = metrics.counters.single()
        assertThat(counter.tags)
            .containsEntry("direction", "above_max")
            .containsEntry("enforced", "true")
    }

    @Test
    fun `stored below minVersion is detect-only when enforcement disabled`() {
        val (enforcer, metrics) = enforcer(enforce = false)
        enforcer.enforce("wf-9", "change", storedVersion = 1, minVersion = 2, maxVersion = 2)

        val counter = metrics.counters.single()
        assertThat(counter.increments).isEqualTo(1)
        assertThat(counter.tags)
            .containsEntry("direction", "below_min")
            .containsEntry("enforced", "false")
    }

    @Test
    fun `stored above maxVersion is detect-only when enforcement disabled`() {
        val (enforcer, metrics) = enforcer(enforce = false)
        enforcer.enforce("wf-9", "change", storedVersion = 5, minVersion = 1, maxVersion = 3)

        val counter = metrics.counters.single()
        assertThat(counter.tags)
            .containsEntry("direction", "above_max")
            .containsEntry("enforced", "false")
    }

    @Test
    fun `enforcement is off unless an app enables it`() {
        assertThat(InMemoryFeatureGate().isEnabled(FeatureGate.Keys.ENFORCE_VERSION_GATE_MIN_VERSION)).isFalse()
    }
}
