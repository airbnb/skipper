package com.airbnb.skipper.internal

import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.Knobs
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.internal.cluster.BucketPartitioner
import com.airbnb.skipper.internal.cluster.ClusterMembershipManager
import com.airbnb.skipper.internal.cluster.SingleMemberClusterMembershipManager
import com.airbnb.skipper.internal.scheduler.LeaseRenewalManager
import com.airbnb.skipper.internal.scheduler.ScheduleRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.SchedulerExecutionQueue
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.scheduler.TaskHandler
import com.airbnb.skipper.internal.testutils.inactiveCancelWatcher
import com.airbnb.skipper.testutils.TestRuntime
import io.vavr.collection.HashMap
import io.vavr.collection.List
import io.vavr.control.Option
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** How [SkipperSchedulerManager] limits the tasks one process takes on at once. */
class SkipperSchedulerManagerConcurrencyTest {
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val taskHandlerExecutor: ExecutorService = Executors.newCachedThreadPool()
    private lateinit var metrics: Metrics
    private lateinit var schedulerQueue: SchedulerExecutionQueue
    private lateinit var scheduler: Scheduler
    private lateinit var leaseManager: LeaseRenewalManager
    private lateinit var featureGate: FeatureGate
    private lateinit var knobs: Knobs
    private var manager: SkipperSchedulerManager? = null

    @BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        val runtime = deps.getRuntime()
        metrics = runtime.metrics.get()
        featureGate = deps.featureGate
        knobs = runtime.knobs.get()
        schedulerQueue = deps.schedulerExecutionQueue
        scheduler = deps.scheduler
        leaseManager = deps.leaseRenewalManager
    }

    @AfterEach
    fun tearDown() {
        manager?.forceStop()
        executor.shutdownNow()
        taskHandlerExecutor.shutdownNow()
    }

    @Test
    fun testRunsNoMoreTasksAtOnceThanTheLimit() {
        val handler = BlockingHandler()
        manager = createManager(scheduler, handler, knobs, maxConcurrentTasks = 3).also { it.start() }
        repeat(10) { scheduleReadyTask() }

        assertTrue(handler.awaitStarted(3), "the first three tasks should start")
        // Give the fetch loop several rounds to over-fetch if it were going to.
        Thread.sleep(2_500)
        assertEquals(3, handler.started.get(), "no task should start while three are running")
        assertEquals(3, schedulerQueue.admittedCount)

        handler.release()
        assertTrue(handler.awaitFinished(10), "the rest should run once slots free up")
        assertTrue(handler.maxRunning.get() <= 3, "at most three ran at once, saw ${handler.maxRunning.get()}")
    }

    @Test
    fun testInMemoryTasksCountAgainstTheLimitAndPauseFetching() {
        val mockScheduler = idleScheduler()
        val handler = BlockingHandler()
        manager = createManager(mockScheduler, handler, knobs, maxConcurrentTasks = 1).also { it.start() }

        // An in-memory execution is always admitted, and it fills the only slot.
        schedulerQueue.add(readyTask())
        assertTrue(handler.awaitStarted(1))
        // The fetch loop may have fetched once before the task was added; any fetch from now on
        // would mean it ignored the full slot.
        Thread.sleep(200)
        clearInvocations(mockScheduler)
        Thread.sleep(2_500)
        verify(mockScheduler, never()).fetch<Any>(any<Int>())

        handler.release()
        assertTrue(handler.awaitFinished(1))
        Thread.sleep(1_500)
        verify(mockScheduler, atLeastOnce()).fetch<Any>(any<Int>())
    }

    @Test
    fun testAsksOnlyForTheFreeSlots() {
        val mockScheduler = idleScheduler()
        val handler = BlockingHandler()
        manager = createManager(mockScheduler, handler, knobs, maxConcurrentTasks = 3).also { it.start() }

        schedulerQueue.add(readyTask())
        assertTrue(handler.awaitStarted(1))
        clearInvocations(mockScheduler)
        Thread.sleep(1_500)

        // The default fetch size is 5, but only two slots are free.
        verify(mockScheduler, atLeastOnce()).fetch<Any>(2)
        verify(mockScheduler, never()).fetch<Any>(5)
        handler.release()
    }

    @Test
    fun testSlotsFreedWhileFullAreFetchedAsOneBatch() {
        // The admitted count is set by the test, so it can drop from full to empty in one step and
        // the next poll is guaranteed to see every freed slot at once.
        val admitted = AtomicInteger(3)
        val queue = spy(schedulerQueue)
        whenever(queue.admittedCount).thenAnswer { admitted.get() }
        val mockScheduler = idleScheduler()
        manager = createManager(mockScheduler, CompletingHandler(), knobs, maxConcurrentTasks = 3, queue = queue)
            .also { it.start() }

        Thread.sleep(500)
        verify(mockScheduler, never()).fetch<Any>(any<Int>())

        admitted.set(0)
        Thread.sleep(500)
        verify(mockScheduler).fetch<Any>(3)
        verify(mockScheduler, never()).fetch<Any>(1)
        verify(mockScheduler, never()).fetch<Any>(2)
    }

    @Test
    fun testWithNoLimitAFetchBelowTheDefaultSizeStillPauses() {
        // With no limit the pause rule is unchanged: any fetch that returns fewer than 5 tasks is
        // followed by a pause, even a full one of a smaller fetch size.
        val mockKnobs = mock<Knobs>()
        whenever(mockKnobs.getInteger(Knobs.Keys.SCHEDULER_FETCH_COUNT)).thenReturn(Optional.of(2))
        val fetches = AtomicInteger()
        val mockScheduler = idleScheduler()
        whenever(mockScheduler.fetch<Any>(2)).thenAnswer {
            fetches.incrementAndGet()
            List.of(readyTask(), readyTask())
        }
        manager = createManager(mockScheduler, CompletingHandler(), mockKnobs).also { it.start() }

        Thread.sleep(1_500)
        assertTrue(fetches.get() <= 2, "a fetch below 5 should be followed by a pause, saw ${fetches.get()}")
    }

    @Test
    fun testFetchesAgainWithoutTheLongPauseAfterFillingTheFreeSlots() {
        // Two free slots and a fetch that fills both: the loop must not wait a second, or a limit
        // below 5 would cap throughput at one batch a second.
        val fetches = AtomicInteger()
        val mockScheduler = idleScheduler()
        whenever(mockScheduler.fetch<Any>(2)).thenAnswer {
            fetches.incrementAndGet()
            List.of(readyTask(), readyTask())
        }
        manager = createManager(mockScheduler, CompletingHandler(), knobs, maxConcurrentTasks = 2).also { it.start() }

        Thread.sleep(900)
        assertTrue(fetches.get() >= 3, "expected fetches without a one-second pause, saw ${fetches.get()}")
    }

    @Test
    fun testFetchesAgainStraightAwayAfterAShortFetchOfFiveOrMore() {
        // Rows lost to another instance's lease can make a large fetch short with more still ready;
        // as before this change, only a fetch below 5 pauses.
        val mockKnobs = mock<Knobs>()
        whenever(mockKnobs.getInteger(Knobs.Keys.SCHEDULER_FETCH_COUNT)).thenReturn(Optional.of(10))
        val fetches = AtomicInteger()
        val mockScheduler = idleScheduler()
        whenever(mockScheduler.fetch<Any>(10)).thenAnswer {
            fetches.incrementAndGet()
            List.fill(7) { readyTask() }
        }
        manager = createManager(mockScheduler, CompletingHandler(), mockKnobs).also { it.start() }

        Thread.sleep(900)
        assertTrue(fetches.get() >= 3, "expected back-to-back fetches, saw ${fetches.get()}")
    }

    @Test
    fun testSleepsAfterAShortFetch() {
        val fetches = AtomicInteger()
        val mockScheduler = idleScheduler()
        whenever(mockScheduler.fetch<Any>(any<Int>())).thenAnswer {
            fetches.incrementAndGet()
            List.of(readyTask())
        }
        manager = createManager(mockScheduler, CompletingHandler(), knobs).also { it.start() }

        Thread.sleep(1_500)
        assertTrue(fetches.get() <= 2, "a short fetch should be followed by a pause, saw ${fetches.get()}")
    }

    @Test
    fun testSleepsAfterAnEmptyFetchWhenTheFetchSizeIsZero() {
        // A fetch size of zero asks for nothing and gets nothing. That must still pause, or the loop
        // queries the scheduler as fast as it can.
        val mockKnobs = mock<Knobs>()
        whenever(mockKnobs.getInteger(Knobs.Keys.SCHEDULER_FETCH_COUNT)).thenReturn(Optional.of(0))
        val fetches = AtomicInteger()
        val mockScheduler = idleScheduler()
        whenever(mockScheduler.fetch<Any>(0)).thenAnswer {
            fetches.incrementAndGet()
            List.empty<Task<Any>>()
        }
        manager = createManager(mockScheduler, CompletingHandler(), mockKnobs).also { it.start() }

        Thread.sleep(1_500)
        assertTrue(fetches.get() <= 2, "an empty fetch should be followed by a pause, saw ${fetches.get()}")
    }

    @Test
    fun testKnobOverridesTheConfiguredLimit() {
        val mockKnobs = mock<Knobs>()
        val manager = createManager(idleScheduler(), CompletingHandler(), mockKnobs, maxConcurrentTasks = 10)
        assertEquals(10, manager.freeSlots())

        whenever(mockKnobs.getInteger(Knobs.Keys.SCHEDULER_MAX_CONCURRENT_TASKS)).thenReturn(Optional.of(2))
        schedulerQueue.add(readyTask())
        assertEquals(1, manager.freeSlots())

        whenever(mockKnobs.getInteger(Knobs.Keys.SCHEDULER_MAX_CONCURRENT_TASKS)).thenReturn(Optional.of(0))
        assertEquals(Int.MAX_VALUE, manager.freeSlots())
    }

    @Test
    fun testNoLimitByDefault() {
        val manager = createManager(idleScheduler(), CompletingHandler(), knobs)
        repeat(50) { schedulerQueue.add(readyTask()) }
        assertEquals(Int.MAX_VALUE, manager.freeSlots())
    }

    @Test
    fun testReleasesTheSlotWhenTheTaskCannotBeSubmitted() {
        val rejecting = RejectingExecutor()
        manager = createManager(idleScheduler(), CompletingHandler(), knobs, mainExecutor = rejecting).also { it.start() }
        // The loops are running; from now on every task submission is rejected.
        rejecting.reject = true

        schedulerQueue.add(readyTask())
        assertTrue(awaitNoneAdmitted(), "a rejected task must not keep its slot")
        rejecting.shutdownNow()
    }

    @Test
    fun testKeepsTheSlotWhileTheHandlerOutlivesTheTimeout() {
        // The scheduler stops waiting after a second, but the handler is still running on its pool.
        val handled = CompletableFuture<Option<Instant>>()
        val handler =
            object : TaskHandler {
                override fun handle(
                    task: Task<*>,
                    executorService: ExecutorService,
                ): CompletableFuture<Option<Instant>> = handled
            }
        manager =
            createManager(idleScheduler(), handler, knobs, executionTimeout = Duration.ofSeconds(1))
                .also { it.start() }

        schedulerQueue.add(readyTask())
        Thread.sleep(2_500)
        assertEquals(1, schedulerQueue.admittedCount, "the handler has not finished, so the slot is still held")

        handled.complete(Option.none())
        assertTrue(awaitNoneAdmitted(), "the slot is given back once the handler finishes")
    }

    @Test
    fun testReleasesTheSlotWhenSomethingThrowsBeforeTheRunStarts() {
        val throwingMetrics =
            object : Metrics by metrics {
                override fun counter(vararg names: String) =
                    if ("takenTasks" in names) throw IllegalStateException("metrics backend down") else metrics.counter(*names)
            }
        manager = createManager(idleScheduler(), CompletingHandler(), knobs, metrics = throwingMetrics).also { it.start() }

        schedulerQueue.add(readyTask())
        assertTrue(awaitNoneAdmitted(), "a task that never started must not keep its slot")
    }

    @Test
    fun testEveryPartitioningFallbackFetchesTheRequestedSize() {
        // Each way partitioning falls back to the whole queue must still ask for the size the
        // fetch loop computed from the free slots, not the configured fetch size.
        val cluster = mock<ClusterMembershipManager>()
        whenever(cluster.isRegistered).thenReturn(true)
        whenever(cluster.currentMemberId).thenReturn("me")
        val mockScheduler = idleScheduler()
        val manager = createManager(mockScheduler, CompletingHandler(), knobs, cluster = cluster)

        // 1. No live members yet.
        whenever(cluster.activeMemberIds).thenReturn(List.empty())
        manager.fetchTasksWithOptionalPartitioning(3)
        // 2. Live members, but not this one.
        whenever(cluster.activeMemberIds).thenReturn(List.of("other"))
        manager.fetchTasksWithOptionalPartitioning(3)
        // 3. Reading the members fails.
        whenever(cluster.activeMemberIds).thenThrow(IllegalStateException("membership read failed"))
        manager.fetchTasksWithOptionalPartitioning(3)

        verify(mockScheduler, times(3)).fetch<Any>(3)
    }

    private fun awaitNoneAdmitted(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (schedulerQueue.admittedCount > 0) {
            if (System.nanoTime() > deadline) {
                return false
            }
            Thread.sleep(20)
        }
        return true
    }

    private fun scheduleReadyTask() {
        val id = UUID.randomUUID().toString()
        scheduler.schedule(
            ScheduleRequest.builder<Any>().type(Task.Type.WORKFLOW).id(id).dedupToken(id).payload(null).build(),
        )
    }

    private fun readyTask(): Task<Any> {
        val id = UUID.randomUUID().toString()
        return Task.builder<Any>()
            .id(id)
            .type(Task.Type.WORKFLOW)
            .dedupToken(id)
            .createdAt(Instant.now())
            .runAfter(Instant.EPOCH)
            .executionTimeout(Duration.ofSeconds(30))
            .build()
    }

    /** A scheduler with nothing ready, whose gauges can be read. */
    private fun idleScheduler(): Scheduler {
        val mockScheduler = mock<Scheduler>()
        whenever(mockScheduler.fetch<Any>(any<Int>())).thenReturn(List.empty())
        whenever(mockScheduler.getFailedTasks<Any>()).thenReturn(List.empty())
        return mockScheduler
    }

    private fun createManager(
        scheduler: Scheduler,
        handler: TaskHandler,
        knobs: Knobs,
        maxConcurrentTasks: Int = SkipperSchedulerManager.NO_LIMIT,
        queue: SchedulerExecutionQueue = schedulerQueue,
        cluster: ClusterMembershipManager = SingleMemberClusterMembershipManager(),
        mainExecutor: ExecutorService = executor,
        executionTimeout: Duration = Duration.ofMinutes(5),
        metrics: Metrics = this.metrics,
    ): SkipperSchedulerManager =
        SkipperSchedulerManager(
            queue,
            scheduler,
            mainExecutor,
            taskHandlerExecutor,
            HashMap.of(Task.Type.WORKFLOW, handler),
            metrics,
            10,
            executionTimeout,
            leaseManager,
            featureGate,
            knobs,
            cluster,
            BucketPartitioner(),
            Duration.ofSeconds(5),
            inactiveCancelWatcher(),
            maxConcurrentTasks,
        )

    /** Holds every task until [release], recording how many ran at once. */
    private class BlockingHandler : TaskHandler {
        val started = AtomicInteger()
        val maxRunning = AtomicInteger()
        private val running = AtomicInteger()
        private val finished = AtomicInteger()
        private val gate = CountDownLatch(1)

        override fun handle(
            task: Task<*>,
            executorService: ExecutorService,
        ): CompletableFuture<Option<Instant>> {
            started.incrementAndGet()
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max)
            try {
                gate.await(30, TimeUnit.SECONDS)
            } finally {
                running.decrementAndGet()
                finished.incrementAndGet()
            }
            return CompletableFuture.completedFuture(Option.none())
        }

        fun release() = gate.countDown()

        fun awaitStarted(count: Int): Boolean = awaitCount(started, count)

        fun awaitFinished(count: Int): Boolean = awaitCount(finished, count)

        private fun awaitCount(
            counter: AtomicInteger,
            count: Int,
        ): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (counter.get() < count) {
                if (System.nanoTime() > deadline) {
                    return false
                }
                Thread.sleep(20)
            }
            return true
        }
    }

    /** Runs everything on a cached pool until [reject] is set, then rejects every submission. */
    private class RejectingExecutor : AbstractExecutorService() {
        @Volatile var reject = false
        private val delegate = Executors.newCachedThreadPool()

        override fun execute(command: Runnable) {
            if (reject) {
                throw RejectedExecutionException("rejecting for the test")
            }
            delegate.execute(command)
        }

        override fun shutdown() = delegate.shutdown()

        override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()

        override fun isShutdown(): Boolean = delegate.isShutdown

        override fun isTerminated(): Boolean = delegate.isTerminated

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = delegate.awaitTermination(timeout, unit)
    }

    private class CompletingHandler : TaskHandler {
        override fun handle(
            task: Task<*>,
            executorService: ExecutorService,
        ): CompletableFuture<Option<Instant>> = CompletableFuture.completedFuture(Option.none())
    }
}
