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

/** An [Admission] goes back to its queue exactly once, however its run ends. */
class AdmissionTest {
    private lateinit var queue: SchedulerExecutionQueue

    @BeforeEach
    fun setUp() {
        queue = SchedulerExecutionQueue(mock<Scheduler>(), Clock.systemUTC(), Duration.ofMinutes(8))
    }

    @Test
    fun testFinishedRunGivesTheSlotBack() {
        val run = AdmittedRun(admit()) {}
        run.run()
        assertEquals(0, queue.admittedCount)
    }

    @Test
    fun testRunThatThrowsGivesTheSlotBack() {
        // Covers the handler and the run's own cleanup alike: both throw out of the body.
        val run = AdmittedRun(admit()) { throw IllegalStateException("handler blew up") }
        run.run()
        assertEquals(0, queue.admittedCount)
    }

    @Test
    fun testRunCancelledBeforeItStartsGivesTheSlotBackOnce() {
        // A second task keeps the count above zero, so an extra release would show as 0, not throw.
        admit()
        val run = AdmittedRun(admit()) {}
        run.cancel(true)
        run.run()
        run.cancel(true)
        assertEquals(1, queue.admittedCount)
    }

    @Test
    fun testHandlerFutureKeepsTheSlotAfterTheRunEnds() {
        val handled = CompletableFuture<Unit>()
        val admission = admit()
        val run = AdmittedRun(admission) { admission.holdUntil(handled) }
        run.run()
        assertEquals(1, queue.admittedCount, "the handler is still running")

        handled.complete(Unit)
        assertEquals(0, queue.admittedCount)
    }

    @Test
    fun testAlreadyCompletedHandlerFutureDoesNotHoldTheSlot() {
        val admission = admit()
        val run = AdmittedRun(admission) { admission.holdUntil(CompletableFuture.completedFuture(Unit)) }
        run.run()
        assertEquals(0, queue.admittedCount)
    }

    @Test
    fun testRunCancelledWhileRunningGivesTheSlotBackOnce() {
        admit()
        val handled = CompletableFuture<Unit>()
        val admission = admit()
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val run =
            AdmittedRun(admission) {
                started.countDown()
                // Cancelling interrupts this wait. The admission is already given back by then, so
                // the hold below must not take it again.
                try {
                    cancelled.await(5, TimeUnit.SECONDS)
                } catch (ignored: InterruptedException) {
                }
                admission.holdUntil(handled)
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

    private fun admit(): Admission {
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
        // Taking it leaves it admitted, as the scheduler's take loop does.
        queue.take<Any>(task.type)
        return Admission(queue)
    }
}
