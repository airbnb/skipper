package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.internal.cluster.BucketRange
import io.vavr.collection.List
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Which scheduler calls [FairShareFetcher] makes, and what it does when one of them fails. */
class FairShareFetcherTest {
    private val scheduler = mock<Scheduler>()
    private val fetcher = FairShareFetcher(scheduler, emptyMap(), Random(7))
    private val partition = BucketRange(0, 100)

    @Test
    fun testWithNoQueueDueItIsOnePlainFetch() {
        whenever(scheduler.dueQueues()).thenReturn(List.empty())
        whenever(scheduler.fetch<Any>(5)).thenReturn(List.of(task("t", "a")))

        assertEquals(1, fetcher.fetch(5, null).size())
        verify(scheduler, never()).fetch<Any>(any(), anyOrNull(), any())
    }

    @Test
    fun testWithOneQueueDueItIsOnePlainFetchOfThePartition() {
        whenever(scheduler.dueQueues()).thenReturn(List.of("a"))
        whenever(scheduler.fetch<Any>(5, partition)).thenReturn(List.of(task("t", "a")))

        assertEquals(1, fetcher.fetch(5, partition).size())
        verify(scheduler, never()).fetch<Any>(any(), anyOrNull(), any())
    }

    @Test
    fun testEachDueQueueIsFetchedUpToItsShareWithinThePartition() {
        whenever(scheduler.dueQueues()).thenReturn(List.of("a", "b"))
        whenever(scheduler.fetch<Any>(3, partition, "a")).thenReturn(List.of(task("a1", "a"), task("a2", "a"), task("a3", "a")))
        whenever(scheduler.fetch<Any>(3, partition, "b")).thenReturn(List.of(task("b1", "b"), task("b2", "b"), task("b3", "b")))

        assertEquals(6, fetcher.fetch(6, partition).size())
        // Every slot was used, so there is nothing to top up.
        verify(scheduler, never()).fetch<Any>(any(), eq(partition))
    }

    @Test
    fun testAFailedQueueFetchKeepsTheTasksAlreadyLeased() {
        whenever(scheduler.dueQueues()).thenReturn(List.of("a", "b"))
        whenever(scheduler.fetch<Any>(3, null, "a")).thenReturn(List.of(task("a1", "a")))
        whenever(scheduler.fetch<Any>(3, null, "b")).thenThrow(IllegalStateException("store is down"))

        // "a" is fetched first and "b" fails: the task leased from "a" is still delivered.
        assertEquals(listOf("a1"), fetcher.fetch(6, null).map { it.id }.asJava())
    }

    @Test
    fun testAFailedTopUpKeepsTheTasksAlreadyLeased() {
        whenever(scheduler.dueQueues()).thenReturn(List.of("a", "b"))
        whenever(scheduler.fetch<Any>(3, null, "a")).thenReturn(List.of(task("a1", "a")))
        whenever(scheduler.fetch<Any>(3, null, "b")).thenReturn(List.of(task("b1", "b")))
        whenever(scheduler.fetch<Any>(4)).thenThrow(IllegalStateException("store is down"))

        assertEquals(listOf("a1", "b1"), fetcher.fetch(6, null).map { it.id }.asJava())
    }

    @Test
    fun testUnusedSlotsAreToppedUpFromWhateverIsReady() {
        whenever(scheduler.dueQueues()).thenReturn(List.of("a", "b"))
        whenever(scheduler.fetch<Any>(3, partition, "a")).thenReturn(List.of(task("a1", "a")))
        whenever(scheduler.fetch<Any>(3, partition, "b")).thenReturn(List.empty())
        whenever(scheduler.fetch<Any>(5, partition)).thenReturn(List.of(task("a2", "a"), task("a3", "a")))

        assertEquals(listOf("a1", "a2", "a3"), fetcher.fetch(6, partition).map { it.id }.asJava())
    }

    @Test
    fun testAFailureBeforeAnythingIsLeasedPropagates() {
        whenever(scheduler.dueQueues()).thenReturn(List.of("a", "b"))
        whenever(scheduler.fetch<Any>(any(), anyOrNull(), any())).thenThrow(IllegalStateException("store is down"))

        assertThrows(IllegalStateException::class.java) { fetcher.fetch(6, null) }
    }

    @Test
    fun testReservedFetchAsksOnlyTheQueuesBelowTheirShare() {
        whenever(scheduler.dueQueues()).thenReturn(List.of("noisy", "quiet"))
        whenever(scheduler.fetch<Any>(3, partition, "quiet")).thenReturn(List.of(task("q1", "quiet")))

        // Limit 10 and two queues: 5 reserved each. "quiet" runs 2 and is owed 3; "noisy" runs 14.
        val fetched = fetcher.fetchReserved(5, 6, 10, mapOf("noisy" to 14, "quiet" to 2), partition)
        assertEquals(listOf("q1"), fetched.map { it.id }.asJava())
        verify(scheduler, never()).fetch<Any>(any(), anyOrNull(), eq("noisy"))
        verify(scheduler, never()).fetch<Any>(any<Int>())
    }

    private fun task(
        id: String,
        queue: String,
    ): Task<Any> =
        Task.builder<Any>()
            .id(id)
            .type(Task.Type.WORKFLOW)
            .dedupToken(id)
            .queue(queue)
            .createdAt(Instant.EPOCH)
            .executionTimeout(Duration.ofSeconds(30))
            .build()
}
