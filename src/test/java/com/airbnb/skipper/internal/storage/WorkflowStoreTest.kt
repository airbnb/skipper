package com.airbnb.skipper.internal.storage

import com.airbnb.skipper.WorkflowInstance
import io.vavr.collection.List
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock

/** Covers the [WorkflowStore] default methods seen by implementations that don't override them. */
class WorkflowStoreTest {
    private val store = mock<WorkflowStore>(defaultAnswer = Mockito.CALLS_REAL_METHODS)

    @Test
    fun countChildWorkflows_byDefault_shouldBeUnsupported() {
        assertThrows(UnsupportedOperationException::class.java) {
            store.countChildWorkflows("parent-1", List.of(WorkflowInstance.Status.RUNNING))
        }
    }

    @Test
    fun countChildWorkflows_byDefault_shouldRejectNullArguments() {
        assertThrows(NullPointerException::class.java) {
            store.countChildWorkflows(null, List.empty())
        }
        assertThrows(NullPointerException::class.java) {
            store.countChildWorkflows("parent-1", null)
        }
    }
}
