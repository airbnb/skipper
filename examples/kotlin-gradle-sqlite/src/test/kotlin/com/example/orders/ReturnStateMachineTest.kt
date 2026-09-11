package com.example.orders

import com.airbnb.skipper.api.WorkflowInstanceStatusView
import com.airbnb.skipper.testutils.Bind
import com.airbnb.skipper.testutils.WorkflowTest
import com.airbnb.skipper.testutils.workflow
import com.airbnb.skipper.testutils.workflowBuilder
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** A state machine is a workflow, so the same harness drives it: start it, send events, assert on the state. */
class ReturnStateMachineTest : WorkflowTest() {
    @Bind(to = PaymentGateway::class) val payments = InMemoryPayments()

    private val input = ReturnInput(orderId = "o-1", paymentId = "pay-1")

    @Test
    fun `approved return is refunded once the item arrives`() = runBlocking {
        val sm = workflowBuilder<ReturnStateMachine>().build()
        val finalState = async(Dispatchers.IO) { sm.execute(input) }
        helper.expectWorkflowToWait()
        assertEquals(ReturnState.REQUESTED, sm.getState())

        sm.sendEvent(ReturnEvent.Approved("agent-7"))
        helper.waitForCondition { workflow<ReturnStateMachine>().getState() == ReturnState.AWAITING_ITEM }
        assertTrue(payments.entries().isEmpty(), "no refund before the item is back")

        sm.sendEvent(ReturnEvent.ItemReceived("unopened"))

        assertEquals(ReturnState.REFUNDED, finalState.await())
        assertEquals(listOf("REFUND pay-1"), payments.entries())
    }

    @Test
    fun `rejected return ends without a refund`() = runBlocking {
        val sm = workflowBuilder<ReturnStateMachine>().build()
        val finalState = async(Dispatchers.IO) { sm.execute(input) }
        helper.expectWorkflowToWait()

        sm.sendEvent(ReturnEvent.Rejected("outside the return window"))

        assertEquals(ReturnState.REJECTED, finalState.await())
        assertTrue(payments.entries().isEmpty())
    }

    @Test
    fun `item that never arrives expires the return`() = runBlocking {
        val sm = workflowBuilder<ReturnStateMachine>().build()
        // Jumping the clock two weeks also runs the suspended execute() call past its 30 s result-polling
        // limit, so do not await it here; read the state through the query instead.
        async(Dispatchers.IO) { runCatching { sm.execute(input) } }
        helper.expectWorkflowToWait()
        sm.sendEvent(ReturnEvent.Approved("agent-7"))
        helper.waitForCondition { workflow<ReturnStateMachine>().getState() == ReturnState.AWAITING_ITEM }

        clock.fastForward(ReturnStateMachine.RETURN_WINDOW.plusHours(1))

        assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().status)
        assertEquals(ReturnState.EXPIRED, workflow<ReturnStateMachine>().getState())
    }
}
