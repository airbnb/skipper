package com.airbnb.skipper.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/**
 * A [Clock] that only moves when told to. [WorkflowTest] runs Skipper on one of these so that
 * timeouts and `waitUntil` deadlines are under the test's control: nothing expires until the test
 * calls [fastForward].
 */
class MutableClock(
    start: Instant = Instant.EPOCH,
) : Clock() {
    private val now = AtomicReference(start)

    override fun instant(): Instant = now.get()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    /** Moves the clock forward by [duration]. */
    fun fastForward(duration: Duration) {
        require(!duration.isNegative) { "a clock only moves forward; got $duration" }
        now.updateAndGet { it.plus(duration) }
    }

    /** Moves the clock forward to [instant], which must not be in the past. */
    fun fastForward(instant: Instant) = fastForward(Duration.between(instant(), instant))
}
