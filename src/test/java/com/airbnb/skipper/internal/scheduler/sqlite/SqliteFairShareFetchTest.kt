package com.airbnb.skipper.internal.scheduler.sqlite

import com.airbnb.skipper.internal.cluster.BucketRange
import com.airbnb.skipper.internal.scheduler.FairShareFetcher
import com.airbnb.skipper.internal.scheduler.ScheduleRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore
import com.airbnb.skipper.testutils.SqliteTestSetupExtension
import com.airbnb.skipper.testutils.TestRuntime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

/** [FairShareFetcher] against a real scheduler: one queue with a huge backlog next to small ones. */
@ExtendWith(SqliteTestSetupExtension::class)
class SqliteFairShareFetchTest {
    private val clock: Clock = mock(Clock::class.java)
    private lateinit var scheduler: Scheduler

    @BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        deps.setClock(clock)
        deps.config.schedulerTaskLeaseDuration = Duration.ofMinutes(10)
        deps.config.sqliteDataSource = SqliteTestSetupExtension.DB_DATA_SOURCE
        deps.config.workflowStore = SqliteWorkflowStore.Factory()
        deps.config.scheduler = SqliteScheduler.Factory()
        scheduler = deps.scheduler
        whenever(clock.instant()).thenReturn(Instant.EPOCH.plusSeconds(60))
    }

    @Test
    fun testWithoutFairShareAFloodedQueueCrowdsOutTheOthers() {
        scheduleReady("noisy", FLOOD)
        scheduleReady("quiet", 10)

        // The plain fetch is a random sample of everything ready, so the quiet queue, 1% of the
        // backlog, gets about 1% of what is fetched.
        val fetched = (1..10).flatMap { scheduler.fetch<Any>(10).asJava() }
        assertEquals(100, fetched.size)
        assertTrue(fetched.count { it.queue == "quiet" } < 10, "the quiet queue is not drained by a plain fetch")
    }

    @Test
    fun testAFloodedQueueDoesNotKeepOtherQueuesFromBeingFetched() {
        scheduleReady("noisy", FLOOD)
        scheduleReady("quiet", 10)
        val fetcher = FairShareFetcher(scheduler, emptyMap(), Random(1))

        // Two rounds of ten: each queue gets five per round, so the quiet queue is drained while
        // the noisy one still has almost all of its backlog.
        val fetched = (1..2).flatMap { fetcher.fetch(10, null).asJava() }
        assertEquals(10, fetched.count { it.queue == "quiet" })
        assertEquals(10, fetched.count { it.queue == "noisy" })
    }

    @Test
    fun testALowWeightQueueStillGetsATaskOnEveryFetch() {
        scheduleReady("important", FLOOD)
        scheduleReady("background", 50)
        val fetcher = FairShareFetcher(scheduler, mapOf("important" to 1_000), Random(1))

        repeat(5) { round ->
            val fetched = fetcher.fetch(5, null).asJava()
            assertEquals(4, fetched.count { it.queue == "important" }, "round $round")
            assertEquals(1, fetched.count { it.queue == "background" }, "round $round")
        }
    }

    @Test
    fun testWeightsSetTheShareOfEachQueue() {
        scheduleReady("heavy", 200)
        scheduleReady("light", 200)
        val fetcher = FairShareFetcher(scheduler, mapOf("heavy" to 3), Random(1))

        val fetched = fetcher.fetch(98, null).asJava()
        assertEquals(73, fetched.count { it.queue == "heavy" })
        assertEquals(25, fetched.count { it.queue == "light" })
    }

    @Test
    fun testSlotsAQueueCannotFillGoToTheOthers() {
        scheduleReady("noisy", 100)
        scheduleReady("quiet", 1)
        val fetcher = FairShareFetcher(scheduler, emptyMap(), Random(1))

        val fetched = fetcher.fetch(10, null).asJava()
        assertEquals(1, fetched.count { it.queue == "quiet" })
        assertEquals(9, fetched.count { it.queue == "noisy" })
    }

    @Test
    fun testFairShareWithinAPartition() {
        scheduleReady("noisy", FLOOD)
        scheduleReady("quiet", 40)
        val fetcher = FairShareFetcher(scheduler, emptyMap(), Random(1))
        val partition = BucketRange(0, 500)

        val fetched = fetcher.fetch(10, partition).asJava()
        assertEquals(10, fetched.size)
        assertEquals(5, fetched.count { it.queue == "quiet" })
    }

    @Test
    fun testOnlyQueuesWithTasksReadyAreDue() {
        scheduleReady("ready", 2)
        schedule("later", 2, Instant.EPOCH.plusSeconds(3_600))
        assertEquals(listOf("ready"), scheduler.dueQueues().asJava())

        // Leased tasks are not ready either.
        scheduler.fetch<Any>(10)
        assertEquals(emptyList<String>(), scheduler.dueQueues().asJava())
    }

    private fun scheduleReady(
        queue: String,
        count: Int,
    ) = schedule(queue, count, Instant.EPOCH)

    private fun schedule(
        queue: String,
        count: Int,
        runAfter: Instant,
    ) {
        repeat(count) { i ->
            val id = "$queue-$i"
            scheduler.schedule(
                ScheduleRequest.builder<Any>().type(Task.Type.WORKFLOW).id(id).dedupToken(id).payload(null).queue(queue).runAfter(runAfter).build(),
            )
        }
    }

    private companion object {
        const val FLOOD = 1_000
    }
}
