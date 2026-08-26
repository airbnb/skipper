package com.airbnb.skipper.api

import io.vavr.collection.HashMap
import io.vavr.collection.List
import io.vavr.collection.Map
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WorkflowInstanceViewTest {
    @Test
    fun testGetInputWithCorrectType() {
        val workflowInstanceView = WorkflowInstanceView(
            id = "exampleId",
            workflowClass = "exampleClass",
            workflowMethod = "exampleMethod",
            status = WorkflowInstanceStatusView.CREATED,
            createdAt = Instant.now(),
            actionCheckpointsSupplier = { List.empty() },
            workflowInput = "exampleInput",
            state = HashMap.empty()
        )

        val input: String? = workflowInstanceView.getInput<String>()
        assertEquals("exampleInput", input)
    }

    @Test
    fun testGetInputWithIncorrectType() {
        val workflowInstanceView = WorkflowInstanceView(
            id = "exampleId",
            workflowClass = "exampleClass",
            workflowMethod = "exampleMethod",
            status = WorkflowInstanceStatusView.CREATED,
            createdAt = Instant.now(),
            actionCheckpointsSupplier = { List.empty() },
            workflowInput = "exampleInput",
            state = HashMap.empty()
        )

        assertThrows(IllegalArgumentException::class.java) {
            workflowInstanceView.getInput<Int>()
        }
    }

    @Test
    fun testGetStateParamWithCorrectType() {
        val state: Map<String, Any?> = HashMap.of("key1", "value1")
        val workflowInstanceView = WorkflowInstanceView(
            id = "exampleId",
            workflowClass = "exampleClass",
            workflowMethod = "exampleMethod",
            status = WorkflowInstanceStatusView.CREATED,
            createdAt = Instant.now(),
            actionCheckpointsSupplier = { List.empty() },
            workflowInput = null,
            state = state
        )

        val stateParam: String? = workflowInstanceView.getStateParam<String>("key1")
        assertEquals("value1", stateParam)
    }

    @Test
    fun testGetStateParamWithIncorrectType() {
        val state: Map<String, Any?> = HashMap.of("key1", "value1")
        val workflowInstanceView = WorkflowInstanceView(
            id = "exampleId",
            workflowClass = "exampleClass",
            workflowMethod = "exampleMethod",
            status = WorkflowInstanceStatusView.CREATED,
            createdAt = Instant.now(),
            actionCheckpointsSupplier = { List.empty() },
            workflowInput = null,
            state = state
        )

        assertThrows(IllegalArgumentException::class.java) {
            workflowInstanceView.getStateParam<Int>("key1")
        }
    }

    @Test
    fun testGetStateParamWithMissingKey() {
        val state: Map<String, Any?> = HashMap.of("key1", "value1")
        val workflowInstanceView = WorkflowInstanceView(
            id = "exampleId",
            workflowClass = "exampleClass",
            workflowMethod = "exampleMethod",
            status = WorkflowInstanceStatusView.CREATED,
            createdAt = Instant.now(),
            actionCheckpointsSupplier = { List.empty() },
            workflowInput = null,
            state = state
        )

        assertThrows(IllegalArgumentException::class.java) {
            workflowInstanceView.getStateParam<String>("missingKey")
        }
    }

    @Test
    fun testGetInputWithNullValue() {
        val workflowInstanceView = WorkflowInstanceView(
            id = "exampleId",
            workflowClass = "exampleClass",
            workflowMethod = "exampleMethod",
            status = WorkflowInstanceStatusView.CREATED,
            createdAt = Instant.now(),
            actionCheckpointsSupplier = { List.empty() },
            workflowInput = null,
            state = HashMap.empty()
        )

        val input: String? = workflowInstanceView.getInput<String>()
        assertEquals(null, input)
    }

    @Test
    fun testGetStateParamWithNullValue() {
        val state: Map<String, Any?> = HashMap.of("key1", null)
        val workflowInstanceView = WorkflowInstanceView(
            id = "exampleId",
            workflowClass = "exampleClass",
            workflowMethod = "exampleMethod",
            status = WorkflowInstanceStatusView.CREATED,
            createdAt = Instant.now(),
            actionCheckpointsSupplier = { List.empty() },
            workflowInput = null,
            state = state
        )

        val stateParam: String? = workflowInstanceView.getStateParam<String>("key1")
        assertEquals(null, stateParam)
    }
}
