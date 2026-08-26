package com.airbnb.skipper.internal

import com.airbnb.skipper.Actions
import com.airbnb.skipper.Execute
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.named
import io.vavr.collection.List
import io.vavr.control.Either
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class NamedCheckpointCoreTest {
    private val executorService = Executors.newSingleThreadExecutor()

    // -- Actions.named() API tests --

    @Test
    fun `named() sets pending checkpoint name`() {
        val actions = TestActions()
        actions.named("step-a")
        assertEquals("step-a", actions.pendingCheckpointName)
    }

    @Test
    fun `named() returns same instance with correct type`() {
        val actions = TestActions()
        val result = actions.named("step-a")
        assertTrue(result === actions)
    }

    @Test
    fun `named() Java overload with class token`() {
        val actions: Actions = TestActions()
        val typed: TestActions = actions.named(TestActions::class.java, "step-b")
        assertEquals("step-b", typed.pendingCheckpointName)
        assertTrue(typed === actions)
    }

    @Test
    fun `named() can be overwritten before consumption`() {
        val actions = TestActions()
        actions.named("first")
        actions.named("second")
        assertEquals("second", actions.pendingCheckpointName)
    }

    @Test
    fun `pendingCheckpointName defaults to null`() {
        val actions = TestActions()
        assertNull(actions.pendingCheckpointName)
    }

    // -- ExecutionContext name-based checkpoint lookup tests --

    @Test
    fun `getActionCheckpoint matches by name when both tags have checkpointName`() {
        val storedTag = CheckpointTag.builder()
            .workflowId("wf-1")
            .actionClass(TestActions::class.java)
            .actionMethod("doSomething")
            .iteration(0)
            .checkpointName("prepare-order")
            .build()

        val lookupTag = CheckpointTag.builder()
            .workflowId("wf-1")
            .actionClass(TestActions::class.java)
            .actionMethod("doSomething")
            .iteration(99)
            .checkpointName("prepare-order")
            .build()

        val context = ExecutionContext.builder()
            .actionCheckpoints(
                List.of(
                    ActionCheckpoint.builder()
                        .checkpointTag(storedTag)
                        .isTransient(false)
                        .executionStartTime(Instant.EPOCH)
                        .result(Either.right("result"))
                        .build()
                )
            )
            .workflow(TestUtils.getWorkflowInstance())
            .clock(mock(Clock::class.java))
            .executorService(executorService)
            .build()

        val found = context.getActionCheckpoint(lookupTag)
        assertTrue(found.isDefined)
        assertEquals("result", found.get().result.get())
    }

    @Test
    fun `getActionCheckpoint does not match named vs positional`() {
        val storedTag = CheckpointTag.builder()
            .workflowId("wf-1")
            .actionClass(TestActions::class.java)
            .actionMethod("doSomething")
            .iteration(0)
            .build()

        val lookupTag = CheckpointTag.builder()
            .workflowId("wf-1")
            .actionClass(TestActions::class.java)
            .actionMethod("doSomething")
            .iteration(0)
            .checkpointName("my-name")
            .build()

        val context = ExecutionContext.builder()
            .actionCheckpoints(
                List.of(
                    ActionCheckpoint.builder()
                        .checkpointTag(storedTag)
                        .isTransient(false)
                        .executionStartTime(Instant.EPOCH)
                        .result(Either.right("result"))
                        .build()
                )
            )
            .workflow(TestUtils.getWorkflowInstance())
            .clock(mock(Clock::class.java))
            .executorService(executorService)
            .build()

        assertTrue(context.getActionCheckpoint(lookupTag).isEmpty)
    }

    @Test
    fun `consumedNamedCheckpoints tracking`() {
        val context = ExecutionContext.builder()
            .workflow(TestUtils.getWorkflowInstance())
            .clock(mock(Clock::class.java))
            .executorService(executorService)
            .build()

        assertFalse(context.isNamedCheckpointConsumed("step-a"))
        context.addConsumedNamedCheckpoint("step-a")
        assertTrue(context.isNamedCheckpointConsumed("step-a"))
        assertFalse(context.isNamedCheckpointConsumed("step-b"))
    }

    @Test
    fun `getActionRetryCount uses matches() for named tags`() {
        val storedTag = CheckpointTag.builder()
            .workflowId("wf-1")
            .actionClass(TestActions::class.java)
            .actionMethod("doSomething")
            .iteration(0)
            .checkpointName("retryable-step")
            .build()

        val lookupTag = CheckpointTag.builder()
            .workflowId("wf-1")
            .actionClass(TestActions::class.java)
            .actionMethod("doSomething")
            .iteration(0)
            .checkpointName("retryable-step")
            .build()

        val context = ExecutionContext.builder()
            .actionCheckpoints(
                List.of(
                    ActionCheckpoint.builder()
                        .checkpointTag(storedTag)
                        .isTransient(true)
                        .executionStartTime(Instant.EPOCH)
                        .result(Either.left(RuntimeException("retry")))
                        .build(),
                    ActionCheckpoint.builder()
                        .checkpointTag(storedTag)
                        .isTransient(true)
                        .executionStartTime(Instant.EPOCH)
                        .result(Either.left(RuntimeException("retry")))
                        .build()
                )
            )
            .workflow(TestUtils.getWorkflowInstance())
            .clock(mock(Clock::class.java))
            .executorService(executorService)
            .build()

        assertEquals(2, context.getActionRetryCount(lookupTag))
    }

    // -- ExecuteActionRequest.checkpointName tests --

    @Test
    fun `ExecuteActionRequest checkpointName defaults to null`() {
        val request = ActionExecutor.ExecuteActionRequest.builder()
            .actionObject(TestActions())
            .proxyMethod(TestActions::class.java.getDeclaredMethod("doSomething"))
            .originalMethod(TestActions::class.java.getDeclaredMethod("doSomething"))
            .executionContext(
                ExecutionContext.builder()
                    .workflow(TestUtils.getWorkflowInstance())
                    .clock(mock(Clock::class.java))
                    .executorService(executorService)
                    .build()
            )
            .retryStrategy(mock(com.airbnb.skipper.RetryStrategy::class.java))
            .build()

        assertNull(request.checkpointName)
    }

    @Test
    fun `ExecuteActionRequest can carry checkpointName`() {
        val request = ActionExecutor.ExecuteActionRequest.builder()
            .actionObject(TestActions())
            .proxyMethod(TestActions::class.java.getDeclaredMethod("doSomething"))
            .originalMethod(TestActions::class.java.getDeclaredMethod("doSomething"))
            .executionContext(
                ExecutionContext.builder()
                    .workflow(TestUtils.getWorkflowInstance())
                    .clock(mock(Clock::class.java))
                    .executorService(executorService)
                    .build()
            )
            .retryStrategy(mock(com.airbnb.skipper.RetryStrategy::class.java))
            .checkpointName("my-step")
            .build()

        assertEquals("my-step", request.checkpointName)
    }

    @Test
    fun `fromExecuteActionRequest sets iteration to 0 when checkpointName is present`() {
        val context = ExecutionContext.builder()
            .workflow(TestUtils.getWorkflowInstance())
            .clock(mock(Clock::class.java))
            .executorService(executorService)
            .build()

        context.incrementActionIteration(TestActions::class.java, "doSomething")
        context.incrementActionIteration(TestActions::class.java, "doSomething")
        assertEquals(2, context.getActionIteration(TestActions::class.java, "doSomething"))

        val request = ActionExecutor.ExecuteActionRequest.builder()
            .actionObject(TestActions())
            .proxyMethod(TestActions::class.java.getDeclaredMethod("doSomething"))
            .originalMethod(TestActions::class.java.getDeclaredMethod("doSomething"))
            .executionContext(context)
            .retryStrategy(mock(com.airbnb.skipper.RetryStrategy::class.java))
            .checkpointName("my-named-step")
            .build()

        val tag = CheckpointTag.fromExecuteActionRequest(request)
        assertEquals("my-named-step", tag.checkpointName)
        assertEquals(0, tag.iteration)
    }

    @Test
    fun `fromExecuteActionRequest uses iteration when checkpointName is null`() {
        val context = ExecutionContext.builder()
            .workflow(TestUtils.getWorkflowInstance())
            .clock(mock(Clock::class.java))
            .executorService(executorService)
            .build()

        context.incrementActionIteration(TestActions::class.java, "doSomething")
        context.incrementActionIteration(TestActions::class.java, "doSomething")

        val request = ActionExecutor.ExecuteActionRequest.builder()
            .actionObject(TestActions())
            .proxyMethod(TestActions::class.java.getDeclaredMethod("doSomething"))
            .originalMethod(TestActions::class.java.getDeclaredMethod("doSomething"))
            .executionContext(context)
            .retryStrategy(mock(com.airbnb.skipper.RetryStrategy::class.java))
            .build()

        val tag = CheckpointTag.fromExecuteActionRequest(request)
        assertNull(tag.checkpointName)
        assertEquals(2, tag.iteration)
    }

    open class TestActions : Actions() {
        @Execute
        open fun doSomething(): String = "done"
    }
}
