package com.airbnb.skipper.testutils

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/**
 * A [Clock] the test moves by hand: it reads [base] plus an offset that only grows when the test
 * calls [fastForward]. With the default fixed base, time stands still until told otherwise, which
 * is how [WorkflowTest] runs Skipper: `waitUntil` deadlines and timers fire only when the test
 * says so. Wrap `Clock.systemUTC()` instead for a clock that keeps ticking but can be jumped ahead.
 */
class MutableClock
    @JvmOverloads
    constructor(
        private val base: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
    ) : Clock() {
        private val offset = AtomicReference(Duration.ZERO)

        override fun instant(): Instant = base.instant().plus(offset.get())

        override fun getZone(): ZoneId = base.zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(base.withZone(zone)).also { it.offset.set(offset.get()) }

        /** Moves the clock forward by [duration]. */
        fun fastForward(duration: Duration) {
            require(!duration.isNegative) { "a clock only moves forward; got $duration" }
            offset.updateAndGet { it.plus(duration) }
        }

        /** Moves the clock forward to [instant], which must not be before the current instant. */
        fun fastForward(instant: Instant) = fastForward(Duration.between(instant(), instant))

        /** Drops any fast-forwarding, so the clock reads [base] again. */
        fun resetOffset() = offset.set(Duration.ZERO)

        companion object {
            private val instanceLock = Any()
            private var instance: MutableClock? = null

            /**
             * A process-wide instance over [clock], created on first use. Kept for tests that share one
             * clock across components wired separately; a [WorkflowTest] has its own clock and does not
             * use this. Pair with [resetInstance] between tests.
             */
            @JvmStatic
            fun getInstance(clock: Clock): MutableClock = synchronized(instanceLock) { instance ?: MutableClock(clock).also { instance = it } }

            /** Forgets the process-wide instance so the next [getInstance] starts fresh. */
            @JvmStatic
            fun resetInstance() = synchronized(instanceLock) { instance = null }
        }
    }
