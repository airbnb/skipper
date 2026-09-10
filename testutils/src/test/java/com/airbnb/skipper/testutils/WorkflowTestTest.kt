package com.airbnb.skipper.testutils

import com.airbnb.skipper.Actions
import com.airbnb.skipper.Execute
import com.airbnb.skipper.FixedRetryStrategy
import com.airbnb.skipper.RetryStrategy
import com.airbnb.skipper.RetryableError
import com.airbnb.skipper.SignalMethod
import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.StateField
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.api.WorkflowInstanceStatusView
import java.time.Duration
import java.util.concurrent.CompletableFuture
import javax.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Exercises the public harness the way an adopter would, in Kotlin. */
class WorkflowTestTest : WorkflowTest() {
    /** A hand-rolled fake standing in for a mocking library; bound to the interface the action injects. */
    @Bind(to = Greeter::class)
    val greeter: RecordingGreeter = RecordingGreeter()

    @Bind val carrier: FlakyCarrier = FlakyCarrier()

    var configured = false

    override fun configure(config: SkipperConfig) {
        configured = true
    }

    @Test
    fun runsAWorkflowEndToEndAgainstTheInMemoryStore() {
        val workflow = workflowBuilder<GreetingWorkflow>().build()

        val greeting = workflow.greet("world").get()

        assertEquals("Hello, world!", greeting)
        assertEquals(listOf("world"), greeter.greeted, "the @Bind fake was injected into the action")
        assertTrue(configured, "configure() ran before the runtime was built")
        assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().status)
    }

    @Test
    fun waitsForASignalAndResumesWhenItArrives() {
        val workflow = workflowBuilder<ApprovalWorkflow>().build()
        helper.expectWaitSignal { workflow.approveOrDecline().get() }

        helper.expectWorkflowToWait()
        workflow<ApprovalWorkflow>().decide(true)

        assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().status)
        assertEquals("approved", workflow<ApprovalWorkflow>().approveOrDecline().get())
    }

    @Test
    fun aDeadlineIsReachedByJumpingTheClock() {
        val workflow = workflowBuilder<ApprovalWorkflow>().build()
        helper.expectWaitSignal { workflow.approveOrDecline().get() }
        helper.expectWorkflowToWait()

        // Real time has barely moved, so the one-hour deadline is nowhere near.
        Thread.sleep(300)
        assertEquals(WorkflowInstanceStatusView.WAITING, helper.currentView().status)

        clock.fastForward(Duration.ofHours(2)) // now the deadline has passed and the timer fires

        assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().status)
        assertEquals("timed out", workflow<ApprovalWorkflow>().approveOrDecline().get())
    }

    @Test
    fun shortRetryDelaysElapseOnTheirOwn() {
        carrier.failuresRemaining = 2 // the action allows three retries, 200 ms apart

        // The clock ticks with real time, so awaiting the result is enough for millisecond retries.
        assertEquals("TRK-pkg-1", workflowBuilder<ShippingWorkflow>().build().ship("pkg-1").get())

        assertEquals(3, carrier.calls)
        assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().status)
    }

    @Test
    fun longRetryDelaysAreSkippedByFastForwarding() {
        carrier.failuresRemaining = 2

        workflowBuilder<SlowShippingWorkflow>().build().ship("pkg-1") // retries are 10 minutes apart

        assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.fastForwardUntilWorkflowCompletes(Duration.ofMinutes(10)).status)
        assertEquals(3, carrier.calls)
        assertEquals("TRK-pkg-1", workflow<SlowShippingWorkflow>().ship("pkg-1").get())
    }

    @Test
    fun exhaustedRetriesEndInError() {
        carrier.failuresRemaining = Int.MAX_VALUE

        workflowBuilder<SlowShippingWorkflow>().build().ship("pkg-2")

        val view = helper.fastForwardUntilWorkflowCompletes(Duration.ofMinutes(10))
        assertEquals(WorkflowInstanceStatusView.ERROR, view.status)
        assertEquals(4, carrier.calls, "one attempt plus three retries")
    }

    @Test
    fun fastForwardStopsAtTheRequestedStatus() {
        val workflow = workflowBuilder<ApprovalWorkflow>().build()
        helper.expectWaitSignal { workflow.approveOrDecline().get() }

        // Reaches WAITING without stepping past the one-hour deadline.
        assertEquals(WorkflowInstanceStatusView.WAITING, helper.fastForwardUntilWorkflowReachesStatus(WorkflowInstanceStatusView.WAITING).status)
        workflow<ApprovalWorkflow>().decide(false)
        helper.waitForWorkflowToComplete()
        assertEquals("declined", workflow<ApprovalWorkflow>().approveOrDecline().get())
    }

    @Test
    fun fastForwardNeedsTheClock() {
        val bare = WorkflowTestHelper(runtime, workflowId)
        val error = assertThrows(IllegalStateException::class.java) { bare.fastForwardUntilWorkflowCompletes() }
        assertTrue(error.message!!.contains("MutableClock"))
    }

    @Test
    fun eachTestGetsItsOwnInstanceId() {
        assertTrue(workflowId.startsWith("wf-"))
        assertTrue(helper.currentViewOrNull() == null, "no workflow has been started yet")
    }

    interface Greeter {
        fun greet(name: String): String
    }

    class RecordingGreeter : Greeter {
        val greeted = mutableListOf<String>()

        override fun greet(name: String): String {
            greeted += name
            return "Hello, $name!"
        }
    }

    class GreetingActions : Actions() {
        @Inject lateinit var greeter: Greeter

        @Execute
        fun render(name: String): String = greeter.greet(name)
    }

    class GreetingWorkflow : Workflow() {
        private val actions = actions<GreetingActions>()

        @WorkflowMethod(returnType = String::class)
        fun greet(name: String): CompletableFuture<String> = CompletableFuture.completedFuture(actions.render(name))
    }

    /** Fails the next [failuresRemaining] calls with a transient error. */
    class FlakyCarrier {
        var failuresRemaining = 0
        var calls = 0

        fun createShipment(pkg: String): String {
            calls++
            if (failuresRemaining-- > 0) throw IllegalStateException("carrier returned 503")
            return "TRK-$pkg"
        }
    }

    class ShippingActions : Actions() {
        @Inject lateinit var carrier: FlakyCarrier

        // Referenced by name from @Execute: three retries, 200 ms apart, on the runtime's clock.
        val carrierRetries: RetryStrategy = FixedRetryStrategy(Duration.ofMillis(200), 3)

        @Execute(retryStrategy = "carrierRetries")
        fun ship(pkg: String): String =
            try {
                carrier.createShipment(pkg)
            } catch (e: IllegalStateException) {
                throw RetryableError("carrier unavailable", e)
            }
    }

    class ShippingWorkflow : Workflow() {
        private val shipping = actions<ShippingActions>()

        @WorkflowMethod(returnType = String::class)
        fun ship(pkg: String): CompletableFuture<String> = CompletableFuture.completedFuture(shipping.ship(pkg))
    }

    /** Same carrier, but retries ten minutes apart: no test should sit through that. */
    class SlowShippingActions : Actions() {
        @Inject lateinit var carrier: FlakyCarrier

        val carrierRetries: RetryStrategy = FixedRetryStrategy(Duration.ofMinutes(10), 3)

        @Execute(retryStrategy = "carrierRetries")
        fun ship(pkg: String): String =
            try {
                carrier.createShipment(pkg)
            } catch (e: IllegalStateException) {
                throw RetryableError("carrier unavailable", e)
            }
    }

    class SlowShippingWorkflow : Workflow() {
        private val shipping = actions<SlowShippingActions>()

        @WorkflowMethod(returnType = String::class)
        fun ship(pkg: String): CompletableFuture<String> = CompletableFuture.completedFuture(shipping.ship(pkg))
    }

    class ApprovalWorkflow : Workflow() {
        @StateField var approved: Boolean? = null

        @WorkflowMethod(returnType = String::class)
        fun approveOrDecline(): CompletableFuture<String> {
            val decided = waitUntil({ approved != null }, Duration.ofHours(1))
            return CompletableFuture.completedFuture(
                when {
                    !decided -> "timed out"
                    approved == true -> "approved"
                    else -> "declined"
                },
            )
        }

        @SignalMethod
        fun decide(value: Boolean) {
            approved = value
        }
    }
}
