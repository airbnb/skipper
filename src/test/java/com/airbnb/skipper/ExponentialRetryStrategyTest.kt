package com.airbnb.skipper

import com.airbnb.skipper.internal.serde.Serde
import com.airbnb.skipper.internal.serde.SmartSerde
import java.time.Duration
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExponentialRetryStrategyTest {
    @Test
    fun testNextRetryDelay() {
        val initialDelay = Duration.ofMillis(100)
        val maxDelay = Duration.ofMillis(500)
        val maxRetries = 5
        val multiplier = 2.0
        val strategy = ExponentialRetryStrategy(initialDelay, maxRetries, multiplier, maxDelay)

        // Test first retry
        var delay: Optional<Duration> = strategy.nextRetryDelay(0)
        assertTrue(delay.isPresent)
        assertEquals(initialDelay, delay.get())

        // Test second retry
        delay = strategy.nextRetryDelay(1)
        assertTrue(delay.isPresent)
        assertEquals(Duration.ofMillis(200), delay.get())

        // Test third retry
        delay = strategy.nextRetryDelay(2)
        assertTrue(delay.isPresent)
        assertEquals(Duration.ofMillis(400), delay.get())

        // Test delay capped by maxDelay
        delay = strategy.nextRetryDelay(3)
        assertTrue(delay.isPresent)
        assertEquals(maxDelay, delay.get())

        // Test max retries reached
        delay = strategy.nextRetryDelay(5)
        assertTrue(!delay.isPresent)
    }

    @Test
    fun testNextRetryDelayExceedsMaxRetries() {
        val initialDelay = Duration.ofMillis(100)
        val maxDelay = Duration.ofMillis(500)
        val maxRetries = 3
        val multiplier = 2.0
        val strategy = ExponentialRetryStrategy(initialDelay, maxRetries, multiplier, maxDelay)

        // Test retry count exceeds max retries
        val delay: Optional<Duration> = strategy.nextRetryDelay(4)
        assertTrue(!delay.isPresent)
    }

    @Test
    fun testIsSerializable() {
        val serde: Serde = SmartSerde()
        val strategy =
            ExponentialRetryStrategy(Duration.ofMillis(100), 5, 2.0, Duration.ofDays(1))
        val json = serde.serialize(strategy)
        val deserialized = serde.deserialize(json)
        assertEquals(strategy, deserialized)
    }
}
