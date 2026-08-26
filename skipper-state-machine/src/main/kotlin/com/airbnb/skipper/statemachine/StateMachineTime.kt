package com.airbnb.skipper.statemachine

import java.time.Clock

/** Capture the current clock instant as a nanosecond-precision state-machine timestamp. */
internal fun Clock.preciseNow(): SkipperStateMachine.PreciseTimestamp {
    val instant = instant()
    return SkipperStateMachine.PreciseTimestamp(instant.epochSecond, instant.nano)
}

/** Executes [block] and returns the result plus the wall-clock span it consumed. */
internal inline fun <T> measureTimeRange(
    clock: Clock,
    block: () -> T,
): Pair<SkipperStateMachine.PreciseTimeRange, T> {
    val start = clock.preciseNow()
    val result = block()
    val end = clock.preciseNow()
    return SkipperStateMachine.PreciseTimeRange(start, end) to result
}
