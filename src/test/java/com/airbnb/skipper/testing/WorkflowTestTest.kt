package com.airbnb.skipper.testing

import com.airbnb.skipper.Actions
import com.airbnb.skipper.Execute
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Exercises the public harness the way an adopter would, in Kotlin. */
class WorkflowTestTest : WorkflowTest() {
    /** A hand-rolled fake standing in for a mocking library; bound to the interface the action injects. */
    @Bind(to = Greeter::class)
    val greeter: RecordingGreeter = RecordingGreeter()

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
    fun theClockOnlyMovesWhenTheTestSaysSo() {
        val workflow = workflowBuilder<ApprovalWorkflow>().build()
        helper.expectWaitSignal { workflow.approveOrDecline().get() }
        helper.expectWorkflowToWait()

        // Nothing has happened to the clock, so the one-hour deadline is nowhere near.
        Thread.sleep(300)
        assertEquals(WorkflowInstanceStatusView.WAITING, helper.currentView().status)

        clock.fastForward(Duration.ofHours(2)) // now the deadline has passed and the timer fires

        assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().status)
        assertEquals("timed out", workflow<ApprovalWorkflow>().approveOrDecline().get())
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
