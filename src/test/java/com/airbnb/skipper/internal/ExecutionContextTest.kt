package com.airbnb.skipper.internal

import com.airbnb.skipper.Actions
import com.airbnb.skipper.Compensate
import com.airbnb.skipper.Execute
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.internal.api.ActionCheckpoint
import io.vavr.collection.List
import io.vavr.control.Either
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class ExecutionContextTest {
    private val executorService = Executors.newSingleThreadExecutor()

    @Test
    fun testGetActionRetryCount() {
        val checkpointTag =
            CheckpointTag.builder()
                .workflowId("workflowInstanceId")
                .actionClass(Workflow::class.java)
                .actionMethod("actionId")
                .iteration(1)
                .build()
        val context =
            ExecutionContext.builder()
                .actionCheckpoints(
                    List.of(
                        ActionCheckpoint.builder()
                            .checkpointTag(checkpointTag)
                            .isTransient(true)
                            .executionStartTime(Instant.EPOCH)
                            .result(Either.left(RuntimeException("error")))
                            .build(),
                        ActionCheckpoint.builder()
                            .checkpointTag(checkpointTag)
                            .isTransient(true)
                            .executionStartTime(Instant.EPOCH)
                            .result(Either.left(RuntimeException("error")))
                            .build(),
                        ActionCheckpoint.builder()
                            .checkpointTag(checkpointTag.toBuilder().iteration(2).build())
                            .isTransient(true)
                            .executionStartTime(Instant.EPOCH)
                            .result(Either.left(RuntimeException("error")))
                            .build(),
                    ),
                )
                .workflow(TestUtils.getWorkflowInstance())
                .clock(mock(Clock::class.java))
                .executorService(executorService)
                .build()
        assertEquals(2, context.getActionRetryCount(checkpointTag))
        assertEquals(1, context.getActionRetryCount(checkpointTag.toBuilder().iteration(2).build()))
        assertEquals(0, context.getActionRetryCount(checkpointTag.toBuilder().iteration(3).build()))
    }

    @Test
    fun testHasCompensationFlow_withSuccessfulCompensableAction() {
        val checkpointTag =
            CheckpointTag.builder()
                .workflowId("workflowInstanceId")
                .actionClass(TestCompensableActions::class.java)
                .actionMethod("compensableAction")
                .iteration(0)
                .build()

        val context =
            ExecutionContext.builder()
                .actionCheckpoints(
                    List.of(
                        ActionCheckpoint.builder()
                            .checkpointTag(checkpointTag)
                            .isTransient(false)
                            .executionStartTime(Instant.EPOCH)
                            .executionEndTime(Instant.EPOCH)
                            .result(Either.right("success"))
                            .build(),
                    ),
                )
                .workflow(TestUtils.getWorkflowInstance())
                .clock(mock(Clock::class.java))
                .executorService(executorService)
                .build()

        assertTrue(context.hasCompensationFlow())
    }

    @Test
    fun testHasCompensationFlow_withSuccessfulNonCompensableAction() {
        val checkpointTag =
            CheckpointTag.builder()
                .workflowId("workflowInstanceId")
                .actionClass(TestNonCompensableActions::class.java)
                .actionMethod("regularAction")
                .iteration(0)
                .build()

        val context =
            ExecutionContext.builder()
                .actionCheckpoints(
                    List.of(
                        ActionCheckpoint.builder()
                            .checkpointTag(checkpointTag)
                            .isTransient(false)
                            .executionStartTime(Instant.EPOCH)
                            .executionEndTime(Instant.EPOCH)
                            .result(Either.right("success"))
                            .build(),
                    ),
                )
                .workflow(TestUtils.getWorkflowInstance())
                .clock(mock(Clock::class.java))
                .executorService(executorService)
                .build()

        assertFalse(context.hasCompensationFlow())
    }

    @Test
    fun testHasCompensationFlow_withFailedCompensableAction() {
        val checkpointTag =
            CheckpointTag.builder()
                .workflowId("workflowInstanceId")
                .actionClass(TestCompensableActions::class.java)
                .actionMethod("compensableAction")
                .iteration(0)
                .build()

        val context =
            ExecutionContext.builder()
                .actionCheckpoints(
                    List.of(
                        ActionCheckpoint.builder()
                            .checkpointTag(checkpointTag)
                            .isTransient(false)
                            .executionStartTime(Instant.EPOCH)
                            .executionEndTime(Instant.EPOCH)
                            .result(Either.left(RuntimeException("failed")))
                            .build(),
                    ),
                )
                .workflow(TestUtils.getWorkflowInstance())
                .clock(mock(Clock::class.java))
                .executorService(executorService)
                .build()

        assertFalse(context.hasCompensationFlow())
    }

    @Test
    fun testHasCompensationFlow_withTransientCompensableAction() {
        val checkpointTag =
            CheckpointTag.builder()
                .workflowId("workflowInstanceId")
                .actionClass(TestCompensableActions::class.java)
                .actionMethod("compensableAction")
                .iteration(0)
                .build()

        val context =
            ExecutionContext.builder()
                .actionCheckpoints(
                    List.of(
                        ActionCheckpoint.builder()
                            .checkpointTag(checkpointTag)
                            .isTransient(true)
                            .executionStartTime(Instant.EPOCH)
                            .executionEndTime(Instant.EPOCH)
                            .result(Either.right("success"))
                            .build(),
                    ),
                )
                .workflow(TestUtils.getWorkflowInstance())
                .clock(mock(Clock::class.java))
                .executorService(executorService)
                .build()

        assertFalse(context.hasCompensationFlow())
    }

    @Test
    fun testHasCompensationFlow_withDirtyCheckpoints() {
        val checkpointTag =
            CheckpointTag.builder()
                .workflowId("workflowInstanceId")
                .actionClass(TestCompensableActions::class.java)
                .actionMethod("compensableAction")
                .iteration(0)
                .build()

        val context =
            ExecutionContext.builder()
                .workflow(TestUtils.getWorkflowInstance())
                .clock(mock(Clock::class.java))
                .executorService(executorService)
                .build()

        // Add a dirty checkpoint for a compensable action
        context.addDirtyCheckpoint(
            ActionCheckpoint.builder()
                .checkpointTag(checkpointTag)
                .isTransient(false)
                .executionStartTime(Instant.EPOCH)
                .executionEndTime(Instant.EPOCH)
                .result(Either.right("success"))
                .build(),
        )

        assertTrue(context.hasCompensationFlow())
    }

    @Test
    fun testHasCompensationFlow_withMixedActions() {
        val compensableCheckpointTag =
            CheckpointTag.builder()
                .workflowId("workflowInstanceId")
                .actionClass(TestCompensableActions::class.java)
                .actionMethod("compensableAction")
                .iteration(0)
                .build()

        val regularCheckpointTag =
            CheckpointTag.builder()
                .workflowId("workflowInstanceId")
                .actionClass(TestNonCompensableActions::class.java)
                .actionMethod("regularAction")
                .iteration(0)
                .build()

        val context =
            ExecutionContext.builder()
                .actionCheckpoints(
                    List.of(
                        ActionCheckpoint.builder()
                            .checkpointTag(regularCheckpointTag)
                            .isTransient(false)
                            .executionStartTime(Instant.EPOCH)
                            .executionEndTime(Instant.EPOCH)
                            .result(Either.right("success"))
                            .build(),
                        ActionCheckpoint.builder()
                            .checkpointTag(compensableCheckpointTag)
                            .isTransient(false)
                            .executionStartTime(Instant.EPOCH)
                            .executionEndTime(Instant.EPOCH)
                            .result(Either.right("success"))
                            .build(),
                    ),
                )
                .workflow(TestUtils.getWorkflowInstance())
                .clock(mock(Clock::class.java))
                .executorService(executorService)
                .build()

        assertTrue(context.hasCompensationFlow())
    }

    @Test
    fun testHasCompensationFlow_withNoCheckpoints() {
        val context =
            ExecutionContext.builder()
                .workflow(TestUtils.getWorkflowInstance())
                .clock(mock(Clock::class.java))
                .executorService(executorService)
                .build()

        assertFalse(context.hasCompensationFlow())
    }

    // Test action classes for compensation flow testing
    private class TestCompensableActions : Actions() {
        @Execute
        fun compensableAction(data: String): String = "Processed $data"

        @Compensate(forExecute = "compensableAction")
        fun compensateAction(data: String) {
            // Compensation logic
        }
    }

    private class TestNonCompensableActions : Actions() {
        @Execute
        fun regularAction(data: String): String = "Processed $data"
    }
}
