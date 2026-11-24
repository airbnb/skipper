package com.airbnb.skipper.integtest

import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.api.WorkflowInstanceStatusView
import com.airbnb.skipper.internal.SkipperSchedulerManager
import com.airbnb.skipper.testutils.TestRuntime
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** Each finished run of a key is followed by a new, numbered run, and every run stays in the store. */
@Execution(ExecutionMode.SAME_THREAD)
class WorkflowRunsByKeyTest {
    open class GreetWorkflow : Workflow() {
        @WorkflowMethod
        open fun greet(name: String): String = "hello $name"
    }

    private lateinit var schedulerManager: SkipperSchedulerManager

    @AfterEach
    fun tearDown() {
        if (::schedulerManager.isInitialized) schedulerManager.forceStop()
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun aFinishedRunIsFollowedByTheNextNumberedRun() {
        val runtime = TestRuntime().runtime
        schedulerManager = runtime.skipperSchedulerManager.get()
        schedulerManager.start()
        val workflowFactory = runtime.workflowFactory.get()
        val workflowsService = runtime.workflowsService.get()
        val key = "greet-${UUID.randomUUID()}"

        val firstRunId = workflowsService.nextRunId(key).get()
        assertEquals(key, firstRunId)
        workflowFactory(GreetWorkflow::class.java, firstRunId).greet("first")

        val secondRunId = workflowsService.nextRunId(key).get()
        assertEquals("${key}_r2", secondRunId)
        assertEquals("hello second", workflowFactory(GreetWorkflow::class.java, secondRunId).greet("second"))

        val latest = workflowsService.findLatestRun(key).get()
        assertEquals(secondRunId, latest.id)
        assertEquals(WorkflowInstanceStatusView.COMPLETED, latest.status)
        assertEquals(Optional.of("${key}_r3"), workflowsService.nextRunId(key))
        assertEquals(
            WorkflowInstanceStatusView.COMPLETED,
            workflowFactory(GreetWorkflow::class.java, firstRunId).getWorkflowInstanceView().status
        )
    }
}
