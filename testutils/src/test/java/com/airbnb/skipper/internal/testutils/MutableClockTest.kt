package com.airbnb.skipper.internal.testutils

import com.airbnb.skipper.testutils.MutableClock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MutableClockTest {
    @Test
    fun testMutableClockWithFixedClock() {
        // Only test with fixed clock to avoid timing issues with system clock
        // The system clock keeps ticking which causes race conditions in assertions
        val fixedClock = Clock.fixed(Instant.ofEpochSecond(1000000), ZoneOffset.UTC)

        MutableClock.resetInstance()
        val clock = MutableClock.getInstance(fixedClock)
        assertEquals(
            fixedClock.instant().truncatedTo(ChronoUnit.SECONDS),
            clock.instant().truncatedTo(ChronoUnit.SECONDS),
        )
        // Advance the clock 1 day
        clock.fastForward(Duration.ofDays(1))
        assertEquals(
            fixedClock.instant().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS),
            clock.instant().truncatedTo(ChronoUnit.SECONDS),
        )
        // Advance another day
        clock.fastForward(Duration.ofDays(1))
        assertEquals(
            fixedClock.instant().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS),
            clock.instant().truncatedTo(ChronoUnit.SECONDS),
        )
        // Reset the clock
        clock.resetOffset()
        assertEquals(
            fixedClock.instant().truncatedTo(ChronoUnit.SECONDS),
            clock.instant().truncatedTo(ChronoUnit.SECONDS),
        )
        // Advance the clock to a specific time
        val futureTime = fixedClock.instant().plus(Duration.ofDays(365).plusMillis(1234))
        clock.fastForward(futureTime)
        assertEquals(
            futureTime.truncatedTo(ChronoUnit.SECONDS),
            clock.instant().truncatedTo(ChronoUnit.SECONDS),
        )
    }

    @Test
    fun testMutableClockWithSystemClock() {
        // Test with system clock using relative comparisons to avoid timing issues
        MutableClock.resetInstance()
        val systemClock = Clock.systemUTC()
        val clock = MutableClock.getInstance(systemClock)

        // Capture baseline time
        val beforeFastForward = clock.instant()

        // Advance the clock 1 day
        clock.fastForward(Duration.ofDays(1))
        val afterOneDayFastForward = clock.instant()

        // The time should have advanced by approximately 1 day (allow small margin for test execution)
        val elapsed = Duration.between(beforeFastForward, afterOneDayFastForward)
        assertTrue(
            elapsed.compareTo(Duration.ofDays(1).minusSeconds(1)) > 0,
            "Clock should have advanced by at least 1 day minus 1 second",
        )
        assertTrue(
            elapsed.compareTo(Duration.ofDays(1).plusSeconds(5)) < 0,
            "Clock should have advanced by at most 1 day plus 5 seconds",
        )

        // Advance another day
        clock.fastForward(Duration.ofDays(1))
        val afterTwoDaysFastForward = clock.instant()

        val totalElapsed = Duration.between(beforeFastForward, afterTwoDaysFastForward)
        assertTrue(
            totalElapsed.compareTo(Duration.ofDays(2).minusSeconds(1)) > 0,
            "Clock should have advanced by at least 2 days minus 1 second",
        )
    }
}
