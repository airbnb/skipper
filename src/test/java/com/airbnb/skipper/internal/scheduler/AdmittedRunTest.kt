package com.airbnb.skipper.internal.scheduler

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/** An [AdmittedRun] gives its slot back to the queue exactly once, however it ends. */
class AdmittedRunTest {
    private lateinit var queue: SchedulerExecutionQueue

    @BeforeEach
    fun setUp() {
        queue = SchedulerExecutionQueue(mock<Scheduler>(), Clock.systemUTC(), Duration.ofMinutes(8))
    }

    @Test
    fun testFinishedRunGivesTheSlotBack() {
        admit()
        val run = AdmittedRun(queue) {}
        run.run()
        assertEquals(0, queue.admittedCount)
    }

    @Test
    fun testRunThatThrowsGivesTheSlotBack() {
        // Covers the handler and the run's own cleanup alike: both throw out of the body.
        admit()
        val run = AdmittedRun(queue) { throw IllegalStateException("handler blew up") }
        run.run()
        assertEquals(0, queue.admittedCount)
    }

    @Test
    fun testRunCancelledBeforeItStartsGivesTheSlotBackOnce() {
        // A second task keeps the count above zero, so an extra release would show as 0, not throw.
        admit()
        admit()
        val run = AdmittedRun(queue) {}
        run.cancel(true)
        run.run()
        run.cancel(true)
        assertEquals(1, queue.admittedCount)
    }

    @Test
    fun testHandlerFutureKeepsTheSlotAfterTheRunEnds() {
        admit()
        val handled = CompletableFuture<Unit>()
        val run = AdmittedRun(queue) { holdSlotUntil -> holdSlotUntil(handled) }
        run.run()
        assertEquals(1, queue.admittedCount, "the handler is still running")

        handled.complete(Unit)
        assertEquals(0, queue.admittedCount)
    }

    @Test
    fun testHandlerFutureThatFailsGivesTheSlotBack() {
        admit()
        val handled = CompletableFuture<Unit>()
        val run = AdmittedRun(queue) { holdSlotUntil -> holdSlotUntil(handled) }
        run.run()

        handled.completeExceptionally(IllegalStateException("workflow blew up"))
        assertEquals(0, queue.admittedCount)
    }

    @Test
    fun testAlreadyCompletedHandlerFutureDoesNotHoldTheSlot() {
        admit()
        val run = AdmittedRun(queue) { holdSlotUntil -> holdSlotUntil(CompletableFuture.completedFuture(Unit)) }
        run.run()
        assertEquals(0, queue.admittedCount)
    }

    @Test
    fun testRunCancelledWhileTheHandlerRunsKeepsTheSlotUntilTheHandlerFinishes() {
        admit()
        val handled = CompletableFuture<Unit>()
        val reported = CountDownLatch(1)
        val run =
            AdmittedRun(queue) { holdSlotUntil ->
                holdSlotUntil(handled)
                reported.countDown()
                // The scheduler's wait for the handler, which cancelling interrupts.
                try {
                    Thread.sleep(5_000)
                } catch (ignored: InterruptedException) {
                }
            }
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.execute(run)
            assertTrue(reported.await(5, TimeUnit.SECONDS))
            run.cancel(true)
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(1, queue.admittedCount, "the handler is still running")

            handled.complete(Unit)
            assertEquals(0, queue.admittedCount)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun testRunCancelledBeforeItReachesTheHandlerGivesTheSlotBackOnce() {
        admit()
        admit()
        val handled = CompletableFuture<Unit>()
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val run =
            AdmittedRun(queue) { holdSlotUntil ->
                started.countDown()
                // Cancelling gives the slot back at once, since no handler has been reported yet.
                // Reporting one afterwards must not give it back a second time.
                try {
                    cancelled.await(5, TimeUnit.SECONDS)
                } catch (ignored: InterruptedException) {
                }
                holdSlotUntil(handled)
            }
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.execute(run)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            run.cancel(true)
            cancelled.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            handled.complete(Unit)
            assertEquals(1, queue.admittedCount)
        } finally {
            pool.shutdownNow()
        }
    }

    /** Adds a task and takes it, which leaves it admitted, as the scheduler's take loop does. */
    private fun admit() {
        val id = UUID.randomUUID().toString()
        val task =
            Task.builder<Any>()
                .id(id)
                .type(Task.Type.WORKFLOW)
                .dedupToken(id)
                .createdAt(Instant.now())
                .runAfter(Instant.EPOCH)
                .executionTimeout(Duration.ofSeconds(30))
                .build()
        assertTrue(queue.add(task))
        queue.take<Any>(task.type)
    }
}
