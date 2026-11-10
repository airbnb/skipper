package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.testutils.TestRuntime
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** How [SchedulerExecutionQueue] counts the tasks it has admitted and not yet finished. */
class SchedulerExecutionQueueAdmissionTest {
    private lateinit var queue: SchedulerExecutionQueue

    @BeforeEach
    fun setUp() {
        queue = TestRuntime().schedulerExecutionQueue
    }

    @Test
    fun testCountsTasksFromAddUntilFinished() {
        queue.add(task("a"))
        queue.add(task("b"))
        assertEquals(2, queue.admittedCount)

        // Taking a task does not release it: it is still running until markFinished.
        queue.take<Any>(Task.Type.WORKFLOW)
        assertEquals(2, queue.admittedCount)

        queue.markFinished()
        assertEquals(1, queue.admittedCount)
    }

    @Test
    fun testDuplicateAddIsNotAdmitted() {
        assertTrue(queue.add(task("a")))
        assertFalse(queue.add(task("a")))
        assertEquals(1, queue.admittedCount)
    }

    @Test
    fun testInMemoryScheduleIsAdmitted() {
        val id = UUID.randomUUID().toString()
        queue.schedule(
            ScheduleRequest.builder<Any>()
                .type(Task.Type.WORKFLOW)
                .id(id)
                .dedupToken(id)
                .payload(null)
                .inMemoryExecutionEnabled(true)
                .build(),
        )
        assertEquals(1, queue.admittedCount)
    }

    @Test
    fun testMarkFinishedWithNothingAdmittedFails() {
        assertThrows(IllegalStateException::class.java) { queue.markFinished() }
    }

    @Test
    fun testAwaitReturnsOnceATaskFinishes() {
        queue.add(task("a"))
        queue.add(task("b"))
        assertFalse(queue.awaitAdmittedBelow(2, Duration.ofMillis(50)))

        val waiter = CompletableFuture.supplyAsync { queue.awaitAdmittedBelow(2, Duration.ofSeconds(10)) }
        Thread.sleep(100)
        assertFalse(waiter.isDone)
        queue.markFinished()
        assertTrue(waiter.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun testAwaitReturnsAtOnceWhenBelowTheLimit() {
        queue.add(task("a"))
        assertTrue(queue.awaitAdmittedBelow(2, Duration.ZERO))
    }

    private fun task(id: String): Task<Any> =
        Task.builder<Any>()
            .id(id)
            .type(Task.Type.WORKFLOW)
            .dedupToken(id)
            .createdAt(Instant.now())
            .runAfter(Instant.EPOCH)
            .executionTimeout(Duration.ofSeconds(30))
            .build()
}
