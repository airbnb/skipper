package com.airbnb.skipper.internal

import com.airbnb.skipper.OptimisticLockingError
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.TestUtils.getTestTask
import com.airbnb.skipper.internal.TestUtils.getWorkflowInstance
import com.airbnb.skipper.internal.cluster.BucketPartitioner
import com.airbnb.skipper.internal.cluster.SingleMemberClusterMembershipManager
import com.airbnb.skipper.internal.scheduler.LeaseRenewalManager
import com.airbnb.skipper.internal.scheduler.ScheduleRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.scheduler.TaskHandler
import com.airbnb.skipper.testutils.TestRuntime
import io.vavr.collection.HashMap
import io.vavr.control.Option
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Pins down what [SkipperSchedulerManager] does when the final versioned write for a finished task
 * meets a row whose version moved. Each test scripts the [Scheduler] mock into one interleaving:
 * our own lease renewal racing the write, a rerun bump on our leased row (with or without the renewer
 * having already adopted it), another worker holding the lease, the row being gone, and a row that
 * keeps moving.
 */
class SkipperSchedulerManagerFinishTaskTest {
    private lateinit var scheduler: Scheduler
    private lateinit var handler: TaskHandler
    private lateinit var leaseManager: LeaseRenewalManager
    private lateinit var manager: SkipperSchedulerManager

    private val task: Task<WorkflowInstance> = getTestTask(getWorkflowInstance()).toBuilder().version(4).build()

    @BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        val runtime = deps.runtime
        scheduler = mock()
        handler = mock()
        leaseManager = deps.leaseRenewalManager
        manager =
            SkipperSchedulerManager(
                deps.schedulerExecutionQueue,
                scheduler,
                mock(),
                mock(),
                HashMap.of(Task.Type.WORKFLOW, handler, Task.Type.TIMER, handler),
                runtime.metrics.get(),
                10,
                leaseManager,
                deps.featureGate,
                runtime.knobs.get(),
                SingleMemberClusterMembershipManager(),
                BucketPartitioner(),
                Duration.ofSeconds(5),
            )
        whenever(handler.handle(any(), any())).thenReturn(CompletableFuture.completedFuture(Option.none()))
    }

    private fun inFlight(
        known: Task<*>,
        rerunRequested: Boolean = false,
    ) = leaseManager.addTaskInFlight(
        LeaseRenewalManager.TaskInFlight(CompletableFuture<Any?>(), known.runAfter, known, rerunRequested),
    )

    @Suppress("UNCHECKED_CAST")
    private fun row(
        version: Int,
        runAfter: Instant = task.runAfter,
    ): Task<Any> = task.toBuilder().version(version).runAfter(runAfter).build() as Task<Any>

    private fun verifyRerunScheduledNow(): ScheduleRequest<Any> {
        val request = argumentCaptor<ScheduleRequest<Any>>()
        verify(scheduler, times(1)).schedule(request.capture())
        with(request.firstValue) {
            assertEquals(task.id, id)
            assertEquals(Task.Type.WORKFLOW, type)
            assertNull(payload, "the handler hydrates by id; a stale payload must not be carried")
            assertEquals(Instant.EPOCH, runAfter)
            assertFalse(isHonorActiveLeaseWhenOverwriting, "ours is the lease; the overwrite must not defer to it")
            assertFalse(inMemoryExecutionEnabled)
        }
        return request.firstValue
    }

    @Test
    fun ownRenewalRacingTheRemoveJustRetriesWithTheRenewedVersion() {
        val renewed: Task<Any> = row(version = 5, runAfter = task.runAfter.plusSeconds(60))
        inFlight(task)
        // The first remove (with version 4) fails because our renewer committed version 5 in between;
        // as the renewer would, the mock also updates the in-flight entry to the renewed task.
        doAnswer {
            leaseManager.addTaskInFlight(LeaseRenewalManager.TaskInFlight(CompletableFuture<Any?>(), renewed.runAfter, renewed))
            throw OptimisticLockingError("stale")
        }.doAnswer { null }.whenever(scheduler).remove<Any>(any())
        whenever(scheduler.getTask<Any>(task.id)).thenReturn(Option.of<Task<Any>>((renewed) as Task<Any>))

        manager.handleTask(task)

        val removed = argumentCaptor<Task<Any>>()
        verify(scheduler, times(2)).remove(removed.capture())
        assertEquals(4, removed.firstValue.version)
        assertEquals(5, removed.secondValue.version)
        verify(scheduler, never()).schedule<Any>(any())
    }

    @Test
    fun bumpOnOurLeasedRowIsServedByReschedulingNow() {
        inFlight(task)
        doThrow(OptimisticLockingError("bumped")).whenever(scheduler).remove<Any>(any())
        // Different version, identical run_after: a rerun request recorded on our leased row.
        whenever(scheduler.getTask<Any>(task.id)).thenReturn(Option.of(row(version = 5)))

        manager.handleTask(task)

        verify(scheduler, times(1)).remove<Any>(any())
        verifyRerunScheduledNow()
    }

    @Test
    fun bumpAlreadyAdoptedByTheRenewerSkipsTheRemoveEntirely() {
        // The renewer adopted the bump (version 5) and flagged it; the row no longer disagrees with us,
        // so a versioned remove would succeed and lose the request. The flag must win.
        inFlight(row(version = 5), rerunRequested = true)

        manager.handleTask(task)

        verify(scheduler, never()).remove<Any>(any())
        verifyRerunScheduledNow()
    }

    @Test
    fun leaseTakenByAnotherWorkerIsLeftAlone() {
        inFlight(task)
        doThrow(OptimisticLockingError("stale")).whenever(scheduler).remove<Any>(any())
        // A fetch always re-leases to now + leaseDuration, so run_after moved.
        whenever(scheduler.getTask<Any>(task.id)).thenReturn(Option.of(row(version = 5, runAfter = task.runAfter.plusSeconds(480))))

        manager.handleTask(task)

        verify(scheduler, times(1)).remove<Any>(any())
        verify(scheduler, never()).schedule<Any>(any())
        verify(scheduler, never()).rescheduleForRetry<Any>(any(), any())
    }

    @Test
    fun rowRemovedElsewhereIsANoOp() {
        inFlight(task)
        doThrow(OptimisticLockingError("gone")).whenever(scheduler).remove<Any>(any())
        whenever(scheduler.getTask<Any>(task.id)).thenReturn(Option.none())

        manager.handleTask(task)

        verify(scheduler, times(1)).remove<Any>(any())
        verify(scheduler, never()).schedule<Any>(any())
    }

    @Test
    fun rowThatKeepsMovingIsLeftToLeaseExpiryAfterBoundedAttempts() {
        inFlight(task)
        // Every attempt: the write fails and the row already shows our in-flight version, i.e. it looks
        // like our own renewal each time. The manager must give up rather than spin.
        doThrow(OptimisticLockingError("moved")).whenever(scheduler).remove<Any>(any())
        whenever(scheduler.getTask<Any>(task.id)).thenReturn(Option.of(row(version = 4)))

        manager.handleTask(task)

        verify(scheduler, times(3)).remove<Any>(any())
        verify(scheduler, never()).schedule<Any>(any())
    }

    @Test
    fun nonWorkflowTaskWithRerunRequestFallsBackToLeaseExpiry() {
        val timer = task.toBuilder().type(Task.Type.TIMER).build()
        inFlight(timer, rerunRequested = true)

        manager.handleTask(timer)

        // Its payload cannot be dropped, so it is neither removed (that would lose the request) nor
        // rescheduled with a null payload; the surviving row runs when the lease expires.
        verify(scheduler, never()).remove<Any>(any())
        verify(scheduler, never()).schedule<Any>(any())
    }

    @Test
    fun bumpDuringRetryReschedulingIsAlsoServedNow() {
        val retryAt = Instant.EPOCH.plusSeconds(30)
        whenever(handler.handle(any(), any())).thenReturn(CompletableFuture.completedFuture(Option.of(retryAt)))
        inFlight(task)
        doThrow(OptimisticLockingError("bumped")).whenever(scheduler).rescheduleForRetry<Any>(any(), eq(retryAt))
        whenever(scheduler.getTask<Any>(task.id)).thenReturn(Option.of(row(version = 5)))

        manager.handleTask(task)

        verify(scheduler, times(1)).rescheduleForRetry<Any>(any(), eq(retryAt))
        verify(scheduler, never()).remove<Any>(any())
        verifyRerunScheduledNow()
    }
}
