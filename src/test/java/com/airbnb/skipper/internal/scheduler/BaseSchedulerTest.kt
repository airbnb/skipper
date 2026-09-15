package com.airbnb.skipper.internal.scheduler

import com.airbnb.skipper.OptimisticLockingError
import com.airbnb.skipper.internal.cluster.BucketPartitioner
import com.airbnb.skipper.internal.cluster.BucketRange
import io.vavr.collection.List
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HashSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

/**
 * Base test suite for all the scheduler implementations.
 *
 * All scheduler implementations must adhere to the specification defined by these set of tests.
 * Tests that are specific to every scheduler implementation should be defined in the concrete
 * scheduler test class.
 */
abstract class BaseSchedulerTest {
    @JvmField
    protected val mockClock: Clock = mock(Clock::class.java)

    @JvmField
    protected val leaseDuration: Duration = Duration.ofSeconds(1)

    protected abstract fun scheduler(): Scheduler

    @Test
    fun test() {
        val t1 = Instant.EPOCH
        val t2 = Instant.EPOCH.plusSeconds(1)
        val t3 = Instant.EPOCH.plusSeconds(2)
        val t4 = Instant.EPOCH.plusSeconds(3)
        var expectedVersion = 1

        whenever(mockClock.instant()).thenReturn(t1)
        val task =
            scheduler()
                .schedule(
                    ScheduleRequest.builder<String>()
                        .id("test")
                        .payload("payload")
                        .type(Task.Type.WORKFLOW)
                        .dedupToken("test")
                        .runAfter(null)
                        .build()
                )
        val task2 =
            scheduler()
                .schedule(
                    ScheduleRequest.builder<String>()
                        .id("test2")
                        .payload("payload")
                        .type(Task.Type.WORKFLOW)
                        .dedupToken("test2")
                        .runAfter(t3)
                        .build()
                )
        assertNotNull(task)
        assertEquals(t1, task.createdAt)
        assertEquals(t1, task.runAfter)
        assertEquals(expectedVersion++, task.version)
        assertEquals(0, task.retryCount)
        assertEquals(Task.Status.PENDING, task.status)
        assertEquals("test", task.dedupToken)
        assertEquals("test", task.id)
        assertEquals(Task.Type.WORKFLOW, task.type)
        assertEquals("payload", task.payload)
        assertEquals(t3, task2.runAfter)
        assertEquals(2, scheduler().realSize())
        // Check that the persisted tasks match the tasks returned by schedule
        val storedTask1 = scheduler().getTask<String>(task.id).get()
        assertEquals(task, storedTask1)
        val storedTask2 = scheduler().getTask<String>(task2.id).get()
        assertEquals(task2, storedTask2)
        // Now try to fetch
        val tasks = scheduler().fetch<String>(10)
        assertEquals(1, tasks.size())
        assertEquals(task.id, tasks.get(0).id)
        assertEquals(Task.Status.RUNNING, tasks.get(0).status)
        assertEquals(0, tasks.get(0).retryCount)
        assertEquals(expectedVersion++, tasks.get(0).version)
        assertEquals(2, scheduler().realSize())
        // Fetching again should return zero results
        assertTrue(scheduler().fetch<String>(10).isEmpty)
        // When advancing the clock by leaseDuration, the task should be returned
        whenever(mockClock.instant()).thenReturn(t2)
        val tasks2 = scheduler().fetch<String>(10)
        assertEquals(1, tasks2.size())
        assertEquals(task.id, tasks2.get(0).id)
        assertEquals(task.retryCount + 1, tasks2.get(0).retryCount)
        assertEquals(expectedVersion++, tasks2.get(0).version)
        // Now try to reschedule the task for t4 with the wrong version should result in an
        // OptimisticLockingError. Remember that fetch() actually updates the task's version
        assertThrows(OptimisticLockingError::class.java) { scheduler().rescheduleForRetry(task, t4) }
        // Trying to reschedule an invalid task should throw an IllegalArgumentException
        assertThrows(IllegalArgumentException::class.java) {
            scheduler().rescheduleForRetry(task.toBuilder().id("invalid").build(), t4)
        }
        // Now try rescheduling the right task
        scheduler().rescheduleForRetry(tasks2.get(0), t4)
        // Trying to fetch at this time should return zero results
        assertTrue(scheduler().fetch<String>(10).isEmpty)
        // Now, advancing the clock to t3 should only pick task2, but not task1
        whenever(mockClock.instant()).thenReturn(t3)
        val tasks3 = scheduler().fetch<String>(10)
        assertEquals(1, tasks3.size())
        assertEquals(task2.id, tasks3.get(0).id)
        // Remove task2 from the queue
        scheduler().remove(tasks3.get(0))
        assertTrue(scheduler().fetch<String>(10).isEmpty)
        assertEquals(1, scheduler().realSize())
        // Advance the clock to t4. We should only get the previously rescheduled task1, but
        // task2 should not be returned
        whenever(mockClock.instant()).thenReturn(t4)
        val tasks4 = scheduler().fetch<String>(10)
        assertEquals(1, tasks4.size())
        assertEquals(task.id, tasks4.get(0).id)
        assertEquals(1, scheduler().realSize())
        // Remove task
        scheduler().remove(tasks4.get(0))
        assertTrue(scheduler().fetch<String>(10).isEmpty)
        assertEquals(0, scheduler().realSize())
    }

    @Test
    fun testSchedulerWhenTaskAlreadyExists() {
        val t1 = Instant.EPOCH
        val t2 = Instant.EPOCH.plusSeconds(1)
        whenever(mockClock.instant()).thenReturn(t1)
        val task =
            scheduler()
                .schedule(
                    ScheduleRequest.builder<String>()
                        .id("test")
                        .payload("payload")
                        .type(Task.Type.WORKFLOW)
                        .dedupToken("test")
                        .runAfter(t1)
                        .build()
                )
        val updatedTask =
            scheduler()
                .schedule(
                    ScheduleRequest.builder<String>()
                        .id("test")
                        .payload("payload2")
                        .type(Task.Type.WORKFLOW)
                        .dedupToken("test")
                        .runAfter(t2)
                        .build()
                )
        // The task should've been overwritten
        assertEquals(1, scheduler().realSize())
        // Make sure the task returned by schedule and the actual task are the same
        assertEquals(updatedTask, scheduler().getTask<String>("test").get())
        assertEquals(2, updatedTask.version)
        assertEquals(t2, updatedTask.runAfter)
        assertEquals("payload2", updatedTask.payload)
        // Fetching should return zero results
        var tasks: List<Task<String>> = scheduler().fetch(10)
        assertTrue(tasks.isEmpty)
        // Now advance to t2 and fetch again
        whenever(mockClock.instant()).thenReturn(t2)
        tasks = scheduler().fetch(10)
        assertEquals(1, tasks.size())
        assertEquals("payload2", tasks.get(0).payload)
        assertEquals("test", tasks.get(0).id)
    }

    @Test
    fun testScheduleWhenHonorActiveLeaseIsActive() {
        val t1 = Instant.EPOCH
        val t2 = Instant.EPOCH.plusSeconds(10)
        whenever(mockClock.instant()).thenReturn(t1)
        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id("test")
                    .payload("payload")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken("test")
                    .runAfter(t1)
                    .build()
            )
        // Let's fetch the task 1 so that its status changes to lease active
        val tasks: List<Task<String>> = scheduler().fetch(10)
        assertEquals(1, tasks.size())
        assertTrue(tasks.get(0).hasActiveLease(mockClock.instant(), leaseDuration))

        val updatedTask =
            scheduler()
                .schedule(
                    ScheduleRequest.builder<String>()
                        .id("test")
                        .payload("payload2")
                        .type(Task.Type.WORKFLOW)
                        .dedupToken("test")
                        .runAfter(t2)
                        .honorActiveLeaseWhenOverwriting(true)
                        .build()
                )
        // The task shouldn't have been overwritten
        assertEquals(1, scheduler().realSize())
        // The task returned by schedule and the leased task should be the same
        assertEquals(updatedTask.runAfter, tasks.get(0).runAfter)
        assertEquals(updatedTask.version, tasks.get(0).version)
        assertEquals(updatedTask.id, tasks.get(0).id)
        assertEquals(updatedTask.payload, tasks.get(0).payload)
        assertEquals(updatedTask.status, tasks.get(0).status)
    }

    @Test
    fun testScheduleWhenHonorActiveLeaseIsActiveRecordsRerunWhenRequested() {
        val t1 = Instant.EPOCH
        whenever(mockClock.instant()).thenReturn(t1)
        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id("test")
                    .payload("payload")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken("test")
                    .runAfter(t1)
                    .build()
            )
        // Lease the task, as the handler thread would.
        val leased: Task<String> = scheduler().fetch<String>(10).get(0)
        assertTrue(leased.hasActiveLease(mockClock.instant(), leaseDuration))

        // A signal arrives while the lease is held and asks for a rerun, honouring the lease.
        val bumped =
            scheduler()
                .schedule(
                    ScheduleRequest.builder<String>()
                        .id("test")
                        .payload("payload2")
                        .type(Task.Type.WORKFLOW)
                        .dedupToken("test")
                        .runAfter(t1.plusSeconds(10))
                        .honorActiveLeaseWhenOverwriting(true)
                        .bumpVersionWhenHonoringLease(true)
                        .build()
                )
        // The lease itself is untouched: same runAfter, same status, same payload, one row.
        assertEquals(1, scheduler().realSize())
        assertEquals(leased.runAfter, bumped.runAfter)
        assertEquals(leased.status, bumped.status)
        assertEquals(leased.payload, bumped.payload)
        // ...but the version moved, so the lease holder's versioned remove now fails and the row survives.
        assertEquals(leased.version + 1, bumped.version)
        assertThrows(OptimisticLockingError::class.java) { scheduler().remove(leased) }
        assertEquals(1, scheduler().realSize())
        // Once the lease expires the surviving row is fetched again and the workflow re-runs.
        whenever(mockClock.instant()).thenReturn(t1.plus(leaseDuration).plusSeconds(1))
        assertEquals("test", scheduler().fetch<String>(10).get(0).id)
    }

    @Test
    fun testMarkJobAsFailed() {
        val t1 = Instant.EPOCH
        whenever(mockClock.instant()).thenReturn(t1)
        val task =
            scheduler()
                .schedule(
                    ScheduleRequest.builder<String>()
                        .id("test")
                        .payload("payload")
                        .type(Task.Type.WORKFLOW)
                        .dedupToken("test")
                        .runAfter(t1)
                        .build()
                )
        var tasks: List<Task<String>> = scheduler().fetch(10)
        assertEquals(1, tasks.size())
        // Trying to update a stale task will throw Optimistic lock error
        assertThrows(OptimisticLockingError::class.java) {
            scheduler().markAsFailed(task, "failed reason")
        }
        // Now try to mark the task as failed
        scheduler().markAsFailed(tasks.get(0), "failed reason")
        tasks = scheduler().fetch(10)
        assertTrue(tasks.isEmpty)
        // Attempting to remove a task that doesn't exist should throw an IllegalArgument
        assertThrows(IllegalArgumentException::class.java) {
            scheduler().markAsFailed(task.toBuilder().id("invalid").build(), "failed reason")
        }
    }

    @Test
    fun testRemoveTask() {
        val t1 = Instant.EPOCH
        whenever(mockClock.instant()).thenReturn(t1)
        val task =
            scheduler()
                .schedule(
                    ScheduleRequest.builder<String>()
                        .id("test")
                        .payload("payload")
                        .type(Task.Type.WORKFLOW)
                        .dedupToken("test")
                        .runAfter(t1)
                        .build()
                )
        var tasks: List<Task<String>> = scheduler().fetch(10)
        assertEquals(1, tasks.size())
        // Trying to remove a stale task will throw OptimisticLockError
        assertThrows(OptimisticLockingError::class.java) { scheduler().remove(task) }
        // Remove the task
        scheduler().remove(tasks.get(0))
        tasks = scheduler().fetch(10)
        assertTrue(tasks.isEmpty)
        // Attempting to remove a task that doesn't exist should be a no-op
        scheduler().remove(task.toBuilder().id("invalid").build())
    }

    @Test
    fun testTaskWithNullPayload() {
        val t1 = Instant.EPOCH
        whenever(mockClock.instant()).thenReturn(t1)
        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id("test")
                    .payload(null)
                    .type(Task.Type.WORKFLOW)
                    .dedupToken("test")
                    .runAfter(t1)
                    .build()
            )
        val tasks: List<Task<String>> = scheduler().fetch(10)
        assertEquals(1, tasks.size())
        assertFalse(tasks.get(0).hasPayload())
    }

    @Test
    fun testFetch() {
        var expectedVersion = 1
        val t1 = Instant.EPOCH
        val t2 = t1.plusSeconds(1)
        val t3 = t2.plusSeconds(1)
        whenever(mockClock.instant()).thenReturn(t3)
        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id("test")
                    .payload("payload")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken("test")
                    .runAfter(t1)
                    .build()
            )
        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id("test2")
                    .payload("payload")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken("test2")
                    .runAfter(t2)
                    .build()
            )
        // Now try to fetch
        val tasks = scheduler().fetch<String>(1)
        // Since order is not guaranteed (this is by design to avoid contention)
        // then we should expect either task 1 or 2
        val ids = HashSet<String>()
        ids.add("test")
        ids.add("test2")
        assertEquals(1, tasks.size())
        assertTrue(ids.contains(tasks.get(0).id))
        ids.remove(tasks.get(0).id)
        assertEquals(Task.Status.RUNNING, tasks.get(0).status)
        assertEquals(0, tasks.get(0).retryCount)
        assertEquals(++expectedVersion, tasks.get(0).version)
        assertEquals(2, scheduler().realSize())
        // Try to fetch again, we should only get task 2
        val tasks2 = scheduler().fetch<String>(1)
        assertEquals(1, tasks.size())
        assertTrue(ids.contains(tasks2.get(0).id))
        assertEquals(Task.Status.RUNNING, tasks2.get(0).status)
        assertEquals(0, tasks2.get(0).retryCount)
        // Fetching again should return empty result
        assertTrue(scheduler().fetch<String>(1).isEmpty)
    }

    @Test
    fun testRenewLease() {
        val t1 = Instant.EPOCH
        whenever(mockClock.instant()).thenReturn(t1)
        val task =
            scheduler()
                .schedule(
                    ScheduleRequest.builder<String>()
                        .id("test")
                        .payload("payload")
                        .type(Task.Type.WORKFLOW)
                        .dedupToken("test")
                        .runAfter(t1)
                        .build()
                )
        assertEquals(t1, task.runAfter)
        // Now try to renew lease
        val newTask = scheduler().renewLease(task)
        assertEquals(t1.plus(leaseDuration), newTask.runAfter)
        assertEquals(task.version + 1, newTask.version)
        assertEquals(Task.Status.PENDING, newTask.status)
        // Now try to renew the lease of a stale task, which should throw OptimisticLockError
        assertThrows(OptimisticLockingError::class.java) { scheduler().renewLease(task) }
        // Now try to renew the lease of a task that's not in PENDING or RUNNING
        scheduler().markAsFailed(newTask, "failed")
        assertThrows(IllegalStateException::class.java) { scheduler().renewLease(newTask) }
        // Trying to renew a lease of an invalid task should throw an IllegalArgumentException
        assertThrows(IllegalArgumentException::class.java) {
            scheduler().renewLease(task.toBuilder().id("invalid").build())
        }
    }

    // ========== PARTITION-ENABLED FETCH TESTS ==========

    @Test
    fun testFetchWithPartitionDeterministic() {
        val partitioner = BucketPartitioner()
        val t1 = Instant.EPOCH
        whenever(mockClock.instant()).thenReturn(t1)

        // Find task IDs that deterministically hash to known buckets
        val taskInBucket0 = findTaskIdForBucket(partitioner, 0)
        val taskInBucket500 = findTaskIdForBucket(partitioner, 500)
        val taskInBucket999 = findTaskIdForBucket(partitioner, 999)

        // Create tasks with deterministic bucket assignments
        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id(taskInBucket0)
                    .payload("payload0")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken(taskInBucket0)
                    .runAfter(t1)
                    .build()
            )

        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id(taskInBucket500)
                    .payload("payload500")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken(taskInBucket500)
                    .runAfter(t1)
                    .build()
            )

        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id(taskInBucket999)
                    .payload("payload999")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken(taskInBucket999)
                    .runAfter(t1)
                    .build()
            )

        // Verify our tasks are in the expected buckets
        assertEquals(0, partitioner.hashToBucket(taskInBucket0))
        assertEquals(500, partitioner.hashToBucket(taskInBucket500))
        assertEquals(999, partitioner.hashToBucket(taskInBucket999))

        // Test partition that includes only bucket 0
        val partition0 = BucketRange(0, 1)
        val tasks0 = scheduler().fetch<String>(10, partition0)
        assertEquals(1, tasks0.size())
        assertEquals(taskInBucket0, tasks0.get(0).id)

        // Test partition that includes buckets 500-600
        val partition500 = BucketRange(500, 600)
        val tasks500 = scheduler().fetch<String>(10, partition500)
        assertEquals(1, tasks500.size())
        assertEquals(taskInBucket500, tasks500.get(0).id)

        // Test partition that includes only bucket 999
        val partition999 = BucketRange(999, 1000)
        val tasks999 = scheduler().fetch<String>(10, partition999)
        assertEquals(1, tasks999.size())
        assertEquals(taskInBucket999, tasks999.get(0).id)

        // Test partition that includes no tasks (bucket 100-200, assuming none of our tasks are there)
        val emptyPartition = BucketRange(100, 200)
        val emptyTasks = scheduler().fetch<String>(10, emptyPartition)
        assertTrue(emptyTasks.isEmpty)
    }

    @Test
    fun testFetchWithMultiplePartitionsDeterministic() {
        val partitioner = BucketPartitioner()
        val t1 = Instant.EPOCH
        whenever(mockClock.instant()).thenReturn(t1)

        // Find task IDs that deterministically hash to specific buckets
        val taskInBucket100 = findTaskIdForBucket(partitioner, 100)
        val taskInBucket200 = findTaskIdForBucket(partitioner, 200)
        val taskInBucket600 = findTaskIdForBucket(partitioner, 600)
        val taskInBucket800 = findTaskIdForBucket(partitioner, 800)

        // Create tasks with known bucket assignments
        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id(taskInBucket100)
                    .payload("payload100")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken(taskInBucket100)
                    .runAfter(t1)
                    .build()
            )

        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id(taskInBucket200)
                    .payload("payload200")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken(taskInBucket200)
                    .runAfter(t1)
                    .build()
            )

        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id(taskInBucket600)
                    .payload("payload600")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken(taskInBucket600)
                    .runAfter(t1)
                    .build()
            )

        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id(taskInBucket800)
                    .payload("payload800")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken(taskInBucket800)
                    .runAfter(t1)
                    .build()
            )

        // Create two non-overlapping partitions
        val partition1 = BucketRange(0, 500) // Should include buckets 100 and 200
        val partition2 = BucketRange(500, 1000) // Should include buckets 600 and 800

        // Fetch with each partition
        val tasks1 = scheduler().fetch<String>(10, partition1)
        val tasks2 = scheduler().fetch<String>(10, partition2)

        // Verify partition1 contains exactly the expected tasks
        assertEquals(2, tasks1.size())
        val ids1 = HashSet<String>()
        for (task in tasks1) {
            ids1.add(task.id)
        }
        assertTrue(ids1.contains(taskInBucket100))
        assertTrue(ids1.contains(taskInBucket200))

        // Verify partition2 contains exactly the expected tasks
        assertEquals(2, tasks2.size())
        val ids2 = HashSet<String>()
        for (task in tasks2) {
            ids2.add(task.id)
        }
        assertTrue(ids2.contains(taskInBucket600))
        assertTrue(ids2.contains(taskInBucket800))

        // Verify no overlap between partitions
        val intersection = HashSet(ids1)
        intersection.retainAll(ids2)
        assertTrue(intersection.isEmpty(), "Partitions should not overlap")
    }

    /**
     * A partition that holds at least `limit` ready tasks must yield `limit` tasks, even when the
     * queue also holds many tasks outside the partition. Guards against implementations that apply the
     * bucket filter after a `LIMIT`-bounded read, which would starve a partition in proportion to the
     * share of the queue owned by other members.
     */
    @Test
    fun testFetchWithPartitionReturnsUpToLimit() {
        val partitioner = BucketPartitioner()
        val t1 = Instant.EPOCH
        whenever(mockClock.instant()).thenReturn(t1)

        // 20 tasks in the lower half of the bucket space and 20 in the upper half.
        val lower = (0 until 20).map { i -> findTaskIdForBucket(partitioner, i * 25) }
        val upper = (0 until 20).map { i -> findTaskIdForBucket(partitioner, 500 + i * 25) }
        for (id in lower + upper) {
            scheduler().schedule(
                ScheduleRequest.builder<String>()
                    .id(id)
                    .payload(id)
                    .type(Task.Type.WORKFLOW)
                    .dedupToken(id)
                    .runAfter(t1)
                    .build()
            )
        }

        val tasks = scheduler().fetch<String>(10, BucketRange(0, 500))
        assertEquals(10, tasks.size())
        for (task in tasks) {
            assertTrue(lower.contains(task.id), "task ${task.id} is outside the partition")
        }
    }

    @Test
    fun testFetchWithNullPartition() {
        val t1 = Instant.EPOCH
        whenever(mockClock.instant()).thenReturn(t1)

        // Create tasks
        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id("task1")
                    .payload("payload1")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken("task1")
                    .runAfter(t1)
                    .build()
            )

        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id("task2")
                    .payload("payload2")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken("task2")
                    .runAfter(t1)
                    .build()
            )

        // Fetch with null partition should fallback to regular fetch behavior
        val allTasks = scheduler().fetch<String>(10, null)
        val regularTasks = scheduler().fetch<String>(10)

        // Both should behave the same way
        assertEquals(
            allTasks.size() + regularTasks.size(),
            2,
            "Null partition should work like regular fetch (total tasks available should be 2)"
        )
    }

    @Test
    fun testFetchWithFullRangePartition() {
        val t1 = Instant.EPOCH
        whenever(mockClock.instant()).thenReturn(t1)

        // Create multiple tasks
        val taskIds = arrayOf("alpha", "beta", "gamma", "delta")
        for (taskId in taskIds) {
            scheduler()
                .schedule(
                    ScheduleRequest.builder<String>()
                        .id(taskId)
                        .payload("payload-$taskId")
                        .type(Task.Type.WORKFLOW)
                        .dedupToken(taskId)
                        .runAfter(t1)
                        .build()
                )
        }

        // Create a partition that covers all buckets
        val fullPartition = BucketRange(0, 1000)

        // Fetch with full partition should return all available tasks
        val partitionedTasks = scheduler().fetch<String>(10, fullPartition)
        val regularTasks = scheduler().fetch<String>(10)

        // Should get the same number of tasks (accounting for tasks already fetched)
        assertEquals(
            partitionedTasks.size() + regularTasks.size(),
            taskIds.size,
            "Full partition should return all available tasks"
        )
    }

    @Test
    fun testFetchWithPartitionAndLimitDeterministic() {
        val partitioner = BucketPartitioner()
        val t1 = Instant.EPOCH
        whenever(mockClock.instant()).thenReturn(t1)

        // Find task IDs that deterministically hash to buckets in the range 0-300
        val taskInBucket50 = findTaskIdForBucket(partitioner, 50)
        val taskInBucket100 = findTaskIdForBucket(partitioner, 100)
        val taskInBucket200 = findTaskIdForBucket(partitioner, 200)
        val taskInBucket250 = findTaskIdForBucket(partitioner, 250)

        // Create tasks with known bucket assignments
        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id(taskInBucket50)
                    .payload("payload50")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken(taskInBucket50)
                    .runAfter(t1)
                    .build()
            )

        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id(taskInBucket100)
                    .payload("payload100")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken(taskInBucket100)
                    .runAfter(t1)
                    .build()
            )

        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id(taskInBucket200)
                    .payload("payload200")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken(taskInBucket200)
                    .runAfter(t1)
                    .build()
            )

        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id(taskInBucket250)
                    .payload("payload250")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken(taskInBucket250)
                    .runAfter(t1)
                    .build()
            )

        // Create a partition that includes all our tasks (0-300 range)
        val partition = BucketRange(0, 300)

        // Fetch with limit of 2 - should get exactly 2 tasks from the partition
        val limitedTasks = scheduler().fetch<String>(2, partition)

        // Should respect the limit
        assertEquals(2, limitedTasks.size(), "Should return exactly 2 tasks due to limit")

        // Verify all returned tasks belong to the partition
        val expectedTaskIds = HashSet<String>()
        expectedTaskIds.add(taskInBucket50)
        expectedTaskIds.add(taskInBucket100)
        expectedTaskIds.add(taskInBucket200)
        expectedTaskIds.add(taskInBucket250)

        for (task in limitedTasks) {
            val bucket = partitioner.hashToBucket(task.id)
            assertTrue(partition.contains(bucket), "Task " + task.id + " should be in partition")
            assertTrue(
                expectedTaskIds.contains(task.id),
                "Returned task should be one of our created tasks"
            )
        }

        // Fetch remaining tasks from the partition
        val remainingTasks = scheduler().fetch<String>(10, partition)
        assertEquals(2, remainingTasks.size(), "Should return remaining 2 tasks from partition")

        // Verify no overlap with first fetch
        val firstFetchIds = HashSet<String>()
        for (task in limitedTasks) {
            firstFetchIds.add(task.id)
        }

        for (task in remainingTasks) {
            assertFalse(
                firstFetchIds.contains(task.id),
                "Remaining tasks should not overlap with first fetch"
            )
        }
    }

    /**
     * Tests that fetch() gracefully handles the case where a task is deleted between the initial
     * query and the lease attempt. This can happen when multiple pods are racing to process tasks
     * and one pod completes and deletes the task before another pod can lease it.
     *
     * This test simulates the race condition by: 1. Creating two tasks 2. Having one "pod" fetch and
     * immediately complete/delete task1 3. Having another "pod" try to fetch - it should not fail
     * even if task1 was in the query results but got deleted before leasing
     *
     * The fix ensures a missing-root error is caught during lease acquisition, similar to how a
     * stale-version error is already handled.
     */
    @Test
    fun testFetchHandlesDeletedTaskDuringLeaseAttempt() {
        val t1 = Instant.EPOCH
        whenever(mockClock.instant()).thenReturn(t1)

        // Create two tasks
        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id("task-to-delete")
                    .payload("payload1")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken("task-to-delete")
                    .runAfter(t1)
                    .build()
            )

        scheduler()
            .schedule(
                ScheduleRequest.builder<String>()
                    .id("task-to-keep")
                    .payload("payload2")
                    .type(Task.Type.WORKFLOW)
                    .dedupToken("task-to-keep")
                    .runAfter(t1)
                    .build()
            )

        assertEquals(2, scheduler().realSize())

        // Simulate "pod 1" fetching and completing task1
        val pod1Tasks = scheduler().fetch<String>(1)
        assertEquals(1, pod1Tasks.size())
        val fetchedTask = pod1Tasks.get(0)

        // Pod 1 completes the workflow and removes the task from the scheduler
        scheduler().remove(fetchedTask)

        // Now the scheduler has only 1 task
        assertEquals(1, scheduler().realSize())

        // Simulate "pod 2" fetching - this should NOT throw an exception
        // even if the internal query might have returned the now-deleted task
        // (in a real race condition scenario)
        val pod2Tasks = scheduler().fetch<String>(10)

        // Pod 2 should get the remaining task (or empty if both were processed)
        // The important thing is that no exception was thrown
        assertTrue(pod2Tasks.size() <= 1, "Should have at most 1 task remaining")

        // If pod 2 got a task, it should be the one that wasn't deleted
        if (!pod2Tasks.isEmpty) {
            val remainingTaskId =
                if (fetchedTask.id == "task-to-delete") "task-to-keep" else "task-to-delete"
            assertEquals(remainingTaskId, pod2Tasks.get(0).id)
        }
    }

    /**
     * Helper method to find a task ID that hashes to a specific bucket. This ensures our tests are
     * deterministic.
     */
    private fun findTaskIdForBucket(
        partitioner: BucketPartitioner,
        targetBucket: Int
    ): String {
        for (i in 0 until 10000) {
            val candidate = "task-$targetBucket-$i"
            if (partitioner.hashToBucket(candidate) == targetBucket) {
                return candidate
            }
        }
        throw RuntimeException("Could not find task ID for bucket $targetBucket")
    }
}
