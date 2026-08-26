package com.airbnb.skipper

import com.airbnb.skipper.api.WorkflowInstanceView
import com.airbnb.skipper.internal.TestUtils.EXTRA_REQUEST_DATA
import com.airbnb.skipper.internal.TestUtils.REQUEST_CONTEXT
import com.airbnb.skipper.internal.scheduler.ScheduleRequest
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.storage.WorkflowCreationRequest
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.internal.storage.WorkflowUpdateRequest
import com.airbnb.skipper.testutils.TestRuntime
import io.vavr.collection.List
import io.vavr.control.Either
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WorkflowServiceTest {
    private lateinit var workflowStore: WorkflowStore
    private lateinit var workflowsService: WorkflowsService
    private lateinit var scheduler: Scheduler

    @BeforeEach
    fun setUp() {
        val deps = TestRuntime()
        val runtime = deps.runtime
        workflowStore = runtime.workflowStore.get()
        workflowsService = runtime.workflowsService.get()
        scheduler = runtime.scheduler.get()
    }

    @Test
    fun testReExecuteWorkflows() {
        val instance1 = createWorkflowInstance("test-workflow-1")
        updateStatus(instance1, WorkflowInstance.Status.RETRIES_EXHAUSTED, null)
        val instance2 = createWorkflowInstance("test-workflow-2")
        updateStatus(instance2, WorkflowInstance.Status.COMPLETED, "completed")
        val instance3 = createWorkflowInstance("test-workflow-3")
        updateStatus(instance3, WorkflowInstance.Status.TRANSIENT_ERROR, null)

        val results: Map<String, Optional<Throwable>> =
            workflowsService.reExecuteWorkflows(
                List.of(instance1.workflowId, instance2.workflowId, instance3.workflowId)
                    .toJavaList()
            )
        assertEquals(3, results.size)
        assertFalse(results[instance1.workflowId]!!.isPresent)
        assertTrue(results[instance2.workflowId]!!.isPresent)
        assertFalse(results[instance3.workflowId]!!.isPresent)
        val error = results[instance2.workflowId]!!.get()
        assertTrue(error.message!!.contains("Cannot re-execute a terminal workflow instance"))
    }

    @Test
    fun testFindWorkflowsWithExhaustedRetries() {
        val instance1 = createWorkflowInstance("test-workflow-1")
        updateStatus(instance1, WorkflowInstance.Status.RETRIES_EXHAUSTED, null)
        val instance2 = createWorkflowInstance("test-workflow-2")
        updateStatus(instance2, WorkflowInstance.Status.COMPLETED, "completed")
        val instance3 = createWorkflowInstance("test-workflow-3")
        updateStatus(instance3, WorkflowInstance.Status.TRANSIENT_ERROR, null)

        val results: kotlin.collections.List<WorkflowInstanceView> =
            workflowsService.findWorkflowsWithExhaustedRetries(100)
        assertEquals(1, results.size)
        assertEquals(instance1.workflowId, results[0].id)
    }

    @Test
    fun testFindWorkflowsWithExhaustedRetriesWhenLimitIsInvalid() {
        var error =
            assertThrows(IllegalArgumentException::class.java) {
                workflowsService.findWorkflowsWithExhaustedRetries(-1)
            }
        assertTrue(error.message!!.contains("limit must be between 1 and"))
        error =
            assertThrows(IllegalArgumentException::class.java) {
                workflowsService.findWorkflowsWithExhaustedRetries(
                    WorkflowsService.BATCH_OPERATION_LIMIT + 1
                )
            }
        assertTrue(error.message!!.contains("limit must be between 1 and"))
    }

    @Test
    fun testInspectAndRedriveDLQ() {
        // First create a bunch of tasks in the DLQ
        val task1: Task<String> =
            scheduler.schedule(
                ScheduleRequest.builder<String>()
                    .type(Task.Type.WORKFLOW)
                    .id("test-workflow-1")
                    .dedupToken("test-workflow-1-dedup")
                    .payload("test-workflow-1-payload")
                    .build()
            )
        val task2: Task<String> =
            scheduler.schedule(
                ScheduleRequest.builder<String>()
                    .type(Task.Type.WORKFLOW)
                    .id("test-workflow-2")
                    .dedupToken("test-workflow-2-dedup")
                    .payload("test-workflow-2-payload")
                    .build()
            )
        scheduler.markAsFailed(task1, "error")

        var dlq: kotlin.collections.List<Task<Any>> = workflowsService.inspectDeadLetterQueue(10)
        assertEquals(1, dlq.size)
        assertEquals("test-workflow-1", dlq[0].id)

        val requeuedIds: kotlin.collections.List<String> = workflowsService.redriveDeadLetterQueue(dlq)
        assertEquals(1, requeuedIds.size)
        assertEquals("test-workflow-1", requeuedIds[0])
        // Verify that the task is no longer in the DLQ
        dlq = workflowsService.inspectDeadLetterQueue(10)
        assertEquals(0, dlq.size)
    }

    @Test
    fun testInspectAndRemoveFromDlq() {
        // First create a task in the DLQ
        val task: Task<String> =
            scheduler.schedule(
                ScheduleRequest.builder<String>()
                    .type(Task.Type.WORKFLOW)
                    .id("test-workflow-1")
                    .dedupToken("test-workflow-1-dedup")
                    .payload("test-workflow-1-payload")
                    .build()
            )
        val task2: Task<String> =
            scheduler.schedule(
                ScheduleRequest.builder<String>()
                    .type(Task.Type.WORKFLOW)
                    .id("test-workflow-2")
                    .dedupToken("test-workflow-2-dedup")
                    .payload("test-workflow-2-payload")
                    .build()
            )
        scheduler.markAsFailed(task, "error")

        var dlq: kotlin.collections.List<Task<Any>> = workflowsService.inspectDeadLetterQueue(10)
        assertEquals(1, dlq.size)
        assertEquals("test-workflow-1", dlq[0].id)

        val removed: kotlin.collections.List<String> = workflowsService.removeFromDeadLetterQueue(dlq)
        assertEquals(1, removed.size)
        // Verify that the task is no longer in the DLQ
        dlq = workflowsService.inspectDeadLetterQueue(10)
        assertEquals(0, dlq.size)
    }

    @Test
    fun testCancelWorkflows() {
        val instance1 = createWorkflowInstance("test-workflow-1")
        updateStatus(instance1, WorkflowInstance.Status.RUNNING, null)
        val instance2 = createWorkflowInstance("test-workflow-2")
        updateStatus(instance2, WorkflowInstance.Status.RETRIES_EXHAUSTED, null)
        val instance3 = createWorkflowInstance("test-workflow-3")
        updateStatus(instance3, WorkflowInstance.Status.TRANSIENT_ERROR, null)

        val cancelledIds: kotlin.collections.List<String> =
            workflowsService.cancelWorkflows(
                List.of(instance1.workflowId, instance2.workflowId, instance3.workflowId)
                    .toJavaList(),
                "Test cancellation reason"
            )

        assertEquals(3, cancelledIds.size)
        assertTrue(cancelledIds.contains(instance1.workflowId))
        assertTrue(cancelledIds.contains(instance2.workflowId))
        assertTrue(cancelledIds.contains(instance3.workflowId))

        // Verify workflows are cancelled (status changed to CANCELED)
        val cancelledInstance1 = workflowStore.getWorkflow(instance1.workflowId).get()
        val cancelledInstance2 = workflowStore.getWorkflow(instance2.workflowId).get()
        val cancelledInstance3 = workflowStore.getWorkflow(instance3.workflowId).get()

        assertEquals(WorkflowInstance.Status.CANCELLED, cancelledInstance1.status)
        assertEquals(WorkflowInstance.Status.CANCELLED, cancelledInstance2.status)
        assertEquals(WorkflowInstance.Status.CANCELLED, cancelledInstance3.status)
    }

    @Test
    fun testCancelWorkflowsWithTerminalWorkflows() {
        val instance1 = createWorkflowInstance("test-workflow-1")
        updateStatus(instance1, WorkflowInstance.Status.RUNNING, null)
        val instance2 = createWorkflowInstance("test-workflow-2")
        updateStatus(instance2, WorkflowInstance.Status.COMPLETED, "completed")
        val instance3 = createWorkflowInstance("test-workflow-3")
        updateStatus(instance3, WorkflowInstance.Status.RETRIES_EXHAUSTED, null)

        val cancelledIds: kotlin.collections.List<String> =
            workflowsService.cancelWorkflows(
                List.of(instance1.workflowId, instance2.workflowId, instance3.workflowId)
                    .toJavaList(),
                "Test cancellation reason"
            )

        // Only instance1 and instance3 should be cancelled (instance2 is already terminal)
        assertEquals(2, cancelledIds.size)
        assertTrue(cancelledIds.contains(instance1.workflowId))
        assertFalse(cancelledIds.contains(instance2.workflowId))
        assertTrue(cancelledIds.contains(instance3.workflowId))

        // Verify workflows status
        val instance1After = workflowStore.getWorkflow(instance1.workflowId).get()
        val instance2After = workflowStore.getWorkflow(instance2.workflowId).get()
        val instance3After = workflowStore.getWorkflow(instance3.workflowId).get()

        assertEquals(WorkflowInstance.Status.CANCELLED, instance1After.status)
        assertEquals(WorkflowInstance.Status.COMPLETED, instance2After.status) // unchanged
        assertEquals(WorkflowInstance.Status.CANCELLED, instance3After.status)
    }

    @Test
    fun testCancelWorkflowsWithNonExistentWorkflows() {
        val instance1 = createWorkflowInstance("test-workflow-1")
        updateStatus(instance1, WorkflowInstance.Status.RUNNING, null)
        val nonExistentWorkflowId = "non-existent-workflow"

        val cancelledIds: kotlin.collections.List<String> =
            workflowsService.cancelWorkflows(
                List.of(instance1.workflowId, nonExistentWorkflowId).toJavaList(),
                "Test cancellation reason"
            )

        // Only instance1 should be cancelled (non-existent workflow should be ignored)
        assertEquals(1, cancelledIds.size)
        assertTrue(cancelledIds.contains(instance1.workflowId))
        assertFalse(cancelledIds.contains(nonExistentWorkflowId))

        // Verify instance1 is cancelled
        val cancelledInstance = workflowStore.getWorkflow(instance1.workflowId).get()
        assertEquals(WorkflowInstance.Status.CANCELLED, cancelledInstance.status)
    }

    @Test
    fun testCancelWorkflowsWithEmptyList() {
        val cancelledIds: kotlin.collections.List<String> =
            workflowsService.cancelWorkflows(emptyList(), "Test cancellation reason")

        assertEquals(0, cancelledIds.size)
    }

    private fun updateStatus(
        instance: WorkflowInstance,
        status: WorkflowInstance.Status,
        result: Any?
    ): WorkflowInstance {
        val updateRequest =
            WorkflowUpdateRequest.builder()
                .workflowInstance(instance)
                .newStatus(status)
                .result(if (result != null) Either.right(result) else null)
                .resultIsAsync(if (result != null) false else null)
                .build()
        return workflowStore.updateWorkflow(updateRequest)
    }

    private fun createWorkflowInstance(workflowId: String): WorkflowInstance {
        val request =
            WorkflowCreationRequest.builder()
                .workflowClass(Workflow::class.java)
                .workflowId(workflowId)
                .workflowMethod("test-method")
                .input("test")
                .requestContext(REQUEST_CONTEXT)
                .extraRequestData(EXTRA_REQUEST_DATA)
                .timeoutTime(null)
                .build()
        return workflowStore.createWorkflow(request)
    }
}
