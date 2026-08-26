package com.airbnb.skipper

import com.airbnb.skipper.internal.CheckpointTag
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.TestUtils.EXTRA_REQUEST_DATA
import com.airbnb.skipper.internal.TestUtils.REQUEST_CONTEXT
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.storage.WorkflowStore
import io.vavr.Tuple2
import io.vavr.collection.List.of
import io.vavr.control.Either
import io.vavr.control.Option
import java.time.Instant
import java.util.Arrays
import java.util.Collections
import java.util.Optional
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class WorkflowsServiceTest {
    private val workflowStore = mock<WorkflowStore>()

    private val skipperEngine = mock<SkipperEngine>()

    private val scheduler = mock<Scheduler>()

    private lateinit var workflowsService: WorkflowsService

    @BeforeEach
    fun setUp() {
        workflowsService = WorkflowsService(workflowStore, skipperEngine, scheduler)
    }

    @Test
    fun resetWorkflowsFromError_shouldSuccessfullyResetSingleWorkflow() {
        // Arrange
        val workflowId = "workflow-1"
        val resetWorkflow = createWorkflowInstance(workflowId, WorkflowInstance.Status.RUNNING)
        val removedCheckpoint = createTestActionCheckpoint(workflowId)
        val resetResult: Tuple2<WorkflowInstance, Option<ActionCheckpoint>> =
            Tuple2(resetWorkflow, Option.of(removedCheckpoint))

        whenever(workflowStore.resetWorkflowFromError(workflowId)).thenReturn(resetResult)
        doNothing().whenever(skipperEngine).scheduleExecution(resetWorkflow, false)

        // Act
        val result = workflowsService.resetWorkflowsFromError(Arrays.asList(workflowId))

        // Assert
        assertEquals(1, result.size)
        assertEquals(workflowId, result[0])
        verify(workflowStore).resetWorkflowFromError(workflowId)
        verify(skipperEngine).scheduleExecution(resetWorkflow, false)
    }

    @Test
    fun resetWorkflowsFromError_shouldSuccessfullyResetMultipleWorkflows() {
        // Arrange
        val workflowIds = Arrays.asList("workflow-1", "workflow-2", "workflow-3")

        for (workflowId in workflowIds) {
            val resetWorkflow = createWorkflowInstance(workflowId, WorkflowInstance.Status.RUNNING)
            val resetResult: Tuple2<WorkflowInstance, Option<ActionCheckpoint>> =
                Tuple2(resetWorkflow, Option.none())

            whenever(workflowStore.resetWorkflowFromError(workflowId)).thenReturn(resetResult)
            doNothing().whenever(skipperEngine).scheduleExecution(resetWorkflow, false)
        }

        // Act
        val result = workflowsService.resetWorkflowsFromError(workflowIds)

        // Assert
        assertEquals(3, result.size)
        assertTrue(result.containsAll(workflowIds))

        for (workflowId in workflowIds) {
            verify(workflowStore).resetWorkflowFromError(workflowId)
        }
        verify(skipperEngine, times(3)).scheduleExecution(any<WorkflowInstance>(), eq(false))
    }

    @Test
    fun resetWorkflowsFromError_shouldHandleMixedSuccessAndFailure() {
        // Arrange
        val successWorkflowId = "workflow-success"
        val failWorkflowId = "workflow-fail"
        val workflowIds = Arrays.asList(successWorkflowId, failWorkflowId)

        // Setup successful workflow
        val resetWorkflow = createWorkflowInstance(successWorkflowId, WorkflowInstance.Status.RUNNING)
        val resetResult: Tuple2<WorkflowInstance, Option<ActionCheckpoint>> =
            Tuple2(resetWorkflow, Option.none())
        whenever(workflowStore.resetWorkflowFromError(successWorkflowId)).thenReturn(resetResult)
        doNothing().whenever(skipperEngine).scheduleExecution(resetWorkflow, false)

        // Setup failing workflow
        whenever(workflowStore.resetWorkflowFromError(failWorkflowId))
            .thenThrow(IllegalArgumentException("Workflow not found: $failWorkflowId"))

        // Act
        val result = workflowsService.resetWorkflowsFromError(workflowIds)

        // Assert
        assertEquals(1, result.size)
        assertEquals(successWorkflowId, result[0])

        verify(workflowStore).resetWorkflowFromError(successWorkflowId)
        verify(workflowStore).resetWorkflowFromError(failWorkflowId)
        verify(skipperEngine, times(1)).scheduleExecution(resetWorkflow, false)
    }

    @Test
    fun resetWorkflowsFromError_shouldHandleWorkflowNotFoundError() {
        // Arrange
        val workflowId = "non-existent-workflow"
        whenever(workflowStore.resetWorkflowFromError(workflowId))
            .thenThrow(IllegalArgumentException("Workflow not found: $workflowId"))

        // Act
        val result = workflowsService.resetWorkflowsFromError(Arrays.asList(workflowId))

        // Assert
        assertTrue(result.isEmpty())
        verify(workflowStore).resetWorkflowFromError(workflowId)
        verify(skipperEngine, never()).scheduleExecution(any(), eq(false))
    }

    @Test
    fun resetWorkflowsFromError_shouldHandleWorkflowNotInErrorStatusError() {
        // Arrange
        val workflowId = "running-workflow"
        whenever(workflowStore.resetWorkflowFromError(workflowId))
            .thenThrow(
                IllegalStateException(
                    "Workflow $workflowId is not in ERROR status. Current status: RUNNING"
                )
            )

        // Act
        val result = workflowsService.resetWorkflowsFromError(Arrays.asList(workflowId))

        // Assert
        assertTrue(result.isEmpty())
        verify(workflowStore).resetWorkflowFromError(workflowId)
        verify(skipperEngine, never()).scheduleExecution(any(), eq(false))
    }

    @Test
    fun resetWorkflowsFromError_shouldHandleSchedulingFailure() {
        // Arrange
        val workflowId = "workflow-1"
        val resetWorkflow = createWorkflowInstance(workflowId, WorkflowInstance.Status.RUNNING)
        val resetResult: Tuple2<WorkflowInstance, Option<ActionCheckpoint>> =
            Tuple2(resetWorkflow, Option.none())

        whenever(workflowStore.resetWorkflowFromError(workflowId)).thenReturn(resetResult)
        doThrow(RuntimeException("Failed to schedule execution"))
            .whenever(skipperEngine)
            .scheduleExecution(resetWorkflow, false)

        // Act
        val result = workflowsService.resetWorkflowsFromError(Arrays.asList(workflowId))

        // Assert
        assertTrue(result.isEmpty())
        verify(workflowStore).resetWorkflowFromError(workflowId)
        verify(skipperEngine).scheduleExecution(resetWorkflow, false)
    }

    @Test
    fun resetWorkflowsFromError_shouldHandleEmptyWorkflowIdsList() {
        // Act
        val result = workflowsService.resetWorkflowsFromError(Collections.emptyList())

        // Assert
        assertTrue(result.isEmpty())
        verify(workflowStore, never()).resetWorkflowFromError(any())
        verify(skipperEngine, never()).scheduleExecution(any(), eq(false))
    }

    @Test
    fun resetWorkflowsFromError_shouldResetWorkflowWithoutErrorCheckpoint() {
        // Arrange
        val workflowId = "workflow-no-checkpoint"
        val resetWorkflow = createWorkflowInstance(workflowId, WorkflowInstance.Status.RUNNING)
        // Simulate no error checkpoint was removed (Option.none())
        val resetResult: Tuple2<WorkflowInstance, Option<ActionCheckpoint>> =
            Tuple2(resetWorkflow, Option.none())

        whenever(workflowStore.resetWorkflowFromError(workflowId)).thenReturn(resetResult)
        doNothing().whenever(skipperEngine).scheduleExecution(resetWorkflow, false)

        // Act
        val result = workflowsService.resetWorkflowsFromError(Arrays.asList(workflowId))

        // Assert
        assertEquals(1, result.size)
        assertEquals(workflowId, result[0])
        verify(workflowStore).resetWorkflowFromError(workflowId)
        verify(skipperEngine).scheduleExecution(resetWorkflow, false)
    }

    @Test
    fun resetWorkflowsFromError_shouldContinueProcessingAfterFailure() {
        // Arrange
        val workflowIds = Arrays.asList("workflow-1", "workflow-2", "workflow-3")

        // Setup first workflow to succeed
        val resetWorkflow1 = createWorkflowInstance("workflow-1", WorkflowInstance.Status.RUNNING)
        whenever(workflowStore.resetWorkflowFromError("workflow-1"))
            .thenReturn(Tuple2(resetWorkflow1, Option.none()))
        doNothing().whenever(skipperEngine).scheduleExecution(resetWorkflow1, false)

        // Setup second workflow to fail
        whenever(workflowStore.resetWorkflowFromError("workflow-2"))
            .thenThrow(IllegalArgumentException("Workflow not found"))

        // Setup third workflow to succeed
        val resetWorkflow3 = createWorkflowInstance("workflow-3", WorkflowInstance.Status.RUNNING)
        whenever(workflowStore.resetWorkflowFromError("workflow-3"))
            .thenReturn(Tuple2(resetWorkflow3, Option.none()))
        doNothing().whenever(skipperEngine).scheduleExecution(resetWorkflow3, false)

        // Act
        val result = workflowsService.resetWorkflowsFromError(workflowIds)

        // Assert
        assertEquals(2, result.size)
        assertTrue(result.contains("workflow-1"))
        assertTrue(result.contains("workflow-3"))

        // Verify all workflows were attempted
        verify(workflowStore, times(3)).resetWorkflowFromError(any())
        verify(skipperEngine, times(2)).scheduleExecution(any<WorkflowInstance>(), eq(false))
    }

    @Test
    fun reExecuteWorkflows_shouldUseCompensationManagerForCompensationInProgressWorkflows() {
        // Arrange
        val workflowId = "compensation-workflow"
        val compensationWorkflow =
            createWorkflowInstance(workflowId, WorkflowInstance.Status.COMPENSATION_IN_PROGRESS)
        whenever(workflowStore.getWorkflow(workflowId)).thenReturn(Option.of(compensationWorkflow))
        doNothing().whenever(skipperEngine).scheduleCompensationTask(compensationWorkflow)

        // Act
        val result: Map<String, Optional<Throwable>> =
            workflowsService.reExecuteWorkflows(Arrays.asList(workflowId))

        // Assert
        assertEquals(1, result.size)
        assertFalse(result[workflowId]!!.isPresent) // Should succeed

        verify(workflowStore).getWorkflow(workflowId)
        verify(skipperEngine).scheduleCompensationTask(compensationWorkflow)
        verify(skipperEngine, never()).scheduleExecution(any(), eq(false))
    }

    @Test
    fun reExecuteWorkflows_shouldUseSkipperEngineForNonCompensationWorkflows() {
        // Arrange
        val workflowId = "regular-workflow"
        val regularWorkflow = createWorkflowInstance(workflowId, WorkflowInstance.Status.RUNNING)
        whenever(workflowStore.getWorkflow(workflowId)).thenReturn(Option.of(regularWorkflow))
        doNothing().whenever(skipperEngine).scheduleExecution(regularWorkflow, false)

        // Act
        val result: Map<String, Optional<Throwable>> =
            workflowsService.reExecuteWorkflows(Arrays.asList(workflowId))

        // Assert
        assertEquals(1, result.size)
        assertFalse(result[workflowId]!!.isPresent) // Should succeed

        verify(workflowStore).getWorkflow(workflowId)
        verify(skipperEngine).scheduleExecution(regularWorkflow, false)
        verify(skipperEngine, never()).scheduleCompensationTask(any())
    }

    @Test
    fun rewindWorkflow_byCheckpointTag_shouldDelegateToStoreScheduleAndReturnInstance() {
        // Arrange
        val workflowId = "workflow-1"
        val pivot =
            CheckpointTag.builder()
                .workflowId(workflowId)
                .actionClass(Actions::class.java)
                .actionMethod("failingAction")
                .iteration(0L)
                .build()
        val rewound = createWorkflowInstance(workflowId, WorkflowInstance.Status.RUNNING)
        whenever(workflowStore.rewindWorkflow(workflowId, pivot))
            .thenReturn(Tuple2(rewound, of(createTestActionCheckpoint(workflowId))))
        doNothing().whenever(skipperEngine).scheduleExecution(rewound, false)

        // Act
        val result = workflowsService.rewindWorkflow(workflowId, pivot)

        // Assert
        assertEquals(rewound, result)
        verify(workflowStore).rewindWorkflow(workflowId, pivot)
        verify(skipperEngine).scheduleExecution(rewound, false)
    }

    @Test
    fun rewindWorkflow_byCheckpointName_shouldResolveTagDelegateScheduleAndReturnInstance() {
        // Arrange
        val workflowId = "workflow-1"
        val otherCheckpoint = createNamedActionCheckpoint(workflowId, "first-step")
        val matchingCheckpoint = createNamedActionCheckpoint(workflowId, "failing-step")
        whenever(workflowStore.getActionCheckpoints(workflowId))
            .thenReturn(of(otherCheckpoint, matchingCheckpoint))

        val resolvedPivot = matchingCheckpoint.checkpointTag
        val rewound = createWorkflowInstance(workflowId, WorkflowInstance.Status.RUNNING)
        whenever(workflowStore.rewindWorkflow(workflowId, resolvedPivot))
            .thenReturn(Tuple2(rewound, of(matchingCheckpoint)))
        doNothing().whenever(skipperEngine).scheduleExecution(rewound, false)

        // Act
        val result = workflowsService.rewindWorkflow(workflowId, "failing-step")

        // Assert
        assertEquals(rewound, result)
        verify(workflowStore).getActionCheckpoints(workflowId)
        verify(workflowStore).rewindWorkflow(workflowId, resolvedPivot)
        verify(skipperEngine).scheduleExecution(rewound, false)
    }

    @Test
    fun rewindWorkflow_byCheckpointName_shouldThrowWhenNameNotFound() {
        // Arrange
        val workflowId = "workflow-1"
        whenever(workflowStore.getActionCheckpoints(workflowId))
            .thenReturn(of(createNamedActionCheckpoint(workflowId, "first-step")))

        // Act & Assert
        assertThrows(IllegalArgumentException::class.java) {
            workflowsService.rewindWorkflow(workflowId, "non-existent-step")
        }

        verify(workflowStore, never()).rewindWorkflow(any(), any<CheckpointTag>())
        verify(skipperEngine, never()).scheduleExecution(any(), eq(false))
    }

    @Test
    fun rewindWorkflow_byCheckpointTag_shouldPropagateStoreException() {
        // Arrange
        val workflowId = "workflow-1"
        val pivot =
            CheckpointTag.builder()
                .workflowId(workflowId)
                .actionClass(Actions::class.java)
                .actionMethod("failingAction")
                .iteration(0L)
                .build()
        whenever(workflowStore.rewindWorkflow(workflowId, pivot))
            .thenThrow(IllegalArgumentException("Pivot checkpoint not found"))

        // Act & Assert
        assertThrows(IllegalArgumentException::class.java) {
            workflowsService.rewindWorkflow(workflowId, pivot)
        }

        verify(workflowStore).rewindWorkflow(workflowId, pivot)
        verify(skipperEngine, never()).scheduleExecution(any(), eq(false))
    }

    @Test
    fun rewindWorkflow_byCheckpointTag_shouldThrowWhenWorkflowNotFound() {
        // Arrange
        val workflowId = "non-existent-workflow"
        val pivot =
            CheckpointTag.builder()
                .workflowId(workflowId)
                .actionClass(Actions::class.java)
                .actionMethod("failingAction")
                .iteration(0L)
                .build()
        whenever(workflowStore.rewindWorkflow(workflowId, pivot))
            .thenThrow(IllegalArgumentException("Workflow not found: $workflowId"))

        // Act & Assert
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                workflowsService.rewindWorkflow(workflowId, pivot)
            }
        assertTrue(exception.message!!.contains("Workflow not found"))

        verify(workflowStore).rewindWorkflow(workflowId, pivot)
        verify(skipperEngine, never()).scheduleExecution(any(), eq(false))
    }

    private fun createWorkflowInstance(
        workflowId: String,
        status: WorkflowInstance.Status
    ): WorkflowInstance {
        return WorkflowInstance.builder()
            .workflowId(workflowId)
            .workflowClass(TestWorkflow::class.java)
            .workflowMethod("execute")
            .input(null)
            .version(1)
            .status(status)
            .state(io.vavr.collection.HashMap.empty())
            .result(CompletableFuture())
            .callbackHandler(null)
            .timeoutTime(null)
            .requestContext(REQUEST_CONTEXT)
            .extraRequestData(EXTRA_REQUEST_DATA)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build()
    }

    private fun createTestActionCheckpoint(workflowId: String): ActionCheckpoint {
        val checkpointTag =
            CheckpointTag.builder()
                .workflowId(workflowId)
                .actionClass(Actions::class.java)
                .actionMethod("testAction")
                .iteration(1L)
                .build()

        return ActionCheckpoint.builder()
            .checkpointTag(checkpointTag)
            .executionStartTime(Instant.now())
            .executionEndTime(Instant.now().plusSeconds(1))
            .result(Either.left(NonRetryableError("Test error")))
            .input("test input")
            .isTransient(false)
            .resultIsAsync(false)
            .build()
    }

    private fun createNamedActionCheckpoint(
        workflowId: String,
        checkpointName: String
    ): ActionCheckpoint {
        val checkpointTag =
            CheckpointTag.builder()
                .workflowId(workflowId)
                .actionClass(Actions::class.java)
                .actionMethod("namedAction")
                .iteration(0L)
                .checkpointName(checkpointName)
                .build()

        return ActionCheckpoint.builder()
            .checkpointTag(checkpointTag)
            .executionStartTime(Instant.now())
            .executionEndTime(Instant.now().plusSeconds(1))
            .result(Either.left(NonRetryableError("Test error")))
            .input("test input")
            .isTransient(false)
            .resultIsAsync(false)
            .build()
    }

    // Test workflow class for mocking
    class TestWorkflow : Workflow() {
        fun execute() {
            // Test workflow implementation
        }
    }
}
