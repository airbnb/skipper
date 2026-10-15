package com.airbnb.skipper.internal.testutils

import com.airbnb.skipper.NoOpMetrics
import com.airbnb.skipper.metrics.SkipperCounter

/** [com.airbnb.skipper.Metrics] recording which counters were incremented, so a test can see which branch ran. */
class RecordingMetrics : NoOpMetrics() {
    val incremented = mutableListOf<String>()

    override fun counter(vararg names: String): SkipperCounter = recording(names.joinToString("."))

    override fun counter(
        tags: Map<String, String>,
        vararg names: String
    ): SkipperCounter = recording(names.joinToString("."))

    private fun recording(name: String) =
        object : SkipperCounter {
            override fun inc() {
                incremented.add(name)
            }

            override fun inc(n: Long) {
                incremented.add(name)
            }
        }
}
