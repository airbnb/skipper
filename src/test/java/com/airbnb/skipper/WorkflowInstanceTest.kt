package com.airbnb.skipper

import com.airbnb.skipper.testutils.TestRequestContext
import com.airbnb.skipper.util.ExtraRequestData
import io.vavr.collection.HashMap
import java.time.Instant
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkflowInstanceTest {
    @Test
    fun testBuilder() {
        val workflowId = "workflowId"
        val workflowClass = Workflow::class.java
        val workflowMethod = "workflowMethod"
        val input = "input"
        val requestContext: Any = TestRequestContext.builder().build()
        val extraRequestData = ExtraRequestData()
        val state = io.vavr.collection.HashMap.empty<String, Any?>()
        val result = CompletableFuture<Any?>()
        val callbackHandler = WorkflowCallbackHandler::class.java
        val version = 1
        val status = WorkflowInstance.Status.RUNNING

        val workflowInstance =
            WorkflowInstance.builder()
                .workflowId(workflowId)
                .workflowClass(workflowClass)
                .workflowMethod(workflowMethod)
                .input(input)
                .requestContext(requestContext)
                .extraRequestData(extraRequestData)
                .state(state)
                .result(result)
                .callbackHandler(callbackHandler)
                .version(version)
                .status(status)
                .build()

        assertEquals(workflowId, workflowInstance.workflowId)
        assertEquals(workflowClass, workflowInstance.workflowClass)
        assertEquals(workflowMethod, workflowInstance.workflowMethod)
        assertEquals(input, workflowInstance.input)
        assertEquals(requestContext, workflowInstance.requestContext)
        assertEquals(extraRequestData, workflowInstance.extraRequestData)
        assertEquals(state, workflowInstance.state)
        assertEquals(result, workflowInstance.result)
        assertEquals(callbackHandler, workflowInstance.callbackHandler)
        assertEquals(version, workflowInstance.version)
        assertEquals(status, workflowInstance.status)
    }

    @Test
    fun testToBuilder() {
        val workflowId = "workflowId"
        val workflowClass = Workflow::class.java
        val workflowMethod = "workflowMethod"
        val input = "input"
        val requestContext: Any = TestRequestContext.builder().build()
        val extraRequestData = ExtraRequestData()
        val state = HashMap.empty<String, Any?>()
        val result = CompletableFuture<Any?>()
        val callbackHandler = WorkflowCallbackHandler::class.java
        val version = 1
        val status = WorkflowInstance.Status.CREATED

        val workflowInstance =
            WorkflowInstance.builder()
                .workflowId(workflowId)
                .workflowClass(workflowClass)
                .workflowMethod(workflowMethod)
                .input(input)
                .requestContext(requestContext)
                .extraRequestData(extraRequestData)
                .state(state)
                .result(result)
                .callbackHandler(callbackHandler)
                .version(version)
                .status(status)
                .build()

        val newWorkflowInstance =
            workflowInstance.toBuilder()
                .build()

        assertEquals(workflowInstance, newWorkflowInstance)
    }

    private val mockRequestContext: Any = TestRequestContext.builder().build()
    private val mockExtraRequestData = ExtraRequestData()
    private val mockWorkflowClass = Workflow::class.java

    private fun createBaseWorkflowInstance(
        workflowId: String = "test-id",
        state: io.vavr.collection.Map<String, Any?> = HashMap.empty(),
        result: CompletableFuture<Any?> = CompletableFuture.completedFuture("success"),
        status: WorkflowInstance.Status = WorkflowInstance.Status.RUNNING
    ) = WorkflowInstance(
        workflowId = workflowId,
        workflowClass = mockWorkflowClass,
        workflowMethod = "testMethod",
        input = "testInput",
        requestContext = mockRequestContext,
        extraRequestData = mockExtraRequestData,
        state = state,
        result = result,
        callbackHandler = null,
        version = 1,
        status = status
    )

    @Test
    fun `same instance returns true`() {
        val instance = createBaseWorkflowInstance()
        assertTrue(instance.executionStatusIsTheSame(instance))
    }

    @Test
    fun `different workflowId returns false`() {
        val instance1 = createBaseWorkflowInstance(workflowId = "id1")
        val instance2 = createBaseWorkflowInstance(workflowId = "id2")
        assertFalse(instance1.executionStatusIsTheSame(instance2))
    }

    @Test
    fun `different state returns false`() {
        val instance1 = createBaseWorkflowInstance(state = HashMap.of("key", "value1"))
        val instance2 = createBaseWorkflowInstance(state = HashMap.of("key", "value2"))
        assertFalse(instance1.executionStatusIsTheSame(instance2))
    }

    @Test
    fun `different result success status returns false`() {
        val successResult: CompletableFuture<Any?> = CompletableFuture.completedFuture("success")
        val failureResult = CompletableFuture<Any?>()
        failureResult.completeExceptionally(RetryableError("error"))

        val instance1 = createBaseWorkflowInstance(result = successResult)
        val instance2 = createBaseWorkflowInstance(result = failureResult)
        assertFalse(instance1.executionStatusIsTheSame(instance2))
    }

    @Test
    fun `different result completion status returns false`() {
        val completedResult: CompletableFuture<Any?> = CompletableFuture.completedFuture("success")
        val pendingResult = CompletableFuture<Any?>()

        val instance1 = createBaseWorkflowInstance(result = completedResult)
        val instance2 = createBaseWorkflowInstance(result = pendingResult)
        assertFalse(instance1.executionStatusIsTheSame(instance2))
    }

    @Test
    fun `different status returns false`() {
        val instance1 = createBaseWorkflowInstance(status = WorkflowInstance.Status.RUNNING)
        val instance2 = createBaseWorkflowInstance(status = WorkflowInstance.Status.COMPLETED)
        assertFalse(instance1.executionStatusIsTheSame(instance2))
    }

    @Test
    fun `instances with same execution status return true despite different versions`() {
        val instance1 = createBaseWorkflowInstance()
        val instance2 = instance1.toBuilder().version(2).build()
        assertTrue(instance1.executionStatusIsTheSame(instance2))
    }

    @Test
    fun `instances with same execution status return true despite different updatedAt`() {
        val instance1 = createBaseWorkflowInstance()
        val instance2 = instance1.toBuilder().updatedAt(Instant.now()).build()
        assertTrue(instance1.executionStatusIsTheSame(instance2))
    }
}
