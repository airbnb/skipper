package com.airbnb.skipper.internal.testutils

import com.airbnb.skipper.NoOpMetrics
import com.airbnb.skipper.metrics.SkipperCounter

/** [com.airbnb.skipper.Metrics] recording the counters asked for, so a test can see which branch ran. */
class RecordingMetrics : NoOpMetrics() {
    val counters = mutableListOf<String>()

    override fun counter(
        tags: Map<String, String>,
        vararg names: String
    ): SkipperCounter {
        counters.add(names.joinToString("."))
        return super.counter(tags, *names)
    }
}
