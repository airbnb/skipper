package com.example.orders

import com.airbnb.skipper.api.WorkflowInstanceStatusView
import com.airbnb.skipper.testutils.Bind
import com.airbnb.skipper.testutils.WorkflowTest
import com.airbnb.skipper.testutils.workflow
import com.airbnb.skipper.testutils.workflowBuilder
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Each test runs on its own in-memory Skipper runtime. The @Bind fields are handed to the actions' @Inject
 * fields, so scenarios are set up by configuring the fakes, never by mocking the actions themselves.
 *
 * The bindings are captured before the test body runs: mutate a fake in place, do not reassign the field.
 *
 * A suspend call on a workflow that parks keeps waiting only up to resultPollingTimeLimit (30 s) on Skipper's
 * clock, then fails with TimeoutException. Tests that jump the clock past that read the outcome through the
 * result() query instead of awaiting the call; [start] runs the call off to the side and captures either way.
 */
class OrderWorkflowTest : WorkflowTest() {
    @Bind(to = InventoryService::class) val inventory = InMemoryInventory()
    @Bind(to = PaymentGateway::class) val payments = InMemoryPayments()
    @Bind(to = Carrier::class) val carrier = FlakyCarrier()

    private val small = OrderRequest("o-1", "alice", "BOOK-1", 2, 3_900)
    private val large = OrderRequest("o-2", "bob", "LAPTOP-9", 1, 149_900)
    private val hazmat = OrderRequest("o-3", "carol", "HAZMAT-7", 1, 12_000)

    private fun CoroutineScope.start(order: OrderRequest): Deferred<Result<OrderResult>> =
        async(Dispatchers.IO) { runCatching { workflowBuilder<OrderWorkflow>().build().placeOrder(order) } }

    @Test
    fun `small order is fulfilled in one pass`() = runBlocking {
        val result = workflowBuilder<OrderWorkflow>().build().placeOrder(small)

        assertEquals("FULFILLED", result.status)
        assertEquals("trk-1", result.trackingId)
        assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().status)
        assertEquals(listOf("RESERVE res-1 2 x BOOK-1"), inventory.entries())
        assertEquals(listOf("CHARGE pay-1 alice $39"), payments.entries())
    }

    @Test
    fun `large order waits for approval`() = runBlocking {
        val deferred = start(large)
        helper.expectWorkflowToWait()
        assertEquals("AWAITING_APPROVAL", workflow<OrderWorkflow>().stage())
        assertTrue(payments.entries().isEmpty(), "nothing is charged before approval")

        workflow<OrderWorkflow>().approve(true)

        // Approval arrived within the polling limit, so the suspended caller does get the result.
        assertEquals("FULFILLED", deferred.await().getOrThrow().status)
        assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().status)
        assertEquals(1, payments.entries().size)
    }

    @Test
    fun `declined approval rejects without side effects`() = runBlocking {
        val deferred = start(large)
        helper.expectWorkflowToWait()

        workflow<OrderWorkflow>().approve(false)

        assertEquals("REJECTED", deferred.await().getOrThrow().status)
        assertTrue(inventory.entries().isEmpty())
        assertTrue(payments.entries().isEmpty())
    }

    @Test
    fun `approval window expires`() = runBlocking {
        start(large)
        helper.expectWorkflowToWait()

        // The test clock ticks with real time; jump it past the one-day window instead of waiting.
        clock.fastForward(Duration.ofDays(1).plusMinutes(1))

        assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().status)
        assertEquals("REJECTED", workflow<OrderWorkflow>().result()?.status)
    }

    @Test
    fun `carrier outage is retried`() = runBlocking {
        carrier.failNext(2) // two 503s, then success; the action's strategy allows three retries a minute apart
        start(small)

        // Each retry is a timer on the test clock; step the clock until the instance is terminal.
        assertEquals(
            WorkflowInstanceStatusView.COMPLETED,
            helper.fastForwardUntilWorkflowCompletes(Duration.ofMinutes(1)).status,
        )
        assertEquals(3, carrier.calls)
        assertEquals("FULFILLED", workflow<OrderWorkflow>().result()?.status)
    }

    @Test
    fun `refused shipment compensates charge and reservation`() = runBlocking {
        val deferred = start(hazmat)

        // The instance passes through ERROR on its way here; both are terminal, so wait for the one that matters.
        helper.fastForwardUntilWorkflowReachesStatus(WorkflowInstanceStatusView.COMPENSATION_COMPLETED, Duration.ofSeconds(30))

        assertTrue(deferred.await().isFailure, "the caller sees the carrier's error")
        assertNull(workflow<OrderWorkflow>().result())
        assertEquals(listOf("RESERVE res-1 1 x HAZMAT-7", "RELEASE res-1"), inventory.entries())
        assertEquals(listOf("CHARGE pay-1 carol $120", "REFUND pay-1"), payments.entries())
    }
}
