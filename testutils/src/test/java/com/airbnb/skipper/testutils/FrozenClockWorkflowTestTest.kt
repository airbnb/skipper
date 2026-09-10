package com.airbnb.skipper.testutils

import com.airbnb.skipper.api.WorkflowInstanceStatusView
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Overriding [createClock] freezes time at the epoch: nothing fires until the test says so. */
class FrozenClockWorkflowTestTest : WorkflowTest() {
    @Bind val carrier: WorkflowTestTest.FlakyCarrier = WorkflowTestTest.FlakyCarrier()

    override fun createClock(): MutableClock = MutableClock()

    @Test
    fun timeStandsStillUntilFastForwarded() {
        assertEquals(Instant.EPOCH, clock.instant())
        carrier.failuresRemaining = 1 // a 200 ms retry that real time cannot reach

        workflowBuilder<WorkflowTestTest.ShippingWorkflow>().build().ship("pkg")

        Thread.sleep(500)
        assertEquals(1, carrier.calls, "the retry timer has not fired on its own")
        assertEquals(Instant.EPOCH, clock.instant())

        assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.fastForwardUntilWorkflowCompletes().status)
        assertEquals(2, carrier.calls)
    }
}
