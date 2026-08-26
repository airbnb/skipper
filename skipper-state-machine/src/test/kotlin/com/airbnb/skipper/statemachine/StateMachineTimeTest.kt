package com.airbnb.skipper.statemachine

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StateMachineTimeTest {
    @Test
    fun `preciseNow preserves epoch seconds and nanoseconds`() {
        val clock = MutableTestClock()
        clock.advanceNanos(1_234_567_890L)

        val timestamp = clock.preciseNow()

        assertThat(timestamp.epochSecond).isEqualTo(1L)
        assertThat(timestamp.nano).isEqualTo(234_567_890)
    }

    @Test
    fun `measureTimeRange returns block result and measured span`() {
        val clock = MutableTestClock()

        val (span, result) = measureTimeRange(clock) {
            clock.advanceNanos(250L)
            "done"
        }

        assertThat(result).isEqualTo("done")
        assertThat(span.start).isEqualTo(SkipperStateMachine.PreciseTimestamp(0L, 0))
        assertThat(span.end).isEqualTo(SkipperStateMachine.PreciseTimestamp(0L, 250))
    }
}
