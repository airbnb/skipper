package com.airbnb.skipper.internal.storage

import com.airbnb.skipper.Actions
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.OptimisticLockingError
import com.airbnb.skipper.RequestContextSerde
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.Timer
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowCallbackHandler
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.CheckpointTag
import com.airbnb.skipper.internal.TestUtils.EXTRA_REQUEST_DATA
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.internal.api.PersistedSignal
import io.vavr.Tuple3
import io.vavr.collection.HashMap
import io.vavr.collection.List
import io.vavr.collection.Map
import io.vavr.control.Either
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

abstract class BaseWorkflowStoreTest {
    /**
     * Test payload used as the workflow's opaque request context. The OSS engine treats this as
     * `Any?` so it can be any serializable value — keeping it as a plain [String] (and pairing it
     * with [TEST_REQUEST_CONTEXT_SERDE] below) prevents this test from having to import any
     * Airbnb-specific request-context type.
     */
    private val REQUEST_CONTEXT = "test-context-userId-123"

    /**
     * UTF-8 String <-> bytes serde used by the OSS storage tests. Storage subclasses wire this
     * into the [com.airbnb.skipper.SkipperConfig] (via `setRequestContextSerde`) so the round-trip
     * through the storage backend produces the same String back.
     */
    @JvmField
    protected val TEST_REQUEST_CONTEXT_SERDE: RequestContextSerde =
        object : RequestContextSerde {
            override fun serialize(ctx: Any?): ByteArray {
                return if (ctx == null) ByteArray(0) else (ctx as String).toByteArray(StandardCharsets.UTF_8)
            }

            override fun deserialize(bytes: ByteArray): Any? {
                return if (bytes.isEmpty()) null else String(bytes, StandardCharsets.UTF_8)
            }
        }

    protected abstract fun workflowStore(): WorkflowStore

    protected abstract fun createWorkflowStore(owner: String): WorkflowStore

    @Test
    @Throws(Exception::class)
    fun testCreateWorkflow() {
        // Arrange
        val input = TestInput("Ricardo", 36)
        val timeoutTime = Instant.now().plusSeconds(100).truncatedTo(ChronoUnit.MILLIS)
        val request =
            WorkflowCreationRequest.builder()
                .workflowClass(Workflow::class.java)
                .workflowId("test-workflow-id")
                .workflowMethod("test-method")
                .input(input)
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(EXTRA_REQUEST_DATA)
                .callbackHandler(WorkflowCallbackHandler::class.java)
                .timeoutTime(timeoutTime)
                .build()
        // Act
        val instance = workflowStore().createWorkflow(request)
        // Assert
        assertEquals("test-workflow-id", instance.workflowId)
        assertEquals(WorkflowInstance.Status.RUNNING, instance.status)
        assertEquals(1, instance.version)
        assertEquals(HashMap.empty<Any, Any>(), instance.state)
        assertEquals(input, instance.input)
        assertFalse(instance.result.isDone)
        assertEquals(REQUEST_CONTEXT, instance.requestContext)
        assertEquals(EXTRA_REQUEST_DATA, instance.extraRequestData)
        assertEquals(WorkflowCallbackHandler::class.java, instance.callbackHandler)
        assertEquals(
            timeoutTime.truncatedTo(ChronoUnit.SECONDS),
            instance.timeoutTime!!.truncatedTo(ChronoUnit.SECONDS)
        )
        assertNotNull(instance.createdAt)

        // Trying to create an existing workflow should throw an exception
        assertThrows(EntityAlreadyExists::class.java) { workflowStore().createWorkflow(request) }
        // Trying to create a workflow with the same id but different owner should succeed
        val workflowStore2 = createWorkflowStore("test-owner2")
        val instanceOwner2 = workflowStore2.createWorkflow(request)
        assertEquals("test-workflow-id", instanceOwner2.workflowId)
        // Test getting the workflow
        assertWorkflowInstancesAreEqual(
            instance,
            workflowStore().getWorkflow("test-workflow-id").get()
        )
        // Trying to get a non-existing workflow should return an empty option
        assertTrue(workflowStore().getWorkflow("non-existing-workflow-id").isEmpty)
        // Try to update the workflow
        val newState: Map<String, Any?> = HashMap.of("name", "Ricardo")
        val updateRequest =
            WorkflowUpdateRequest.builder()
                .workflowInstance(instance)
                .newStatus(WorkflowInstance.Status.COMPLETED)
                .newState(newState)
                .result(Either.right("completed result!"))
                .resultIsAsync(true)
                .build()
        val updatedInstance = workflowStore().updateWorkflow(updateRequest)
        assertEquals(newState, updatedInstance.state)
        assertEquals(WorkflowInstance.Status.COMPLETED, updatedInstance.status)
        assertEquals(2, updatedInstance.version)
        assertEquals(REQUEST_CONTEXT, updatedInstance.requestContext)
        // Result was set to be async so instance result should be in turn async
        @Suppress("UNCHECKED_CAST")
        val result = updatedInstance.result.get() as CompletableFuture<Any>
        assertEquals("completed result!", result.get())
        assertTrue(updatedInstance.isResultAsync())
        // Trying to update a stale workflow should throw an exception
        assertThrows(RetryableError::class.java) { workflowStore().updateWorkflow(updateRequest) }
        // Now let's try clearing the result
        val clearResultRequest =
            WorkflowUpdateRequest.builder()
                .workflowInstance(updatedInstance)
                .newStatus(WorkflowInstance.Status.RUNNING)
                .clearResult(true)
                .build()
        val clearedInstance = workflowStore().updateWorkflow(clearResultRequest)
        assertFalse(clearedInstance.result.isDone)
    }

    @Test
    fun testCreateAndGetWorkflowInstanceWithParentWorkflowId() {
        // Arrange
        val parentWorkflowId = "parent-id"
        val workflowId = "test-workflow-id-new"
        val input = TestInput("Ricardo", 36)
        val timeoutTime = Instant.now().plusSeconds(100).truncatedTo(ChronoUnit.MILLIS)
        val request =
            WorkflowCreationRequest.builder()
                .workflowClass(Workflow::class.java)
                .workflowId(workflowId)
                .workflowMethod("test-method")
                .input(input)
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(EXTRA_REQUEST_DATA)
                .callbackHandler(WorkflowCallbackHandler::class.java)
                .timeoutTime(timeoutTime)
                .parentWorkflowId(parentWorkflowId)
                .build()
        // Act
        workflowStore().createWorkflow(request)
        val instance = workflowStore().getWorkflow("test-workflow-id-new").get()
        // Assert
        assertEquals(workflowId, instance.workflowId)
        assertEquals(parentWorkflowId, instance.parentWorkflowId)
    }

    @Test
    fun testStoreActionCheckpoints() {
        // Create the root objects
        val input = TestInput("Ricardo", 36)
        val request =
            WorkflowCreationRequest.builder()
                .workflowClass(Workflow::class.java)
                .workflowId("test-workflow-id")
                .workflowMethod("test-method")
                .input(input)
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(EXTRA_REQUEST_DATA)
                .build()
        workflowStore().createWorkflow(request)
        workflowStore().createWorkflow(request.toBuilder().workflowId("test-workflow-2").build())
        // Arrange
        val t1 = Instant.EPOCH
        // Act
        val tag1 =
            CheckpointTag.builder()
                .workflowId("test-workflow-id")
                .actionClass(Actions::class.java)
                .actionMethod("test-method")
                .iteration(1)
                .build()
        val tag2 =
            CheckpointTag.builder()
                .workflowId("test-workflow-id")
                .actionClass(Actions::class.java)
                .actionMethod("test-method2")
                .iteration(1)
                .build()
        val checkpoint1 =
            ActionCheckpoint.builder()
                .checkpointTag(tag1)
                .executionStartTime(t1)
                .result(Either.right("result"))
                .isTransient(false)
                .build()
        val checkpoint2 =
            ActionCheckpoint.builder()
                .checkpointTag(tag2)
                .executionStartTime(t1)
                .result(Either.right("result2"))
                .resultIsAsync(true)
                .isTransient(false)
                .input("test-input")
                .build()
        val afterSaveCheckpoints =
            workflowStore()
                .storeActionCheckpoints("test-workflow-id", List.of(checkpoint1, checkpoint2))
        // Add a new checkpoint for other workflow
        val checkpoint3 =
            ActionCheckpoint.builder()
                .checkpointTag(tag2.toBuilder().workflowId("test-workflow-2").build())
                .executionStartTime(t1)
                .result(Either.left(NonRetryableError("non retryable error")))
                .isTransient(false)
                .build()
        val afterSaveCheckpoints2 =
            workflowStore().storeActionCheckpoints("test-workflow-2", List.of(checkpoint3))
        // Assert
        assertEquals(2, afterSaveCheckpoints.size())
        // Checkpoint ordering may not be guaranteed, so check by finding matching checkpoints
        assertTrue(
            afterSaveCheckpoints.exists { cp ->
                cp.checkpointTag.equals(checkpoint1.checkpointTag)
            },
            "checkpoint1 should be in afterSaveCheckpoints"
        )
        assertTrue(
            afterSaveCheckpoints.exists { cp ->
                cp.checkpointTag.equals(checkpoint2.checkpointTag)
            },
            "checkpoint2 should be in afterSaveCheckpoints"
        )
        assertEquals(1, afterSaveCheckpoints2.size())
        // Fetch the checkpoints for workflow 1
        val fetchedCheckpoints = workflowStore().getActionCheckpoints("test-workflow-id")
        assertEquals(2, fetchedCheckpoints.size())
        // Checkpoint ordering may not be guaranteed, so check by finding matching checkpoints
        assertTrue(
            fetchedCheckpoints.exists { cp ->
                cp.checkpointTag.equals(checkpoint1.checkpointTag)
            },
            "checkpoint1 should be in fetchedCheckpoints"
        )
        assertTrue(
            fetchedCheckpoints.exists { cp ->
                cp.checkpointTag.equals(checkpoint2.checkpointTag)
            },
            "checkpoint2 should be in fetchedCheckpoints"
        )
        // Fetch the checkpoints for workflow 2
        val fetchedCheckpoints2 = workflowStore().getActionCheckpoints("test-workflow-2")
        assertEquals(1, fetchedCheckpoints2.size())
        assertEquals(checkpoint3.checkpointTag, fetchedCheckpoints2.get(0).checkpointTag)
    }

    @Test
    fun testUpdateWorkflowAndStoreCheckpoints() {
        // Arrange
        // Create the root objects
        val input = TestInput("Ricardo", 36)
        val request =
            WorkflowCreationRequest.builder()
                .workflowClass(Workflow::class.java)
                .workflowId("test-workflow-id")
                .workflowMethod("test-method")
                .input(input)
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(EXTRA_REQUEST_DATA)
                .build()
        workflowStore().createWorkflow(request)
        val t1 = Instant.EPOCH
        // Act
        val tag1 =
            CheckpointTag.builder()
                .workflowId("test-workflow-id")
                .actionClass(Actions::class.java)
                .actionMethod("test-method")
                .iteration(1)
                .build()
        val tag2 =
            CheckpointTag.builder()
                .workflowId("test-workflow-id")
                .actionClass(Actions::class.java)
                .actionMethod("test-method2")
                .iteration(1)
                .build()
        val checkpoint1 =
            ActionCheckpoint.builder()
                .checkpointTag(tag1)
                .executionStartTime(t1)
                .result(Either.right("result"))
                .isTransient(false)
                .build()
        val checkpoint2 =
            ActionCheckpoint.builder()
                .checkpointTag(tag2)
                .executionStartTime(t1)
                .result(Either.right("result2"))
                .resultIsAsync(true)
                .isTransient(false)
                .input("test-input")
                .build()
        val updateRequest =
            WorkflowUpdateRequest.builder()
                .workflowInstance(workflowStore().getWorkflow("test-workflow-id").get())
                .newStatus(WorkflowInstance.Status.COMPLETED)
                .newState(HashMap.of("name", "Ricardo"))
                .result(Either.right("completed result!"))
                .resultIsAsync(true)
                .build()
        val timer =
            Timer(
                "test-workflow-id",
                "1",
                null,
                Duration.ofDays(1),
                Instant.EPOCH.plus(Duration.ofDays(1)),
                Timer.Status.ACTIVE,
                1
            )
        val timer2 =
            Timer(
                "test-workflow-id",
                "2",
                null,
                Duration.ofDays(1),
                Instant.EPOCH.plus(Duration.ofDays(1)),
                Timer.Status.ACTIVE,
                1
            )
        val updatedInstanceAndCheckpoints =
            workflowStore()
                .updateWorkflowAndStoreCheckpointsAndTimers(
                    updateRequest,
                    List.of(checkpoint1, checkpoint2),
                    List.of(timer, timer2)
                )
        // Assert
        assertEquals(2, updatedInstanceAndCheckpoints._2.size())
        assertEquals(checkpoint1, updatedInstanceAndCheckpoints._2.get(0))
        assertEquals(checkpoint2, updatedInstanceAndCheckpoints._2.get(1))
        assertEquals(2, updatedInstanceAndCheckpoints._1.version)
        assertEquals(WorkflowInstance.Status.COMPLETED, updatedInstanceAndCheckpoints._1.status)
        val newTimers = updatedInstanceAndCheckpoints._3
        assertEquals(2, newTimers.size())
        // Verify that the total timers for the workflow are 2
        assertEquals(2, workflowStore().getTimers("test-workflow-id").size())
        // Now test that adding a new timer works like an upsert
        val updatedWorkflowInstance = updatedInstanceAndCheckpoints._1
        val updatedTimer = updatedInstanceAndCheckpoints._3.get(0)
        val newTimer =
            Timer(
                updatedTimer.workflowId,
                updatedTimer.id,
                updatedTimer.createdAt,
                Duration.ofDays(2),
                updatedTimer.expiresAt,
                updatedTimer.status,
                updatedTimer.version
            )
        var newResult: Tuple3<WorkflowInstance, List<ActionCheckpoint>, List<Timer>> =
            workflowStore()
                .updateWorkflowAndStoreCheckpointsAndTimers(
                    updateRequest.toBuilder().workflowInstance(updatedWorkflowInstance).build(),
                    List.empty(),
                    List.of(newTimer)
                )
        assertEquals(0, newResult._3.size())
        // Verify that the total timers are still 2
        assertEquals(2, workflowStore().getTimers("test-workflow-id").size())
        // Now trying to create a timer that already exists and is in a terminal status should
        // resut in no-op.
        workflowStore().expireTimer(timer.workflowId, timer.id)
        val newUpdatedWorkflowInstance =
            workflowStore().getWorkflow(updatedWorkflowInstance.workflowId).get()
        newResult =
            workflowStore()
                .updateWorkflowAndStoreCheckpointsAndTimers(
                    updateRequest.toBuilder().workflowInstance(newUpdatedWorkflowInstance).build(),
                    List.empty(),
                    List.of(timer)
                )
        // Check that the number of timers created/updated is zero
        assertEquals(0, newResult._3.size())
        // Now try updating an expired timer with the new status of CANCELLED. This is a valid
        // transition!
        val expiredTimer = workflowStore().getTimer(timer.workflowId, timer.id).get()
        val cancelledTimer =
            Timer(
                expiredTimer.workflowId,
                expiredTimer.id,
                expiredTimer.createdAt,
                expiredTimer.duration,
                expiredTimer.expiresAt,
                Timer.Status.CANCELLED,
                expiredTimer.version
            )
        newResult =
            workflowStore()
                .updateWorkflowAndStoreCheckpointsAndTimers(
                    updateRequest.toBuilder().workflowInstance(newResult._1).build(),
                    List.empty(),
                    List.of(cancelledTimer)
                )
        assertEquals(1, newResult._3.size())
        assertEquals(Timer.Status.CANCELLED, newResult._3.get(0).status)
    }

    @Test
    fun testUpdateWorkflowAndInsertCheckpointsWhenNoCheckpoints() {
        // Arrange
        val input = TestInput("Ricardo", 36)
        val request =
            WorkflowCreationRequest.builder()
                .workflowClass(Workflow::class.java)
                .workflowId("test-workflow-id")
                .workflowMethod("test-method")
                .input(input)
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(EXTRA_REQUEST_DATA)
                .build()
        workflowStore().createWorkflow(request)
        // Act
        val updateRequest =
            WorkflowUpdateRequest.builder()
                .workflowInstance(workflowStore().getWorkflow("test-workflow-id").get())
                .newStatus(WorkflowInstance.Status.COMPLETED)
                .newState(HashMap.of("name", "Ricardo"))
                .result(Either.right("completed result!"))
                .resultIsAsync(true)
                .build()
        val updatedInstanceAndCheckpoints =
            workflowStore()
                .updateWorkflowAndStoreCheckpointsAndTimers(updateRequest, List.empty(), List.empty())
        // Assert
        assertEquals(0, updatedInstanceAndCheckpoints._2.size())
        assertEquals(2, updatedInstanceAndCheckpoints._1.version)
        assertEquals(WorkflowInstance.Status.COMPLETED, updatedInstanceAndCheckpoints._1.status)
    }

    @Test
    fun updateWorkflowAndStoreCheckpointsAndTimers_shouldFailAtomicallyOnStaleVersion() {
        // Arrange: create a workflow, keep a handle on the instance as first read, then advance the
        // instance behind that handle so it is stale — the shape of two threads racing to update the
        // same workflow.
        val request =
            WorkflowCreationRequest.builder()
                .workflowClass(Workflow::class.java)
                .workflowId("test-workflow-id")
                .workflowMethod("test-method")
                .input(TestInput("Ricardo", 36))
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(EXTRA_REQUEST_DATA)
                .build()
        workflowStore().createWorkflow(request)
        val staleInstance = workflowStore().getWorkflow("test-workflow-id").get()
        workflowStore()
            .updateWorkflow(
                WorkflowUpdateRequest.builder()
                    .workflowInstance(staleInstance)
                    .newStatus(WorkflowInstance.Status.RUNNING)
                    .build()
            )
        val checkpoint =
            ActionCheckpoint.builder()
                .checkpointTag(
                    CheckpointTag.builder()
                        .workflowId("test-workflow-id")
                        .actionClass(Actions::class.java)
                        .actionMethod("test-method")
                        .iteration(1)
                        .build()
                )
                .executionStartTime(Instant.EPOCH)
                .result(Either.right("result"))
                .isTransient(false)
                .build()
        val timer =
            Timer(
                "test-workflow-id",
                "1",
                null,
                Duration.ofDays(1),
                Instant.EPOCH.plus(Duration.ofDays(1)),
                Timer.Status.ACTIVE,
                1
            )
        val staleRequest =
            WorkflowUpdateRequest.builder()
                .workflowInstance(staleInstance)
                .newStatus(WorkflowInstance.Status.COMPLETED)
                .newState(HashMap.of("name", "Ricardo"))
                .build()
        // Act + Assert: losing the version race is ordinary contention, so every backend reports it
        // as OptimisticLockingError rather than leaking a storage-specific exception.
        assertThrows(OptimisticLockingError::class.java) {
            workflowStore()
                .updateWorkflowAndStoreCheckpointsAndTimers(
                    staleRequest,
                    List.of(checkpoint),
                    List.of(timer)
                )
        }
        // ...and the call is all-or-nothing: the instance keeps the winner's status and version, and
        // neither the checkpoint nor the timer was written.
        val persisted = workflowStore().getWorkflow("test-workflow-id").get()
        assertEquals(2, persisted.version)
        assertEquals(WorkflowInstance.Status.RUNNING, persisted.status)
        assertEquals(0, workflowStore().getActionCheckpoints("test-workflow-id").size())
        assertEquals(0, workflowStore().getTimers("test-workflow-id").size())
    }

    @Test
    fun testExpire() {
        createInstanceForTimer()
        val timer = createTimer()
        var succeeded = workflowStore().expireTimer(timer.workflowId, timer.id)
        assertTrue(succeeded)
        // Check that the timer is expired
        val expiredTimer = workflowStore().getTimer(timer.workflowId, timer.id)
        assertTrue(expiredTimer.isDefined)
        assertEquals(Timer.Status.EXPIRED, expiredTimer.get().status)
        // Trying to do that again should be a no-op but should still return true
        succeeded = workflowStore().expireTimer(timer.workflowId, timer.id)
        assertTrue(succeeded)
    }

    @Test
    fun testExpireWhenTimerIsCancelled() {
        createInstanceForTimer()
        val timer = createTimer()
        var succeeded = workflowStore().cancelTimer(timer.workflowId, timer.id)
        assertTrue(succeeded)
        // Check that the timer is cancelled
        var cancelledTimer = workflowStore().getTimer(timer.workflowId, timer.id)
        assertTrue(cancelledTimer.isDefined)
        assertEquals(Timer.Status.CANCELLED, cancelledTimer.get().status)
        // Trying to expire the timer should return false
        succeeded = workflowStore().expireTimer(timer.workflowId, timer.id)
        assertFalse(succeeded)
        // Check that the timer is still cancelled
        cancelledTimer = workflowStore().getTimer(timer.workflowId, timer.id)
        assertTrue(cancelledTimer.isDefined)
        assertEquals(Timer.Status.CANCELLED, cancelledTimer.get().status)
    }

    @Test
    fun testCancel() {
        createInstanceForTimer()
        val timer = createTimer()
        val succeeded = workflowStore().cancelTimer(timer.workflowId, timer.id)
        assertTrue(succeeded)
        // Check that the timer is cancelled
        val cancelledTimer = workflowStore().getTimer(timer.workflowId, timer.id)
        assertTrue(cancelledTimer.isDefined)
        assertEquals(Timer.Status.CANCELLED, cancelledTimer.get().status)
        // Trying to do that again should be a no-op but should still return true
        workflowStore().cancelTimer(timer.workflowId, timer.id)
    }

    @Test
    fun testCancelWhenTimerIsExpired() {
        createInstanceForTimer()
        val timer = createTimer()
        var succeeded = workflowStore().expireTimer(timer.workflowId, timer.id)
        assertTrue(succeeded)
        // Check that the timer is expired
        var expiredTimer = workflowStore().getTimer(timer.workflowId, timer.id)
        assertTrue(expiredTimer.isDefined)
        assertEquals(Timer.Status.EXPIRED, expiredTimer.get().status)
        // Trying to cancel the timer should return true
        succeeded = workflowStore().cancelTimer(timer.workflowId, timer.id)
        assertTrue(succeeded)
        // Check that the timer is cancelled
        expiredTimer = workflowStore().getTimer(timer.workflowId, timer.id)
        assertTrue(expiredTimer.isDefined)
        assertEquals(Timer.Status.CANCELLED, expiredTimer.get().status)
    }

    @Test
    fun testGetTimers() {
        createInstanceForTimer()
        createInstanceForTimer("workflowId2")
        val timer1 = createTimer()
        val timer2 =
            workflowStore()
                .createTimer(
                    TimerCreationRequest.builder()
                        .workflowId(WORKFLOW_ID)
                        .timerId("timerId2")
                        .duration(DURATION)
                        .expiresAt(EXPIRES_AT)
                        .build()
                )
        val timer3 =
            workflowStore()
                .createTimer(
                    TimerCreationRequest.builder()
                        .workflowId("workflowId2")
                        .timerId("timerId3")
                        .duration(DURATION)
                        .expiresAt(EXPIRES_AT)
                        .build()
                )
        workflowStore().expireTimer(timer1.workflowId, timer1.id)
        var timers = workflowStore().getTimers(WORKFLOW_ID)
        assertEquals(2, timers.size())
        assertTrue(timers.find { t -> t.id.equals(timer1.id) }.isDefined)
        assertTrue(timers.find { t -> t.id.equals(timer2.id) }.isDefined)
        // Now get timers for workflowId2
        timers = workflowStore().getTimers("workflowId2")
        assertEquals(1, timers.size())
        assertTrue(timers.find { t -> t.id.equals(timer3.id) }.isDefined)
        // Now get timers for a non-existent workflow
        timers = workflowStore().getTimers("nonExistentWorkflow")
        assertEquals(0, timers.size())
    }

    @Test
    fun testCountWorkflowsByStatus() {
        // Create some workflows with different statuses
        createInstanceForTimer("workflow1")
        createInstanceForTimer("workflow2")
        val completedInstance = createInstanceForTimer("workflow3")
        val updateRequest =
            WorkflowUpdateRequest.builder()
                .workflowInstance(completedInstance)
                .newStatus(WorkflowInstance.Status.COMPLETED)
                .newState(HashMap.of("name", "Ricardo"))
                .result(Either.right("completed result!"))
                .resultIsAsync(true)
                .build()
        workflowStore().updateWorkflow(updateRequest)

        // Count workflows by status
        val running = workflowStore().countWorkflowsByStatus(List.of(WorkflowInstance.Status.RUNNING))
        val completed =
            workflowStore().countWorkflowsByStatus(List.of(WorkflowInstance.Status.COMPLETED))
        val waiting = workflowStore().countWorkflowsByStatus(List.of(WorkflowInstance.Status.WAITING))
        val runningAndCompleted =
            workflowStore()
                .countWorkflowsByStatus(
                    List.of(WorkflowInstance.Status.RUNNING, WorkflowInstance.Status.COMPLETED)
                )

        // Assert the counts
        assertEquals(2, running)
        assertEquals(1, completed)
        assertEquals(0, waiting)
        assertEquals(3, runningAndCompleted)
    }

    @Test
    @Throws(InterruptedException::class)
    fun testFindWorkflowsWithExhaustedRetries() {
        // Create a workflow with exhausted retries
        createInstanceForTimer("workflow1")
        val instance = createInstanceForTimer("workflowWithExhaustedRetries")
        val updateRequest =
            WorkflowUpdateRequest.builder()
                .workflowInstance(instance)
                .newStatus(WorkflowInstance.Status.RETRIES_EXHAUSTED)
                .build()
        val updatedWorkflow = workflowStore().updateWorkflow(updateRequest)
        assertEquals(WorkflowInstance.Status.RETRIES_EXHAUSTED, updatedWorkflow.status)

        val instance2 = createInstanceForTimer("workflowWithExhaustedRetries2")
        val updateRequest2 =
            WorkflowUpdateRequest.builder()
                .workflowInstance(instance2)
                .newStatus(WorkflowInstance.Status.RETRIES_EXHAUSTED)
                .build()
        workflowStore().updateWorkflow(updateRequest2)

        // Create a workflow with compensation error status
        val instance3 = createInstanceForTimer("workflowWithCompensationError")
        val updateRequest3 =
            WorkflowUpdateRequest.builder()
                .workflowInstance(instance3)
                .newStatus(WorkflowInstance.Status.COMPENSATION_ERROR)
                .build()
        workflowStore().updateWorkflow(updateRequest3)

        // Find workflows with exhausted retries (should include COMPENSATION_ERROR)
        val workflows =
            workflowStore()
                .findWorkflowsWithExhaustedRetries(
                    10,
                    WorkflowSortField.UPDATED_AT,
                    WorkflowSortDirection.ASC,
                )

        // Assert the result - should include both RETRIES_EXHAUSTED and COMPENSATION_ERROR workflows
        assertEquals(3, workflows.size())
        // Check that we have workflows with both statuses
        val statuses = workflows.map { it.status }.toJavaSet()
        assertTrue(statuses.contains(WorkflowInstance.Status.RETRIES_EXHAUSTED))
        assertTrue(statuses.contains(WorkflowInstance.Status.COMPENSATION_ERROR))

        // Skipping the sort must return the same set (in an unspecified order) and must honour the
        // limit, so callers can drop the sort without losing results.
        val unsorted = workflowStore().findWorkflowsWithExhaustedRetries(10, null, WorkflowSortDirection.ASC)
        assertEquals(
            workflows.map { it.workflowId }.toJavaSet(),
            unsorted.map { it.workflowId }.toJavaSet(),
        )
        assertEquals(
            2,
            workflowStore().findWorkflowsWithExhaustedRetries(2, null, WorkflowSortDirection.ASC).size(),
        )

        // DESC must return the same set as ASC but with updated_at non-increasing. Asserted on the
        // timestamp sequence rather than on fixed positions because the fixtures are created in the
        // same test and can share an updated_at tick, which leaves tie order unspecified.
        val descending =
            workflowStore()
                .findWorkflowsWithExhaustedRetries(
                    10,
                    WorkflowSortField.UPDATED_AT,
                    WorkflowSortDirection.DESC,
                )
        assertEquals(
            workflows.map { it.workflowId }.toJavaSet(),
            descending.map { it.workflowId }.toJavaSet(),
        )
        val ascTimestamps = workflows.map { requireNotNull(it.updatedAt) }.toJavaList()
        assertEquals(ascTimestamps.sorted(), ascTimestamps)
        val descTimestamps = descending.map { requireNotNull(it.updatedAt) }.toJavaList()
        assertEquals(descTimestamps.sortedDescending(), descTimestamps)
    }

    private fun createTimer(): Timer {
        val request =
            TimerCreationRequest.builder()
                .workflowId(WORKFLOW_ID)
                .timerId(TIMER_ID)
                .duration(DURATION)
                .expiresAt(EXPIRES_AT)
                .build()
        return workflowStore().createTimer(request)
    }

    private fun createInstanceForTimer(): WorkflowInstance {
        return createInstanceForTimer(WORKFLOW_ID)
    }

    private fun createInstanceForTimer(workflowId: String): WorkflowInstance {
        val request =
            WorkflowCreationRequest.builder()
                .workflowClass(Workflow::class.java)
                .workflowId(workflowId)
                .workflowMethod("test-method")
                .input("test")
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(EXTRA_REQUEST_DATA)
                .callbackHandler(WorkflowCallbackHandler::class.java)
                .timeoutTime(null)
                .build()
        return workflowStore().createWorkflow(request)
    }

    @Test
    fun deleteWorkflow_shouldDeleteWorkflowAndAllAssociatedData() {
        // Create a workflow with checkpoints and timers
        val instance = createInstanceForTimer("delete-test-workflow")

        // Add action checkpoints
        val tag =
            CheckpointTag.builder()
                .workflowId("delete-test-workflow")
                .actionClass(Actions::class.java)
                .actionMethod("testAction")
                .iteration(1)
                .build()
        val checkpoint =
            ActionCheckpoint.builder()
                .checkpointTag(tag)
                .executionStartTime(Instant.EPOCH)
                .result(Either.right("result"))
                .isTransient(false)
                .build()
        workflowStore().storeActionCheckpoints("delete-test-workflow", List.of(checkpoint))

        // Add a timer
        workflowStore()
            .createTimer(
                TimerCreationRequest.builder()
                    .workflowId("delete-test-workflow")
                    .timerId("timer1")
                    .duration(DURATION)
                    .expiresAt(EXPIRES_AT)
                    .build()
            )

        // Verify everything exists
        assertTrue(workflowStore().getWorkflow("delete-test-workflow").isDefined)
        assertEquals(1, workflowStore().getActionCheckpoints("delete-test-workflow").size())
        assertEquals(1, workflowStore().getTimers("delete-test-workflow").size())

        // Delete the workflow
        workflowStore().deleteWorkflow("delete-test-workflow")

        // Verify everything is gone
        assertTrue(workflowStore().getWorkflow("delete-test-workflow").isEmpty)
        assertEquals(0, workflowStore().getActionCheckpoints("delete-test-workflow").size())
        assertEquals(0, workflowStore().getTimers("delete-test-workflow").size())
    }

    @Test
    fun deleteWorkflow_shouldThrowExceptionForNonExistentWorkflow() {
        assertThrows(IllegalArgumentException::class.java) {
            workflowStore().deleteWorkflow("non-existent-workflow")
        }
    }

    @Test
    fun deleteWorkflow_shouldNotAffectOtherWorkflows() {
        // Create two workflows
        createInstanceForTimer("workflow-to-keep")
        createInstanceForTimer("workflow-to-delete")

        // Add checkpoints and timers to both
        val tag1 =
            CheckpointTag.builder()
                .workflowId("workflow-to-keep")
                .actionClass(Actions::class.java)
                .actionMethod("testAction")
                .iteration(1)
                .build()
        workflowStore()
            .storeActionCheckpoints(
                "workflow-to-keep",
                List.of(
                    ActionCheckpoint.builder()
                        .checkpointTag(tag1)
                        .executionStartTime(Instant.EPOCH)
                        .result(Either.right("result"))
                        .isTransient(false)
                        .build()
                )
            )

        val tag2 =
            CheckpointTag.builder()
                .workflowId("workflow-to-delete")
                .actionClass(Actions::class.java)
                .actionMethod("testAction")
                .iteration(1)
                .build()
        workflowStore()
            .storeActionCheckpoints(
                "workflow-to-delete",
                List.of(
                    ActionCheckpoint.builder()
                        .checkpointTag(tag2)
                        .executionStartTime(Instant.EPOCH)
                        .result(Either.right("result"))
                        .isTransient(false)
                        .build()
                )
            )

        // Delete only one workflow
        workflowStore().deleteWorkflow("workflow-to-delete")

        // Verify the other workflow and its data still exist
        assertTrue(workflowStore().getWorkflow("workflow-to-keep").isDefined)
        assertEquals(1, workflowStore().getActionCheckpoints("workflow-to-keep").size())

        // Verify the deleted workflow is gone
        assertTrue(workflowStore().getWorkflow("workflow-to-delete").isEmpty)
        assertEquals(0, workflowStore().getActionCheckpoints("workflow-to-delete").size())
    }

    @Test
    @Throws(Exception::class)
    fun resetWorkflowFromError_shouldResetWorkflowAndDeleteErrorCheckpoint() {
        // Create a workflow in ERROR status
        val request =
            WorkflowCreationRequest.builder()
                .workflowId(WORKFLOW_ID)
                .workflowClass(Workflow::class.java)
                .workflowMethod("workflowMethod")
                .input(TestInput("John", 30))
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(EXTRA_REQUEST_DATA)
                .build()
        workflowStore().createWorkflow(request)

        // Get the created workflow and update it to ERROR status with a non-transient error checkpoint
        val workflow = workflowStore().getWorkflow(WORKFLOW_ID).get()

        // Create a non-transient error checkpoint
        val checkpointTag =
            CheckpointTag.builder()
                .workflowId(WORKFLOW_ID)
                .actionClass(Actions::class.java)
                .actionMethod("testAction")
                .iteration(1)
                .build()

        val errorCheckpoint =
            ActionCheckpoint.builder()
                .checkpointTag(checkpointTag)
                .executionStartTime(Instant.now())
                .executionEndTime(Instant.now().plusSeconds(1))
                .result(Either.left(NonRetryableError("Test error")))
                .input("test input")
                .isTransient(false) // non-transient error
                .resultIsAsync(false)
                .build()

        // Update workflow to ERROR status
        val updateRequest =
            WorkflowUpdateRequest.builder()
                .workflowInstance(workflow)
                .newStatus(WorkflowInstance.Status.ERROR)
                .result(Either.left(NonRetryableError("Test error")))
                .resultIsAsync(false)
                .build()

        val errorWorkflow = workflowStore().updateWorkflow(updateRequest)
        workflowStore().storeActionCheckpoints(WORKFLOW_ID, List.of(errorCheckpoint))

        // Verify workflow is in ERROR status
        assertEquals(WorkflowInstance.Status.ERROR, errorWorkflow.status)

        // Test resetWorkflowFromError
        val resetResult = workflowStore().resetWorkflowFromError(WORKFLOW_ID)
        assertNotNull(resetResult)

        val resetWorkflow = resetResult._1

        // Verify workflow status is now RUNNING
        assertEquals(WorkflowInstance.Status.RUNNING, resetWorkflow.status)

        // Verify result is reset (not completed yet)
        assertFalse(resetWorkflow.result.isDone)

        // Verify the error checkpoint was deleted by checking we have fewer checkpoints
        val remainingCheckpoints = workflowStore().getActionCheckpoints(WORKFLOW_ID)
        // Should have no non-transient error checkpoints
        assertFalse(remainingCheckpoints.exists { cp -> !cp.isTransient && !cp.isSuccessful })
    }

    @Test
    @Throws(Exception::class)
    fun resetWorkflowFromError_shouldThrowExceptionForNonErrorWorkflow() {
        // Create a workflow in RUNNING status
        val request =
            WorkflowCreationRequest.builder()
                .workflowId(WORKFLOW_ID + "_running")
                .workflowClass(Workflow::class.java)
                .workflowMethod("workflowMethod")
                .input(TestInput("John", 30))
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(EXTRA_REQUEST_DATA)
                .build()
        workflowStore().createWorkflow(request)

        // Try to reset a workflow that's not in ERROR status
        val exception =
            assertThrows(IllegalStateException::class.java) {
                workflowStore().resetWorkflowFromError(WORKFLOW_ID + "_running")
            }

        assertTrue(exception.message!!.contains("is not in ERROR status"))
    }

    @Test
    fun resetWorkflowFromError_shouldThrowExceptionForNonExistentWorkflow() {
        // Try to reset a workflow that doesn't exist
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                workflowStore().resetWorkflowFromError("non-existent-workflow")
            }

        assertTrue(exception.message!!.contains("Workflow not found"))
    }

    @Test
    @Throws(Exception::class)
    fun resetWorkflowFromError_shouldSucceedWhenNoErrorCheckpointExists() {
        // Create a workflow in ERROR status but without any error checkpoints
        val request =
            WorkflowCreationRequest.builder()
                .workflowId(WORKFLOW_ID + "_no_checkpoint")
                .workflowClass(Workflow::class.java)
                .workflowMethod("workflowMethod")
                .input(TestInput("John", 30))
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(EXTRA_REQUEST_DATA)
                .build()
        workflowStore().createWorkflow(request)

        // Update workflow to ERROR status without adding any error checkpoint
        val workflow = workflowStore().getWorkflow(WORKFLOW_ID + "_no_checkpoint").get()
        val updateRequest =
            WorkflowUpdateRequest.builder()
                .workflowInstance(workflow)
                .newStatus(WorkflowInstance.Status.ERROR)
                .result(Either.left(NonRetryableError("Test error")))
                .resultIsAsync(false)
                .build()

        val errorWorkflow = workflowStore().updateWorkflow(updateRequest)
        assertEquals(WorkflowInstance.Status.ERROR, errorWorkflow.status)

        // Reset workflow - should succeed even without error checkpoint
        val resetResult = workflowStore().resetWorkflowFromError(WORKFLOW_ID + "_no_checkpoint")

        assertNotNull(resetResult)
        val resetWorkflow = resetResult._1
        val removedCheckpoint = resetResult._2

        // Verify workflow status is now RUNNING
        assertEquals(WorkflowInstance.Status.RUNNING, resetWorkflow.status)

        // Verify result is reset (not completed yet)
        assertFalse(resetWorkflow.result.isDone)

        // Verify no checkpoint was removed (since none existed)
        assertTrue(removedCheckpoint.isEmpty)
    }

    @Test
    @Throws(Exception::class)
    fun rewindWorkflow_shouldSetRunningAndDeletePivotAndLaterCheckpoints() {
        val workflowId = WORKFLOW_ID + "_rewind"
        val request =
            WorkflowCreationRequest.builder()
                .workflowId(workflowId)
                .workflowClass(Workflow::class.java)
                .workflowMethod("workflowMethod")
                .input(TestInput("John", 30))
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(EXTRA_REQUEST_DATA)
                .build()
        workflowStore().createWorkflow(request)

        // Store three checkpoints in execution order.
        workflowStore()
            .storeActionCheckpoints(
                workflowId,
                List.of(
                    buildNamedCheckpoint(workflowId, "step-0"),
                    buildNamedCheckpoint(workflowId, "step-1"),
                    buildNamedCheckpoint(workflowId, "step-2")
                )
            )

        // Drive the workflow into a terminal ERROR status to prove rewind works regardless of state.
        val workflow = workflowStore().getWorkflow(workflowId).get()
        val errorWorkflow =
            workflowStore()
                .updateWorkflow(
                    WorkflowUpdateRequest.builder()
                        .workflowInstance(workflow)
                        .newStatus(WorkflowInstance.Status.ERROR)
                        .result(Either.left(NonRetryableError("boom")))
                        .resultIsAsync(false)
                        .build()
                )
        assertEquals(WorkflowInstance.Status.ERROR, errorWorkflow.status)

        // Rewind to the middle checkpoint (matched by name).
        val pivot =
            CheckpointTag.builder()
                .workflowId(workflowId)
                .actionClass(Actions::class.java)
                .actionMethod("action")
                .iteration(0)
                .checkpointName("step-1")
                .build()
        val result = workflowStore().rewindWorkflow(workflowId, pivot)

        // The workflow is back to RUNNING with a reset result.
        assertEquals(WorkflowInstance.Status.RUNNING, result._1.status)
        assertFalse(result._1.result.isDone)

        // The pivot and every checkpoint after it were deleted and returned.
        val deletedNames = result._2.map { cp -> cp.checkpointTag.checkpointName }
        assertEquals(2, deletedNames.size())
        assertTrue(deletedNames.contains("step-1"))
        assertTrue(deletedNames.contains("step-2"))

        // Only the checkpoint before the pivot remains.
        val remaining = workflowStore().getActionCheckpoints(workflowId)
        assertEquals(1, remaining.size())
        assertEquals("step-0", remaining.get(0).checkpointTag.checkpointName)
    }

    @Test
    fun rewindWorkflow_shouldThrowExceptionForNonExistentWorkflow() {
        val pivot =
            CheckpointTag.builder()
                .workflowId("non-existent-rewind")
                .actionClass(Actions::class.java)
                .actionMethod("action")
                .iteration(0)
                .checkpointName("step-1")
                .build()
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                workflowStore().rewindWorkflow("non-existent-rewind", pivot)
            }
        assertTrue(exception.message!!.contains("Workflow not found"))
    }

    @Test
    @Throws(Exception::class)
    fun rewindWorkflow_shouldThrowExceptionWhenPivotCheckpointNotFound() {
        val workflowId = WORKFLOW_ID + "_rewind_no_pivot"
        val request =
            WorkflowCreationRequest.builder()
                .workflowId(workflowId)
                .workflowClass(Workflow::class.java)
                .workflowMethod("workflowMethod")
                .input(TestInput("John", 30))
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(EXTRA_REQUEST_DATA)
                .build()
        workflowStore().createWorkflow(request)

        val pivot =
            CheckpointTag.builder()
                .workflowId(workflowId)
                .actionClass(Actions::class.java)
                .actionMethod("action")
                .iteration(0)
                .checkpointName("does-not-exist")
                .build()
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                workflowStore().rewindWorkflow(workflowId, pivot)
            }
        assertTrue(exception.message!!.contains("Pivot checkpoint not found"))
    }

    private fun buildNamedCheckpoint(
        workflowId: String,
        checkpointName: String
    ): ActionCheckpoint {
        return ActionCheckpoint.builder()
            .checkpointTag(
                CheckpointTag.builder()
                    .workflowId(workflowId)
                    .actionClass(Actions::class.java)
                    .actionMethod("action")
                    .iteration(0)
                    .checkpointName(checkpointName)
                    .build()
            )
            .executionStartTime(Instant.now())
            .executionEndTime(Instant.now().plusSeconds(1))
            .result(Either.right("ok"))
            .input("test input")
            .isTransient(false)
            .resultIsAsync(false)
            .build()
    }

    // ── listDistinctWorkflowTypes tests ──

    @Test
    fun listDistinctWorkflowTypes_shouldReturnAtLeastOneType() {
        createWorkflow("wf1", Workflow::class.java, "execute")

        val types = workflowStore().listDistinctWorkflowTypes()

        assertFalse(types.isEmpty)
        assertTrue(types.exists { t -> t._1.equals(Workflow::class.java.name) && t._2.equals("execute") })
    }

    @Test
    fun listDistinctWorkflowTypes_shouldReturnEmptyWhenNoWorkflows() {
        val types = workflowStore().listDistinctWorkflowTypes()
        assertEquals(0, types.size())
    }

    // ── findWorkflows tests ──

    @Test
    fun findWorkflows_shouldReturnAllWorkflowsWhenNoFilters() {
        createWorkflow("wf1", Workflow::class.java, "execute")
        createWorkflow("wf2", Actions::class.java, "perform")

        val filter = WorkflowSearchFilter.builder().build()
        val results = workflowStore().findWorkflows(filter, 50)

        assertEquals(2, results.size())
    }

    @Test
    fun findWorkflows_shouldFilterByStatus() {
        createWorkflow("wf-running", Workflow::class.java, "execute")
        val completed = createWorkflow("wf-completed", Workflow::class.java, "execute")
        workflowStore()
            .updateWorkflow(
                WorkflowUpdateRequest.builder()
                    .workflowInstance(completed)
                    .newStatus(WorkflowInstance.Status.COMPLETED)
                    .result(Either.right("done"))
                    .resultIsAsync(false)
                    .build()
            )

        val filter =
            WorkflowSearchFilter.builder()
                .statuses(java.util.List.of(WorkflowInstance.Status.RUNNING))
                .build()
        val results = workflowStore().findWorkflows(filter, 50)

        assertEquals(1, results.size())
        assertEquals("wf-running", results.get(0).workflowId)
    }

    @Test
    fun findWorkflows_shouldFilterByEntryPoint() {
        createWorkflow("wf1", Workflow::class.java, "execute")
        createWorkflow("wf2", Actions::class.java, "perform")

        val filter =
            WorkflowSearchFilter.builder()
                .workflowEntryPoints(
                    java.util.List.of(
                        WorkflowSearchFilter.WorkflowEntryPoint(Actions::class.java.name, "perform")
                    )
                )
                .build()
        val results = workflowStore().findWorkflows(filter, 50)

        assertEquals(1, results.size())
        assertEquals("wf2", results.get(0).workflowId)
    }

    @Test
    fun findWorkflows_shouldFilterByMultipleEntryPoints() {
        createWorkflow("wf1", Workflow::class.java, "execute")
        createWorkflow("wf2", Actions::class.java, "perform")
        createWorkflow("wf3", Workflow::class.java, "run")

        val filter =
            WorkflowSearchFilter.builder()
                .workflowEntryPoints(
                    java.util.List.of(
                        WorkflowSearchFilter.WorkflowEntryPoint(Workflow::class.java.name, "execute"),
                        WorkflowSearchFilter.WorkflowEntryPoint(Workflow::class.java.name, "run")
                    )
                )
                .build()
        val results = workflowStore().findWorkflows(filter, 50)

        assertEquals(2, results.size())
        val ids = results.map { it.workflowId }.toJavaSet()
        assertTrue(ids.contains("wf1"))
        assertTrue(ids.contains("wf3"))
    }

    @Test
    fun findWorkflows_shouldFilterByParentWorkflowId() {
        val childRequest =
            WorkflowCreationRequest.builder()
                .workflowClass(Workflow::class.java)
                .workflowId("child-wf")
                .workflowMethod("execute")
                .input("test")
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(EXTRA_REQUEST_DATA)
                .parentWorkflowId("parent-wf")
                .build()
        workflowStore().createWorkflow(childRequest)
        createWorkflow("unrelated-wf", Workflow::class.java, "execute")

        val filter = WorkflowSearchFilter.builder().parentWorkflowId("parent-wf").build()
        val results = workflowStore().findWorkflows(filter, 50)

        assertEquals(1, results.size())
        assertEquals("child-wf", results.get(0).workflowId)
    }

    @Test
    fun findWorkflows_shouldRespectLimit() {
        for (i in 0 until 10) {
            createWorkflow("wf-$i", Workflow::class.java, "execute")
        }

        val filter = WorkflowSearchFilter.builder().build()
        val results = workflowStore().findWorkflows(filter, 3)

        assertEquals(3, results.size())
    }

    @Test
    fun findWorkflows_shouldReturnEmptyWhenNoMatch() {
        createWorkflow("wf1", Workflow::class.java, "execute")

        val filter =
            WorkflowSearchFilter.builder()
                .statuses(java.util.List.of(WorkflowInstance.Status.COMPLETED))
                .build()
        val results = workflowStore().findWorkflows(filter, 50)

        assertEquals(0, results.size())
    }

    @Test
    fun findWorkflows_shouldCombineStatusAndEntryPointFilters() {
        val wf1 = createWorkflow("wf1", Workflow::class.java, "execute")
        createWorkflow("wf2", Workflow::class.java, "execute")
        createWorkflow("wf3", Actions::class.java, "perform")
        // Complete wf1
        workflowStore()
            .updateWorkflow(
                WorkflowUpdateRequest.builder()
                    .workflowInstance(wf1)
                    .newStatus(WorkflowInstance.Status.COMPLETED)
                    .result(Either.right("done"))
                    .resultIsAsync(false)
                    .build()
            )

        // Filter: RUNNING + Workflow::execute → only wf2
        val filter =
            WorkflowSearchFilter.builder()
                .statuses(java.util.List.of(WorkflowInstance.Status.RUNNING))
                .workflowEntryPoints(
                    java.util.List.of(
                        WorkflowSearchFilter.WorkflowEntryPoint(Workflow::class.java.name, "execute")
                    )
                )
                .build()
        val results = workflowStore().findWorkflows(filter, 50)

        assertEquals(1, results.size())
        assertEquals("wf2", results.get(0).workflowId)
    }

    // ── Helpers ──

    private fun createWorkflow(
        workflowId: String,
        workflowClass: Class<*>,
        workflowMethod: String
    ): WorkflowInstance {
        @Suppress("UNCHECKED_CAST")
        val request =
            WorkflowCreationRequest.builder()
                .workflowClass(workflowClass as Class<out Workflow>)
                .workflowId(workflowId)
                .workflowMethod(workflowMethod)
                .input("test")
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(EXTRA_REQUEST_DATA)
                .build()
        return workflowStore().createWorkflow(request)
    }

    // ── Named-checkpoint round-trip tests (shared across storage adapters) ──

    @Test
    fun testStoreActionCheckpoints_namedCheckpointRoundTrips() {
        val workflowId = "named-checkpoint-workflow"
        workflowStore().createWorkflow(checkpointRoundTripWorkflowRequest(workflowId))
        val namedTag =
            CheckpointTag.builder()
                .workflowId(workflowId)
                .actionClass(Actions::class.java)
                .actionMethod("test-method")
                .iteration(0)
                .checkpointName("prepare-order")
                .build()

        workflowStore()
            .storeActionCheckpoints(workflowId, List.of(checkpointRoundTripBuildCheckpoint(namedTag)))

        val fetched = workflowStore().getActionCheckpoints(workflowId)
        assertEquals(1, fetched.size())
        assertEquals("prepare-order", fetched.get(0).checkpointTag.checkpointName)
        assertEquals(namedTag, fetched.get(0).checkpointTag)
    }

    @Test
    fun testStoreActionCheckpoints_unnamedCheckpointPersistsNullName() {
        val workflowId = "unnamed-checkpoint-workflow"
        workflowStore().createWorkflow(checkpointRoundTripWorkflowRequest(workflowId))
        val unnamedTag =
            CheckpointTag.builder()
                .workflowId(workflowId)
                .actionClass(Actions::class.java)
                .actionMethod("test-method")
                .iteration(1)
                .build()

        workflowStore()
            .storeActionCheckpoints(workflowId, List.of(checkpointRoundTripBuildCheckpoint(unnamedTag)))

        val fetched = workflowStore().getActionCheckpoints(workflowId)
        assertEquals(1, fetched.size())
        assertNull(fetched.get(0).checkpointTag.checkpointName)
        assertEquals(unnamedTag, fetched.get(0).checkpointTag)
    }

    @Test
    fun testStoreActionCheckpoints_mixedNamedAndUnnamedInSameWorkflow() {
        val workflowId = "mixed-checkpoint-workflow"
        workflowStore().createWorkflow(checkpointRoundTripWorkflowRequest(workflowId))
        val namedTag =
            CheckpointTag.builder()
                .workflowId(workflowId)
                .actionClass(Actions::class.java)
                .actionMethod("named-method")
                .iteration(0)
                .checkpointName("step-one")
                .build()
        val unnamedTag =
            CheckpointTag.builder()
                .workflowId(workflowId)
                .actionClass(Actions::class.java)
                .actionMethod("unnamed-method")
                .iteration(1)
                .build()

        workflowStore()
            .storeActionCheckpoints(
                workflowId,
                List.of(
                    checkpointRoundTripBuildCheckpoint(namedTag),
                    checkpointRoundTripBuildCheckpoint(unnamedTag)
                )
            )

        val fetched = workflowStore().getActionCheckpoints(workflowId)
        assertEquals(2, fetched.size())
        val named = fetched.find { cp -> cp.checkpointTag.checkpointName != null }.get()
        val unnamed = fetched.find { cp -> cp.checkpointTag.checkpointName == null }.get()
        assertEquals("step-one", named.checkpointTag.checkpointName)
        assertEquals(namedTag, named.checkpointTag)
        assertEquals(unnamedTag, unnamed.checkpointTag)
    }

    private fun checkpointRoundTripWorkflowRequest(workflowId: String): WorkflowCreationRequest {
        return WorkflowCreationRequest.builder()
            .workflowClass(Workflow::class.java)
            .workflowId(workflowId)
            .workflowMethod("test-method")
            .input("test-input")
            .requestContext(REQUEST_CONTEXT)
            .extraRequestData(EXTRA_REQUEST_DATA)
            .build()
    }

    private fun checkpointRoundTripBuildCheckpoint(tag: CheckpointTag): ActionCheckpoint {
        return ActionCheckpoint.builder()
            .checkpointTag(tag)
            .executionStartTime(Instant.EPOCH)
            .result(Either.right("result"))
            .isTransient(false)
            .build()
    }

    // ── Persisted signal tests (shared across storage adapters) ──

    @Test
    fun persistSignal_roundTripsAndAssignsId() {
        createInstanceForTimer("signal-workflow")

        val persisted =
            workflowStore()
                .persistSignal(
                    PersistedSignal(
                        "signal-workflow",
                        "doSignal",
                        PersistedSignal.Status.PENDING,
                        TestInput("Ricardo", 36),
                        REQUEST_CONTEXT
                    )
                )

        assertNotNull(persisted.id)
        val fetched = workflowStore().getPersistedSignal("signal-workflow", persisted.id!!)
        assertTrue(fetched.isDefined)
        val signal = fetched.get()
        assertEquals(persisted.id, signal.id)
        assertEquals("signal-workflow", signal.workflowId)
        assertEquals("doSignal", signal.signalMethod)
        assertEquals(TestInput("Ricardo", 36), signal.input)
        assertEquals(REQUEST_CONTEXT, signal.requestContext)
        assertEquals(PersistedSignal.Status.PENDING, signal.status)
        assertNull(signal.error)
    }

    @Test
    fun getPersistedSignals_returnsAllSignalsForWorkflowAndExcludesOthers() {
        createInstanceForTimer("signal-workflow")
        createInstanceForTimer("other-workflow")
        val first = persistPendingSignal("signal-workflow", "first")
        val second = persistPendingSignal("signal-workflow", "second")
        persistPendingSignal("other-workflow", "unrelated")

        val signals = workflowStore().getPersistedSignals("signal-workflow")

        assertEquals(2, signals.size())
        val ids = signals.map { it.id }.toJavaSet()
        assertTrue(ids.contains(first.id))
        assertTrue(ids.contains(second.id))
    }

    @Test
    fun getPersistedSignals_returnsEmptyWhenNoSignals() {
        createInstanceForTimer("signal-workflow")
        assertEquals(0, workflowStore().getPersistedSignals("signal-workflow").size())
    }

    @Test
    fun getPersistedSignal_returnsEmptyWhenNotFound() {
        createInstanceForTimer("signal-workflow")
        assertTrue(workflowStore().getPersistedSignal("signal-workflow", 999L).isEmpty)
    }

    @Test
    fun updateSignalStatus_updatesStatusAndError() {
        createInstanceForTimer("signal-workflow")
        val persisted = persistPendingSignal("signal-workflow", "doSignal")

        workflowStore()
            .updateSignalStatus(
                "signal-workflow",
                persisted.id!!,
                PersistedSignal.Status.FAILED,
                "boom"
            )

        val updated = workflowStore().getPersistedSignal("signal-workflow", persisted.id!!).get()
        assertEquals(PersistedSignal.Status.FAILED, updated.status)
        assertNotNull(updated.error)
        assertEquals("boom", updated.error)
    }

    @Test
    fun updateWorkflowAndMarkSignal_updatesWorkflowStateAndSignalStatusTogether() {
        val instance = createInstanceForTimer("signal-workflow")
        val persisted = persistPendingSignal("signal-workflow", "doSignal")
        val updateRequest =
            WorkflowUpdateRequest.builder()
                .workflowInstance(instance)
                .newStatus(WorkflowInstance.Status.COMPLETED)
                .newState(HashMap.of("name", "Ricardo"))
                .result(Either.right("done"))
                .resultIsAsync(false)
                .build()

        val result =
            workflowStore()
                .updateWorkflowAndMarkSignal(
                    updateRequest,
                    persisted.id!!,
                    PersistedSignal.Status.EXECUTED
                )

        // The returned workflow reflects the update...
        assertEquals(WorkflowInstance.Status.COMPLETED, result._1.status)
        assertEquals(PersistedSignal.Status.EXECUTED, result._2.status)
        // ...and both writes are durable.
        assertEquals(
            WorkflowInstance.Status.COMPLETED,
            workflowStore().getWorkflow("signal-workflow").get().status
        )
        assertEquals(
            PersistedSignal.Status.EXECUTED,
            workflowStore().getPersistedSignal("signal-workflow", persisted.id!!).get().status
        )
    }

    @Test
    fun deleteWorkflow_shouldDeletePersistedSignals() {
        createInstanceForTimer("signal-workflow")
        persistPendingSignal("signal-workflow", "doSignal")
        assertEquals(1, workflowStore().getPersistedSignals("signal-workflow").size())

        workflowStore().deleteWorkflow("signal-workflow")

        assertEquals(0, workflowStore().getPersistedSignals("signal-workflow").size())
    }

    private fun persistPendingSignal(
        workflowId: String,
        signalMethod: String
    ): PersistedSignal {
        return workflowStore()
            .persistSignal(
                PersistedSignal(
                    workflowId,
                    signalMethod,
                    PersistedSignal.Status.PENDING,
                    "test-input",
                    REQUEST_CONTEXT
                )
            )
    }

    data class TestInput(val name: String, val age: Int)

    companion object {
        private const val WORKFLOW_ID = "workflowId"
        private const val TIMER_ID = "timerId"
        private val DURATION: Duration = Duration.ofSeconds(10)
        private val EXPIRES_AT: Instant = Instant.EPOCH.plusSeconds(10)

        @JvmStatic
        fun assertWorkflowInstancesAreEqual(
            a: WorkflowInstance,
            b: WorkflowInstance
        ) {
            assertEquals(a.workflowId, b.workflowId)
            assertEquals(a.status, b.status)
            assertEquals(a.version, b.version)
            assertEquals(a.state, b.state)
            assertEquals(a.input, b.input)
            assertEquals(a.result.isDone, b.result.isDone)
            assertEquals(a.requestContext, b.requestContext)
            if (a.timeoutTime != null) {
                assertEquals(
                    a.timeoutTime!!.truncatedTo(ChronoUnit.MILLIS),
                    b.timeoutTime!!.truncatedTo(ChronoUnit.MILLIS)
                )
            } else {
                assertEquals(a.timeoutTime, b.timeoutTime)
            }
            if (a.result.isDone) {
                if (a.result.isCompletedExceptionally) {
                    try {
                        a.result.get()
                        throw AssertionError("Expected a to be completed exceptionally")
                    } catch (e: Throwable) {
                        try {
                            b.result.get()
                            throw AssertionError("Expected b to be completed exceptionally")
                        } catch (e2: Throwable) {
                            assertEquals(e.javaClass, e2.javaClass)
                            assertEquals(e.message, e2.message)
                        }
                    }
                }
            }
        }
    }
}
