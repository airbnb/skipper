package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.TestUtils
import com.airbnb.skipper.testutils.TestRuntime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class QueueBasedSchedulerTest {
    private lateinit var scheduler: SchedulerExecutionQueue
    private lateinit var persistentScheduler: Scheduler

    private val mockClock: Clock = mock()
    private val leaseDuration: Duration = Duration.ofMinutes(1)

    @BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        deps.setClock(mockClock)
        deps.config.schedulerTaskLeaseDuration = leaseDuration
        scheduler = deps.schedulerExecutionQueue
        persistentScheduler = deps.scheduler
    }

    @Test
    @Throws(Exception::class)
    fun testScheduleImmediately() {
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val workflowInstance = TestUtils.getWorkflowInstance()
        val task =
            scheduler.schedule(
                ScheduleRequest.builder<WorkflowInstance>()
                    .id(workflowInstance.workflowId)
                    .dedupToken(workflowInstance.workflowId)
                    .type(Task.Type.WORKFLOW)
                    .payload(workflowInstance)
                    .build()
            )
        assertEquals(1, scheduler.getQueue(Task.Type.WORKFLOW).size())
        val takenTask = scheduler.take<WorkflowInstance>(Task.Type.WORKFLOW)
        assertEquals(task, takenTask)
        assertEquals(Instant.EPOCH.plus(scheduler.leaseDuration), task.runAfter)
        assertEquals(0, scheduler.getQueue(Task.Type.WORKFLOW).size())
        // Now let's move the clock to after the lease time end for the task,
        // this should result in the task being picked and placed back into
        // the execution queue.
        whenever(mockClock.instant())
            .thenReturn(Instant.EPOCH.plus(scheduler.leaseDuration).plus(Duration.ofSeconds(1)))
        persistentScheduler.fetch<Any>(10).forEach { t -> scheduler.add(t) }
        val newTask = scheduler.take<WorkflowInstance>(Task.Type.WORKFLOW)
        assertEquals(task.id, newTask.id)
    }

    @Test
    fun testScheduleWhenInMemoryExecutionDisabled() {
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val workflowInstance = TestUtils.getWorkflowInstance()
        scheduler.schedule(
            ScheduleRequest.builder<WorkflowInstance>()
                .id(workflowInstance.workflowId)
                .dedupToken(workflowInstance.workflowId)
                .type(Task.Type.WORKFLOW)
                .payload(workflowInstance)
                .inMemoryExecutionEnabled(false)
                .build()
        )
        assertEquals(0, scheduler.getQueue(Task.Type.WORKFLOW).size())
        // Now let's move the clock to after the lease time end for the task,
        // this should result in the task being picked and placed back into
        // the execution queue.
        assertEquals(1, persistentScheduler.realSize())
    }

    @Test
    fun testScheduleAfterLeaseOnlyWritesTheRowDueAfterTheLease() {
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val task = scheduler.scheduleAfterLease(workflowTaskRequest())
        assertEquals(0, scheduler.getQueue(Task.Type.WORKFLOW).size())
        assertEquals(Instant.EPOCH.plus(leaseDuration), task.runAfter)
        assertEquals(0, persistentScheduler.fetch<Any>(10).size(), "the row is not due before the lease passes")
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH.plus(leaseDuration).plus(Duration.ofSeconds(1)))
        assertEquals(listOf(task.id), persistentScheduler.fetch<Any>(10).map { it.id }.toJavaList())
    }

    /** A start schedules the task in memory on one node while a retried start schedules it after the lease on another. */
    @Test
    fun testInMemoryScheduleFollowedByScheduleAfterLeaseRunsTheTaskOnce() {
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val otherNode = SchedulerExecutionQueue(persistentScheduler, mockClock, leaseDuration)
        scheduler.schedule(workflowTaskRequest())
        otherNode.scheduleAfterLease(workflowTaskRequest())
        assertRunsOnlyInMemoryOn(scheduler, otherNode)
    }

    @Test
    fun testScheduleAfterLeaseFollowedByInMemoryScheduleRunsTheTaskOnce() {
        whenever(mockClock.instant()).thenReturn(Instant.EPOCH)
        val otherNode = SchedulerExecutionQueue(persistentScheduler, mockClock, leaseDuration)
        otherNode.scheduleAfterLease(workflowTaskRequest())
        scheduler.schedule(workflowTaskRequest())
        assertRunsOnlyInMemoryOn(scheduler, otherNode)
    }

    private fun assertRunsOnlyInMemoryOn(
        node: SchedulerExecutionQueue,
        otherNode: SchedulerExecutionQueue,
    ) {
        assertEquals(1, node.getQueue(Task.Type.WORKFLOW).size())
        assertEquals(0, otherNode.getQueue(Task.Type.WORKFLOW).size())
        assertEquals(0, persistentScheduler.fetch<Any>(10).size(), "the persistent row must not be due while the task runs in memory")
    }

    private fun workflowTaskRequest(): ScheduleRequest<WorkflowInstance> {
        val workflowInstance = TestUtils.getWorkflowInstance()
        return ScheduleRequest.builder<WorkflowInstance>()
            .id(workflowInstance.workflowId)
            .dedupToken(workflowInstance.workflowId)
            .type(Task.Type.WORKFLOW)
            .payload(workflowInstance)
            .honorActiveLeaseWhenOverwriting(true)
            .build()
    }
}
