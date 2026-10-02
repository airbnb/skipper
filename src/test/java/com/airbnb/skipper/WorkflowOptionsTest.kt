package com.airbnb.skipper

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkflowOptionsTest {
    @Test
    fun testMergeWithAllNullBase() {
        val base = WorkflowOptions()
        val override = WorkflowOptions(
            executionTimeout = Duration.ofMinutes(10),
            refreshRequestContextForLongRunningWorkflows = true,
            allowQueryOnNonExistentWorkflow = true,
            createExistingWorkflowIsNoop = true
        )

        val result = base.mergeWith(override)

        assertEquals(Duration.ofMinutes(10), result.executionTimeout)
        assertTrue(result.refreshRequestContextForLongRunningWorkflows)
        assertTrue(result.allowQueryOnNonExistentWorkflow)
        assertTrue(result.createExistingWorkflowIsNoop)
    }

    @Test
    fun testMergeWithNullOverride() {
        val base = WorkflowOptions(
            executionTimeout = Duration.ofMinutes(5),
            refreshRequestContextForLongRunningWorkflows = true,
            allowQueryOnNonExistentWorkflow = true
        )
        val override = WorkflowOptions()

        val result = base.mergeWith(override)

        assertEquals(Duration.ofMinutes(5), result.executionTimeout)
        assertFalse(result.refreshRequestContextForLongRunningWorkflows)
        assertFalse(result.allowQueryOnNonExistentWorkflow)
        assertFalse(result.createExistingWorkflowIsNoop)
    }

    @Test
    fun testMergeWithOverrideExecutionTimeout() {
        val base = WorkflowOptions(
            executionTimeout = Duration.ofMinutes(5),
            refreshRequestContextForLongRunningWorkflows = false,
            allowQueryOnNonExistentWorkflow = false
        )
        val override = WorkflowOptions(
            executionTimeout = Duration.ofMinutes(15),
            refreshRequestContextForLongRunningWorkflows = false,
            allowQueryOnNonExistentWorkflow = false
        )

        val result = base.mergeWith(override)

        assertEquals(Duration.ofMinutes(15), result.executionTimeout)
        assertFalse(result.refreshRequestContextForLongRunningWorkflows)
        assertFalse(result.allowQueryOnNonExistentWorkflow)
        assertFalse(result.createExistingWorkflowIsNoop)
    }

    @Test
    fun testMergeWithOverrideNullExecutionTimeout() {
        val base = WorkflowOptions(
            executionTimeout = Duration.ofMinutes(5),
            refreshRequestContextForLongRunningWorkflows = false,
            allowQueryOnNonExistentWorkflow = false
        )
        val override = WorkflowOptions(
            executionTimeout = null,
            refreshRequestContextForLongRunningWorkflows = true,
            allowQueryOnNonExistentWorkflow = true
        )

        val result = base.mergeWith(override)

        assertEquals(Duration.ofMinutes(5), result.executionTimeout)
        assertTrue(result.refreshRequestContextForLongRunningWorkflows)
        assertTrue(result.allowQueryOnNonExistentWorkflow)
    }

    @Test
    fun testMergeWithBooleanFlags() {
        val base = WorkflowOptions(
            executionTimeout = Duration.ofMinutes(5),
            refreshRequestContextForLongRunningWorkflows = true,
            allowQueryOnNonExistentWorkflow = false
        )
        val override = WorkflowOptions(
            executionTimeout = Duration.ofMinutes(10),
            refreshRequestContextForLongRunningWorkflows = false,
            allowQueryOnNonExistentWorkflow = true
        )

        val result = base.mergeWith(override)

        assertEquals(Duration.ofMinutes(10), result.executionTimeout)
        assertFalse(result.refreshRequestContextForLongRunningWorkflows)
        assertTrue(result.allowQueryOnNonExistentWorkflow)
    }

    @Test
    fun testMergeWithSameValues() {
        val base = WorkflowOptions(
            executionTimeout = Duration.ofMinutes(5),
            refreshRequestContextForLongRunningWorkflows = true,
            allowQueryOnNonExistentWorkflow = true
        )
        val override = WorkflowOptions(
            executionTimeout = Duration.ofMinutes(5),
            refreshRequestContextForLongRunningWorkflows = true,
            allowQueryOnNonExistentWorkflow = true
        )

        val result = base.mergeWith(override)

        assertEquals(Duration.ofMinutes(5), result.executionTimeout)
        assertTrue(result.refreshRequestContextForLongRunningWorkflows)
        assertTrue(result.allowQueryOnNonExistentWorkflow)
    }

    @Test
    fun testMergeWithDefaultOptions() {
        val base = WorkflowOptions()
        val override = WorkflowOptions()

        val result = base.mergeWith(override)

        assertNull(result.executionTimeout)
        assertFalse(result.refreshRequestContextForLongRunningWorkflows)
        assertFalse(result.allowQueryOnNonExistentWorkflow)
    }

    @Test
    fun testMergeWithPartialOverride() {
        val base = WorkflowOptions(
            executionTimeout = Duration.ofMinutes(5),
            refreshRequestContextForLongRunningWorkflows = true,
            allowQueryOnNonExistentWorkflow = false
        )
        val override = WorkflowOptions(
            executionTimeout = null,
            refreshRequestContextForLongRunningWorkflows = false,
            allowQueryOnNonExistentWorkflow = true
        )

        val result = base.mergeWith(override)

        assertEquals(Duration.ofMinutes(5), result.executionTimeout)
        assertFalse(result.refreshRequestContextForLongRunningWorkflows)
        assertTrue(result.allowQueryOnNonExistentWorkflow)
    }

    @Test
    fun testLegacyConstructor() {
        val options = WorkflowOptions(Duration.ofMinutes(10), true)

        assertEquals(Duration.ofMinutes(10), options.executionTimeout)
        assertTrue(options.refreshRequestContextForLongRunningWorkflows)
        assertFalse(options.allowQueryOnNonExistentWorkflow)
        assertFalse(options.createExistingWorkflowIsNoop)
    }

    @Test
    fun testExistingThreeArgumentConstructor() {
        val options = WorkflowOptions(Duration.ofMinutes(10), true, true)

        assertEquals(Duration.ofMinutes(10), options.executionTimeout)
        assertTrue(options.refreshRequestContextForLongRunningWorkflows)
        assertTrue(options.allowQueryOnNonExistentWorkflow)
        assertFalse(options.createExistingWorkflowIsNoop)
    }

    @Test
    fun testMergeWithLegacyConstructorOptions() {
        val base = WorkflowOptions(Duration.ofMinutes(5), false)
        val override = WorkflowOptions(
            executionTimeout = Duration.ofMinutes(15),
            refreshRequestContextForLongRunningWorkflows = true,
            allowQueryOnNonExistentWorkflow = true
        )

        val result = base.mergeWith(override)

        assertEquals(Duration.ofMinutes(15), result.executionTimeout)
        assertTrue(result.refreshRequestContextForLongRunningWorkflows)
        assertTrue(result.allowQueryOnNonExistentWorkflow)
    }

    @Test
    fun testMergeWithNullOverrideParameter() {
        val base = WorkflowOptions(
            executionTimeout = Duration.ofMinutes(5),
            refreshRequestContextForLongRunningWorkflows = true,
            allowQueryOnNonExistentWorkflow = true
        )

        val result = base.mergeWith(null)

        assertEquals(Duration.ofMinutes(5), result.executionTimeout)
        assertTrue(result.refreshRequestContextForLongRunningWorkflows)
        assertTrue(result.allowQueryOnNonExistentWorkflow)
    }
}
