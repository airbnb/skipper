package com.airbnb.skipper.internal

import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.Knobs
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.TestUtils.getTestTask
import com.airbnb.skipper.internal.TestUtils.getWorkflowInstance
import com.airbnb.skipper.internal.cluster.BucketPartitioner
import com.airbnb.skipper.internal.cluster.ClusterMembershipManager
import com.airbnb.skipper.internal.cluster.SingleMemberClusterMembershipManager
import com.airbnb.skipper.internal.scheduler.LeaseRenewalManager
import com.airbnb.skipper.internal.scheduler.ScheduleRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.SchedulerExecutionQueue
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.scheduler.TaskHandler
import com.airbnb.skipper.testutils.TestRuntime
import io.vavr.collection.HashMap
import io.vavr.collection.Map
import io.vavr.control.Option
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SkipperSchedulerManagerTest {
    private lateinit var mockTaskHandler: TaskHandler
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val taskHandlerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val completedToken = "done!"
    private lateinit var metrics: Metrics
    private lateinit var schedulerQueue: SchedulerExecutionQueue
    private lateinit var scheduler: Scheduler
    private lateinit var leaseManager: LeaseRenewalManager
    private lateinit var leaseDuration: Duration
    private var clock: Clock = Clock.systemUTC()
    private lateinit var featureGate: FeatureGate
    private lateinit var knobs: Knobs

    @BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        deps.setClock(clock)
        val runtime = deps.getRuntime()
        metrics = runtime.metrics.get()
        featureGate = deps.featureGate
        knobs = runtime.knobs.get()

        schedulerQueue = deps.schedulerExecutionQueue
        scheduler = deps.scheduler
        leaseManager = deps.leaseRenewalManager
        leaseDuration = deps.config.schedulerTaskLeaseDuration

        mockTaskHandler = mock()
    }

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
        taskHandlerExecutor.shutdownNow()
    }

    @Test
    @Throws(Exception::class)
    fun testStart() {
        val future = CompletableFuture<Task<*>>()
        val handlers: Map<Task.Type, TaskHandler> = HashMap.of(Task.Type.WORKFLOW, DemoHandler(future))
        val skipperSchedulerManager =
            SkipperSchedulerManager(
                schedulerQueue,
                scheduler,
                executor,
                taskHandlerExecutor,
                handlers,
                metrics,
                10,
                leaseManager,
                featureGate,
                knobs,
                SingleMemberClusterMembershipManager(),
                BucketPartitioner(),
                TEST_GRACEFUL_SHUTDOWN_TIMEOUT,
            )

        skipperSchedulerManager.start()
        val id = UUID.randomUUID().toString()
        val scheduledTask =
            scheduler.schedule(
                ScheduleRequest.builder<Any>()
                    .type(Task.Type.WORKFLOW)
                    .id(id)
                    .dedupToken(id)
                    .payload(null)
                    .build(),
            )
        val result = future.get(5, TimeUnit.SECONDS)
        assertEquals(scheduledTask.id, result.id)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun testHandleWhenTaskSucceeds(leaseRenewalEnabled: Boolean) {
        whenever(featureGate.isEnabled(FeatureGate.Keys.AUTOMATIC_LEASE_RENEWAL))
            .thenReturn(leaseRenewalEnabled)
        val mockScheduler = mock<Scheduler>()
        val manager =
            SkipperSchedulerManager(
                schedulerQueue,
                mockScheduler,
                mock(),
                mock(),
                HashMap.of(Task.Type.WORKFLOW, mockTaskHandler),
                metrics,
                10,
                leaseManager,
                featureGate,
                knobs,
                SingleMemberClusterMembershipManager(),
                BucketPartitioner(),
                TEST_GRACEFUL_SHUTDOWN_TIMEOUT,
            )
        whenever(mockTaskHandler.handle(any(), any()))
            .thenReturn(CompletableFuture.completedFuture(Option.none()))
        val instance = getWorkflowInstance()
        manager.handleTask(getTestTask(instance))
        verify(mockScheduler, times(0)).schedule<Any>(any())
        verify(mockScheduler, times(1)).remove<Any>(any())
        verify(mockScheduler, times(0)).markAsFailed<Any>(any(), any())
        verify(mockScheduler, times(0)).rescheduleForRetry<Any>(any(), any())
        assertEquals(0, leaseManager.totalTasksInFlight)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun testHandleWhenRetryIsScheduled(leaseRenewalEnabled: Boolean) {
        whenever(featureGate.isEnabled(FeatureGate.Keys.AUTOMATIC_LEASE_RENEWAL))
            .thenReturn(leaseRenewalEnabled)
        val mockScheduler = mock<Scheduler>()
        val manager =
            SkipperSchedulerManager(
                schedulerQueue,
                mockScheduler,
                mock(),
                mock(),
                HashMap.of(Task.Type.WORKFLOW, mockTaskHandler),
                metrics,
                10,
                leaseManager,
                featureGate,
                knobs,
                SingleMemberClusterMembershipManager(),
                BucketPartitioner(),
                TEST_GRACEFUL_SHUTDOWN_TIMEOUT,
            )
        val retryTime = Instant.EPOCH.plusSeconds(1)
        whenever(mockTaskHandler.handle(any(), any()))
            .thenReturn(CompletableFuture.completedFuture(Option.of(retryTime)))
        val task = getTestTask(getWorkflowInstance())
        manager.handleTask(task)
        verify(mockScheduler, times(0)).schedule<Any>(any())
        verify(mockScheduler, times(0)).remove<Any>(any())
        verify(mockScheduler, times(0)).markAsFailed<Any>(any(), any())
        verify(mockScheduler, times(1)).rescheduleForRetry<WorkflowInstance>(eq(task), eq(retryTime))
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun testHandleWhenHandlerReturnsException(leaseRenewalEnabled: Boolean) {
        whenever(featureGate.isEnabled(FeatureGate.Keys.AUTOMATIC_LEASE_RENEWAL))
            .thenReturn(leaseRenewalEnabled)
        val mockScheduler = mock<Scheduler>()
        val manager =
            SkipperSchedulerManager(
                schedulerQueue,
                mockScheduler,
                mock(),
                mock(),
                HashMap.of(Task.Type.WORKFLOW, mockTaskHandler),
                metrics,
                10,
                leaseManager,
                featureGate,
                knobs,
                SingleMemberClusterMembershipManager(),
                BucketPartitioner(),
                TEST_GRACEFUL_SHUTDOWN_TIMEOUT,
            )
        whenever(mockTaskHandler.handle(any(), any()))
            .thenReturn(
                CompletableFuture.supplyAsync {
                    throw IllegalStateException("test")
                },
            )
        val task = getTestTask(getWorkflowInstance())
        manager.handleTask(task)
        verify(mockScheduler, times(0)).schedule<Any>(any())
        verify(mockScheduler, times(1)).remove<WorkflowInstance>(eq(task))
        verify(mockScheduler, times(0)).markAsFailed<Any>(any(), any())
    }

    @Test
    fun testHandleWhenNoTaskHandleAvailable() {
        val mockScheduler = mock<Scheduler>()
        val manager =
            SkipperSchedulerManager(
                schedulerQueue,
                mockScheduler,
                mock(),
                mock(),
                HashMap.empty(),
                metrics,
                10,
                leaseManager,
                featureGate,
                knobs,
                SingleMemberClusterMembershipManager(),
                BucketPartitioner(),
                TEST_GRACEFUL_SHUTDOWN_TIMEOUT,
            )
        whenever(mockTaskHandler.handle(any(), any()))
            .thenReturn(
                CompletableFuture.supplyAsync {
                    throw IllegalStateException("test")
                },
            )
        val task = getTestTask(getWorkflowInstance())
        manager.handleTask(task)
        verify(mockScheduler, times(0)).schedule<Any>(any())
        verify(mockScheduler, times(1)).remove<WorkflowInstance>(eq(task))
        verify(mockScheduler, times(0)).markAsFailed<Any>(any(), any())
    }

    @Test
    fun testHandleWhenMaxRetriesAreReached() {
        val maxRetries = 10
        val mockScheduler = mock<Scheduler>()
        val manager =
            SkipperSchedulerManager(
                schedulerQueue,
                mockScheduler,
                mock(),
                mock(),
                HashMap.empty(),
                metrics,
                maxRetries,
                leaseManager,
                featureGate,
                knobs,
                SingleMemberClusterMembershipManager(),
                BucketPartitioner(),
                TEST_GRACEFUL_SHUTDOWN_TIMEOUT,
            )
        val task = getTestTask(getWorkflowInstance()).toBuilder().retryCount(maxRetries + 1).build()
        manager.handleTask(task)
        verify(mockScheduler, times(0)).schedule<Any>(any())
        verify(mockScheduler, times(1)).markAsFailed<WorkflowInstance>(eq(task), any())
    }

    @Test
    fun testGetMaxTasksWithStaticFetchSize() {
        val mockKnobs = mock<Knobs>()
        whenever(mockKnobs.getInteger(Knobs.Keys.SCHEDULER_FETCH_COUNT)).thenReturn(Optional.of(10))

        val manager = createManager(mockKnobs)
        assertEquals(10, manager.getMaxTasks())
    }

    @Test
    fun testGetMaxTasksWithDefaultFetchSize() {
        val mockKnobs = mock<Knobs>()
        whenever(mockKnobs.getInteger(Knobs.Keys.SCHEDULER_FETCH_COUNT)).thenReturn(Optional.empty())

        val manager = createManager(mockKnobs)
        assertEquals(5, manager.getMaxTasks())
    }

    @Test
    fun testGetMaxTasksWithDynamicFetchSizeEmptyBacklog() {
        val mockKnobs = mock<Knobs>()
        whenever(mockKnobs.getInteger(Knobs.Keys.SCHEDULER_FETCH_COUNT)).thenReturn(Optional.of(-1))

        val manager = createManager(mockKnobs)
        manager.schedulerBacklogSize.set(0)
        assertEquals(1, manager.getMaxTasks())
    }

    @Test
    fun testGetMaxTasksWithDynamicFetchSizeSmallBacklog() {
        val mockKnobs = mock<Knobs>()
        whenever(mockKnobs.getInteger(Knobs.Keys.SCHEDULER_FETCH_COUNT)).thenReturn(Optional.of(-1))

        val manager = createManager(mockKnobs)
        manager.schedulerBacklogSize.set(16)
        assertEquals(2, manager.getMaxTasks())
    }

    @Test
    fun testGetMaxTasksWithDynamicFetchSizeLargeBacklog() {
        val mockKnobs = mock<Knobs>()
        whenever(mockKnobs.getInteger(Knobs.Keys.SCHEDULER_FETCH_COUNT)).thenReturn(Optional.of(-1))

        val manager = createManager(mockKnobs)
        manager.schedulerBacklogSize.set(1000000)
        assertEquals(20, manager.getMaxTasks())
    }

    private fun createManager(mockKnobs: Knobs): SkipperSchedulerManager =
        SkipperSchedulerManager(
            schedulerQueue,
            mock(),
            mock(),
            mock(),
            HashMap.empty(),
            metrics,
            10,
            leaseManager,
            featureGate,
            mockKnobs,
            SingleMemberClusterMembershipManager(),
            BucketPartitioner(),
            TEST_GRACEFUL_SHUTDOWN_TIMEOUT,
        )

    @Test
    @Throws(Exception::class)
    fun testStopGracefulShutdownSuccess() {
        val mockScheduler = mock<Scheduler>()
        val mockMainExecutor = mock<ExecutorService>()
        val mockTaskHandlerExecutor = mock<ExecutorService>()
        val mockClusterMembership = mock<ClusterMembershipManager>()

        // Simulate successful termination within timeout
        whenever(mockMainExecutor.awaitTermination(any<Long>(), any<TimeUnit>())).thenReturn(true)
        whenever(mockTaskHandlerExecutor.awaitTermination(any<Long>(), any<TimeUnit>())).thenReturn(true)

        val manager =
            SkipperSchedulerManager(
                schedulerQueue,
                mockScheduler,
                mockMainExecutor,
                mockTaskHandlerExecutor,
                HashMap.empty(),
                metrics,
                10,
                leaseManager,
                featureGate,
                knobs,
                mockClusterMembership,
                BucketPartitioner(),
                Duration.ofSeconds(5),
            )

        manager.stop()

        // Verify graceful shutdown was initiated
        verify(mockMainExecutor).shutdown()
        verify(mockTaskHandlerExecutor).shutdown()

        // Verify awaitTermination was called
        verify(mockMainExecutor).awaitTermination(any<Long>(), any<TimeUnit>())
        verify(mockTaskHandlerExecutor).awaitTermination(any<Long>(), any<TimeUnit>())

        // Verify shutdownNow() was NOT called (graceful shutdown succeeded)
        verify(mockMainExecutor, times(0)).shutdownNow()
        verify(mockTaskHandlerExecutor, times(0)).shutdownNow()

        // Verify cluster membership was stopped
        verify(mockClusterMembership).stop()
    }

    @Test
    @Throws(Exception::class)
    fun testStopGracefulShutdownTimeout() {
        val mockScheduler = mock<Scheduler>()
        val mockMainExecutor = mock<ExecutorService>()
        val mockTaskHandlerExecutor = mock<ExecutorService>()
        val mockClusterMembership = mock<ClusterMembershipManager>()

        // Simulate timeout - main executor times out, task handler succeeds
        whenever(mockMainExecutor.awaitTermination(any<Long>(), any<TimeUnit>())).thenReturn(false)
        whenever(mockTaskHandlerExecutor.awaitTermination(any<Long>(), any<TimeUnit>())).thenReturn(true)

        val manager =
            SkipperSchedulerManager(
                schedulerQueue,
                mockScheduler,
                mockMainExecutor,
                mockTaskHandlerExecutor,
                HashMap.empty(),
                metrics,
                10,
                leaseManager,
                featureGate,
                knobs,
                mockClusterMembership,
                BucketPartitioner(),
                Duration.ofSeconds(5),
            )

        manager.stop()

        // Verify graceful shutdown was initiated
        verify(mockMainExecutor).shutdown()
        verify(mockTaskHandlerExecutor).shutdown()

        // Verify awaitTermination was called
        verify(mockMainExecutor).awaitTermination(any<Long>(), any<TimeUnit>())
        verify(mockTaskHandlerExecutor).awaitTermination(any<Long>(), any<TimeUnit>())

        // Verify shutdownNow() was called on the timed-out executor
        verify(mockMainExecutor, times(1)).shutdownNow()
        verify(mockTaskHandlerExecutor, times(0)).shutdownNow()
    }

    @Test
    @Throws(Exception::class)
    fun testStopGracefulShutdownBothTimeout() {
        val mockScheduler = mock<Scheduler>()
        val mockMainExecutor = mock<ExecutorService>()
        val mockTaskHandlerExecutor = mock<ExecutorService>()
        val mockClusterMembership = mock<ClusterMembershipManager>()

        // Simulate both executors timing out
        whenever(mockMainExecutor.awaitTermination(any<Long>(), any<TimeUnit>())).thenReturn(false)
        whenever(mockTaskHandlerExecutor.awaitTermination(any<Long>(), any<TimeUnit>()))
            .thenReturn(false)

        val manager =
            SkipperSchedulerManager(
                schedulerQueue,
                mockScheduler,
                mockMainExecutor,
                mockTaskHandlerExecutor,
                HashMap.empty(),
                metrics,
                10,
                leaseManager,
                featureGate,
                knobs,
                mockClusterMembership,
                BucketPartitioner(),
                Duration.ofSeconds(5),
            )

        manager.stop()

        // Verify shutdownNow() was called on both executors
        verify(mockMainExecutor, times(1)).shutdownNow()
        verify(mockTaskHandlerExecutor, times(1)).shutdownNow()
    }

    @Test
    @Throws(Exception::class)
    fun testStopHandlesInterruptedException() {
        val mockScheduler = mock<Scheduler>()
        val mockMainExecutor = mock<ExecutorService>()
        val mockTaskHandlerExecutor = mock<ExecutorService>()
        val mockClusterMembership = mock<ClusterMembershipManager>()

        // Simulate InterruptedException during awaitTermination
        whenever(mockMainExecutor.awaitTermination(any<Long>(), any<TimeUnit>()))
            .thenThrow(InterruptedException("Test interrupt"))

        val manager =
            SkipperSchedulerManager(
                schedulerQueue,
                mockScheduler,
                mockMainExecutor,
                mockTaskHandlerExecutor,
                HashMap.empty(),
                metrics,
                10,
                leaseManager,
                featureGate,
                knobs,
                mockClusterMembership,
                BucketPartitioner(),
                Duration.ofSeconds(5),
            )

        manager.stop()

        // Verify graceful shutdown was initiated
        verify(mockMainExecutor).shutdown()
        verify(mockTaskHandlerExecutor).shutdown()

        // Verify shutdownNow() was called on both executors due to interruption
        verify(mockMainExecutor, times(1)).shutdownNow()
        verify(mockTaskHandlerExecutor, times(1)).shutdownNow()
    }

    @Test
    @Throws(Exception::class)
    fun testStopWithClusterMembershipException() {
        val mockScheduler = mock<Scheduler>()
        val mockMainExecutor = mock<ExecutorService>()
        val mockTaskHandlerExecutor = mock<ExecutorService>()
        val mockClusterMembership = mock<ClusterMembershipManager>()

        // Simulate exception when stopping cluster membership
        doThrow(RuntimeException("Cluster stop failed")).whenever(mockClusterMembership).stop()

        // Executors should still shutdown gracefully
        whenever(mockMainExecutor.awaitTermination(any<Long>(), any<TimeUnit>())).thenReturn(true)
        whenever(mockTaskHandlerExecutor.awaitTermination(any<Long>(), any<TimeUnit>())).thenReturn(true)

        val manager =
            SkipperSchedulerManager(
                schedulerQueue,
                mockScheduler,
                mockMainExecutor,
                mockTaskHandlerExecutor,
                HashMap.empty(),
                metrics,
                10,
                leaseManager,
                featureGate,
                knobs,
                mockClusterMembership,
                BucketPartitioner(),
                Duration.ofSeconds(5),
            )

        // Should not throw exception - should handle gracefully
        manager.stop()

        // Verify executors were still shutdown despite cluster membership failure
        verify(mockMainExecutor).shutdown()
        verify(mockTaskHandlerExecutor).shutdown()
        verify(mockMainExecutor).awaitTermination(any<Long>(), any<TimeUnit>())
        verify(mockTaskHandlerExecutor).awaitTermination(any<Long>(), any<TimeUnit>())
    }

    private class DemoHandler(private val future: CompletableFuture<Task<*>>) : TaskHandler {
        override fun handle(
            task: Task<*>,
            executorService: ExecutorService,
        ): CompletableFuture<Option<Instant>> {
            future.complete(task)
            return CompletableFuture.completedFuture(Option.none())
        }
    }

    companion object {
        private val TEST_GRACEFUL_SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
