package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.Metrics
import com.airbnb.skipper.OptimisticLockingError
import com.airbnb.skipper.internal.TestUtils
import com.airbnb.skipper.testutils.TestRuntime
import io.vavr.control.Option
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LeaseRenewalManagerTest {
    private lateinit var metrics: Metrics
    private val leaseRenewalGracePeriod: Duration = Duration.ofSeconds(5)

    @BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        metrics = deps.runtime.metrics.get()
    }

    @Test
    fun testAttemptToRenewTask() {
        val mockScheduler = mock<Scheduler>()
        val mockClock = mock<Clock>()
        val leaseDuration = Duration.ofDays(1)
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val task = TestUtils.getTestTask(TestUtils.getWorkflowInstance())
        whenever(mockScheduler.renewLease<Any>(any())).thenAnswer { invocation ->
            val inputTask = invocation.getArgument<Task<*>>(0)
            inputTask.toBuilder()
                .version(inputTask.version + 1)
                .runAfter(inputTask.runAfter.plus(leaseDuration))
                .build()
        }
        val leaseManager =
            LeaseRenewalManager(mockScheduler, mockClock, metrics, leaseRenewalGracePeriod)
        // First scenario: task is in flight but not about to expire.
        var leaseExpire = mockClock.instant().plus(Duration.ofSeconds(10))
        leaseManager.addTaskInFlight(
            LeaseRenewalManager.TaskInFlight(CompletableFuture<Any?>(), leaseExpire, task)
        )
        var result: Option<Task<*>> = leaseManager.attemptToRenewOneTask()
        assertFalse(result.isDefined) // return false means no task was renewed
        // Task should have not been updated
        assertEquals(task, leaseManager.tasksInFlight[task.id]!!.task)
        leaseManager.tasksInFlight.clear()
        // Second scenario: task is in flight and about to expire.
        leaseExpire = mockClock.instant()
        leaseManager.addTaskInFlight(
            LeaseRenewalManager.TaskInFlight(CompletableFuture<Any?>(), leaseExpire, task)
        )
        result = leaseManager.attemptToRenewOneTask()
        assertTrue(result.isDefined)
        // Task should have been updated
        val updatedTask = leaseManager.tasksInFlight[task.id]!!.task
        assertEquals(task.version + 1, updatedTask.version)
        assertEquals(
            updatedTask.runAfter,
            leaseManager.tasksInFlight[task.id]!!.leaseExpiration
        )
        // Attempting to renew again should now return false
        result = leaseManager.attemptToRenewOneTask()
        assertFalse(result.isDefined)
        // Now fast-forward to past the lease expiration of the renewed task
        whenever(mockClock.instant()).thenReturn(updatedTask.runAfter)
        // Also add another task that is about to expire as well, but one second after
        val task2 =
            TestUtils.getTestTask(TestUtils.getWorkflowInstance()).toBuilder()
                .id(UUID.randomUUID().toString())
                .runAfter(updatedTask.runAfter.plusSeconds(1))
                .build()
        leaseManager.addTaskInFlight(
            LeaseRenewalManager.TaskInFlight(CompletableFuture<Any?>(), task2.runAfter, task2)
        )
        // Attempting to renew should renew the first task
        result = leaseManager.attemptToRenewOneTask()
        assertTrue(result.isDefined)
        // Attempting to get the next task should return the second task
        result = leaseManager.attemptToRenewOneTask()
        assertTrue(result.isDefined)
        // Once more should return false
        result = leaseManager.attemptToRenewOneTask()
        assertFalse(result.isDefined)
    }

    @Test
    fun renewalConflictWithOurLeaseIntactAdoptsTheBumpedVersion() {
        // A rerun was recorded on our leased row: version moved, run_after did not. Renewal must not
        // treat that as a lost lease (which would cancel the in-flight run and let the row re-run
        // concurrently at expiry); it adopts the new version and remembers the request.
        val mockScheduler = mock<Scheduler>()
        val mockClock = mock<Clock>()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val task = TestUtils.getTestTask(TestUtils.getWorkflowInstance())
        val bumped = task.toBuilder().version(task.version + 1).build()
        whenever(mockScheduler.renewLease<Any>(any())).thenThrow(OptimisticLockingError("bumped"))
        @Suppress("UNCHECKED_CAST")
        whenever(mockScheduler.getTask<Any>(task.id)).thenReturn(Option.of(bumped as Task<Any>))
        val leaseManager = LeaseRenewalManager(mockScheduler, mockClock, metrics, leaseRenewalGracePeriod)
        val handle = CompletableFuture<Any?>()
        leaseManager.addTaskInFlight(LeaseRenewalManager.TaskInFlight(handle, mockClock.instant(), task))

        val result = leaseManager.attemptToRenewOneTask()

        assertFalse(result.isDefined)
        assertFalse(handle.isCancelled, "the in-flight run must not be cancelled")
        val entry = leaseManager.tasksInFlight[task.id]!!
        assertEquals(bumped.version, entry.task.version)
        assertTrue(entry.rerunRequested)
    }

    @Test
    fun laterSuccessfulRenewalKeepsTheRerunRequestedMark() {
        val mockScheduler = mock<Scheduler>()
        val mockClock = mock<Clock>()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val task = TestUtils.getTestTask(TestUtils.getWorkflowInstance())
        whenever(mockScheduler.renewLease<Any>(any())).thenAnswer { invocation ->
            val input = invocation.getArgument<Task<*>>(0)
            input.toBuilder().version(input.version + 1).runAfter(input.runAfter.plusSeconds(60)).build()
        }
        val leaseManager = LeaseRenewalManager(mockScheduler, mockClock, metrics, leaseRenewalGracePeriod)
        leaseManager.addTaskInFlight(
            LeaseRenewalManager.TaskInFlight(CompletableFuture<Any?>(), mockClock.instant(), task, rerunRequested = true),
        )

        assertTrue(leaseManager.attemptToRenewOneTask().isDefined)

        // The renewal wrote our version back over the row, erasing the bump there; the in-process mark is
        // now the only trace of the request and must survive.
        assertTrue(leaseManager.tasksInFlight[task.id]!!.rerunRequested)
    }

    @Test
    fun renewalConflictWithLeaseTakenElsewhereStillCancelsTheRun() {
        val mockScheduler = mock<Scheduler>()
        val mockClock = mock<Clock>()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val task = TestUtils.getTestTask(TestUtils.getWorkflowInstance())
        val reLeased = task.toBuilder().version(task.version + 1).runAfter(task.runAfter.plusSeconds(480)).build()
        whenever(mockScheduler.renewLease<Any>(any())).thenThrow(OptimisticLockingError("stale"))
        @Suppress("UNCHECKED_CAST")
        whenever(mockScheduler.getTask<Any>(task.id)).thenReturn(Option.of(reLeased as Task<Any>))
        val leaseManager = LeaseRenewalManager(mockScheduler, mockClock, metrics, leaseRenewalGracePeriod)
        val handle = CompletableFuture<Any?>()
        leaseManager.addTaskInFlight(LeaseRenewalManager.TaskInFlight(handle, mockClock.instant(), task))

        assertFalse(leaseManager.attemptToRenewOneTask().isDefined)

        assertTrue(handle.isCancelled)
        assertNull(leaseManager.tasksInFlight[task.id])
    }

    @Test
    fun testRemove() {
        val leaseManager =
            LeaseRenewalManager(mock(), mock(), mock(), leaseRenewalGracePeriod)
        // Removing a non-existing task should be a no-op
        leaseManager.removeTaskInFlight("non-existing-id")
        assertTrue(leaseManager.tasksInFlight.isEmpty())
        // Removing an existing task should remove it
        val task = TestUtils.getTestTask(TestUtils.getWorkflowInstance())
        leaseManager.addTaskInFlight(
            LeaseRenewalManager.TaskInFlight(CompletableFuture<Any?>(), Instant.EPOCH, task)
        )
        assertFalse(leaseManager.tasksInFlight.isEmpty())
        leaseManager.removeTaskInFlight(task.id)
        assertTrue(leaseManager.tasksInFlight.isEmpty())
    }

    @Test
    fun testAttemptToRenewOneTaskWhenSchedulerFails() {
        val mockScheduler = mock<Scheduler>()
        val mockClock = mock<Clock>()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val task = TestUtils.getTestTask(TestUtils.getWorkflowInstance())
        whenever(mockScheduler.renewLease<Any>(any())).thenThrow(IllegalArgumentException("test"))
        val leaseManager =
            LeaseRenewalManager(mockScheduler, mockClock, metrics, leaseRenewalGracePeriod)
        val future: Future<*> = CompletableFuture<Any?>()
        leaseManager.addTaskInFlight(LeaseRenewalManager.TaskInFlight(future, Instant.EPOCH, task))
        assertEquals(1, leaseManager.tasksInFlight.size)
        val result: Option<Task<*>> = leaseManager.attemptToRenewOneTask()
        assertFalse(result.isDefined)
        assertEquals(0, leaseManager.tasksInFlight.size)
        assertTrue(future.isDone)
    }

    @Test
    fun testAttemptToRenewOneTaskWhenTaskFutureIsCompleted() {
        val mockScheduler = mock<Scheduler>()
        val mockClock = mock<Clock>()
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val task = TestUtils.getTestTask(TestUtils.getWorkflowInstance())
        val leaseManager =
            LeaseRenewalManager(mockScheduler, mockClock, metrics, leaseRenewalGracePeriod)
        val future: Future<*> = CompletableFuture.completedFuture(null)
        leaseManager.addTaskInFlight(LeaseRenewalManager.TaskInFlight(future, Instant.EPOCH, task))
        assertEquals(1, leaseManager.tasksInFlight.size)
        val result: Option<Task<*>> = leaseManager.attemptToRenewOneTask()
        assertFalse(result.isDefined)
        assertEquals(0, leaseManager.tasksInFlight.size)
        verify(mockScheduler, times(0)).renewLease<Any>(any())
    }
}
