package com.airbnb.skipper.internal.storage

import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.TestUtils
import io.vavr.control.Either
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WorkflowUpdateRequestTest {
    @Test
    fun testWorkflowUpdateRequest() {
        val builder =
            WorkflowUpdateRequest.builder()
                .workflowInstance(TestUtils.getWorkflowInstance())
                .newStatus(WorkflowInstance.Status.CREATED)
        builder.build()
        assertThrows(IllegalArgumentException::class.java) {
            builder.result(Either.right(Any())).build()
        }
        assertThrows(IllegalArgumentException::class.java) {
            builder
                .newStatus(WorkflowInstance.Status.RUNNING)
                .result(Either.right(Any()))
                .build()
        }
        builder.newStatus(WorkflowInstance.Status.CREATED).result(null).build()
        builder.newStatus(WorkflowInstance.Status.RUNNING).result(null).build()
    }
}
