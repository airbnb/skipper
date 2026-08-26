package com.airbnb.skipper.testutils

import com.google.inject.Singleton
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import org.slf4j.LoggerFactory

/**
 * A mutable clock that can be moved forward to an arbitrary time in the future or by a specific
 * duration.
 *
 * It is useful for testing workflows that have long waits. This will work just fine with
 * "ticking" clock implementations like Clock.systemUTC or fixed clocks. This class is thread safe
 * and a singleton, which means that all your tests will share the same clock, so you must disable
 * parallel test execution when using this!
 *
 * Example usage:
 * ```
 * @ExtendWith(GuiceExtension.class)
 * class MyTest {
 *   @Bind
 *   @Named(SkipperAnnotationNames.UTC_CLOCK)
 *   Clock clock = MutableClock.getInstance(Clock.systemUTC());
 *
 *   @BeforeEach
 *   public void setUp() {
 *     // Make sure the clock is reset before each test.
 *     ((MutableClock) clock).resetOffset();
 *   }
 *
 *   @Test
 *   public void test() {
 *     // Some stuff here...
 *     ((MutableClock) clock).fastForward(Duration.ofDays(1));
 *     // Some stuff that expects the clock to be after 1 day here...
 *   }
 *  }
 * ```
 */
@Singleton
class MutableClock private constructor(private val clock: Clock) : Clock() {
    private val version = AtomicInteger(0)
    private val offset = AtomicReference(Duration.ZERO)
    private val lock = ReentrantLock()

    override fun getZone(): ZoneId {
        throw UnsupportedOperationException()
    }

    override fun withZone(zone: ZoneId): Clock {
        throw UnsupportedOperationException()
    }

    override fun instant(): Instant {
        val now = clock.instant().plus(offset.get())
        log.debug(
            "*** mutable clock NOW: {}. offset={}, version={}, object={}",
            now,
            offset.get(),
            version.get(),
            this
        )
        return now
    }

    /** Move the clock forward by the given duration. */
    fun fastForward(duration: Duration) {
        lock.lock()
        try {
            val t1 = instant()
            offset.set(offset.get().plus(duration))
            val t2 = instant()
            log.debug(
                "mutable clock fast forward from {} -> {}. version={}",
                t1,
                t2,
                version.incrementAndGet()
            )
        } finally {
            lock.unlock()
        }
    }

    /** Move the clock forward to the given instant. */
    fun fastForward(instant: Instant) {
        fastForward(Duration.between(instant(), instant))
    }

    /** Reset the clock to the current time. */
    fun resetOffset() {
        lock.lock()
        try {
            offset.set(Duration.ZERO)
            version.set(0)
            log.debug("mutable clock has been reset. version={}", version.get())
        } finally {
            lock.unlock()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MutableClock::class.java)

        private var instance: MutableClock? = null
        private val instanceLock = ReentrantLock()

        @JvmStatic
        fun getInstance(clock: Clock): MutableClock {
            instanceLock.lock()
            try {
                if (instance == null) {
                    instance = MutableClock(clock)
                }
                return instance!!
            } finally {
                instanceLock.unlock()
            }
        }

        /** Reset the singleton instance. */
        @JvmStatic
        fun resetInstance() {
            instanceLock.lock()
            try {
                instance = null
            } finally {
                instanceLock.unlock()
            }
        }
    }
}
