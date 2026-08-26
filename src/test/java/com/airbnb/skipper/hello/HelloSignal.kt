package com.airbnb.skipper.hello

import com.airbnb.skipper.SignalMethod
import com.airbnb.skipper.StateField
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowFactory
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.internal.api.WaitSignal
import com.airbnb.skipper.invoke
import org.mockito.Mockito.mock

/**
 * Represents a workflow that waits for a signal before proceeding with its execution. This class
 * demonstrates how to use signals within a workflow context to control the flow of execution based
 * on external inputs.
 *
 *
 * The workflow waits for a boolean signal to proceed with its operations, which modifies its
 * behavior based on the state of `shouldProceed`.
 */
internal class HelloSignal : Workflow() {
    /** A state field that determines whether the workflow should continue its execution.  */
    @StateField
    private var shouldProceed = false

    /**
     * Updates the `shouldProceed` state of the workflow. This signal method allows external
     * callers to modify the flow of the workflow execution.
     *
     * @param shouldProceed the new state to set for determining whether the workflow can proceed
     */
    @SignalMethod
    fun updateShouldProceed(shouldProceed: Boolean) {
        this.shouldProceed = shouldProceed
    }

    /**
     * The main method of the workflow that waits until `shouldProceed` is true. It demonstrates
     * how a workflow can suspend its execution waiting for an external signal.
     *
     * @param input the input string to be used once the workflow resumes.
     * @return a string indicating the completion of the workflow with the input included.
     */
    @WorkflowMethod
    fun waitingWorkflow(input: String): String {
        waitUntil { shouldProceed }
        return "Waiting workflow executed with $input"
    }
}

/**
 * Main class to demonstrate the execution flow of the [HelloSignal] workflow. This class
 * setups a workflow instance, triggers its execution, and manipulates the workflow state through
 * external signals.
 */
@Suppress("UNUSED_VARIABLE")
private suspend fun useHelloSignal() {
    val ctx = null
    var workflow = mock<WorkflowFactory>()<HelloSignal>("test", ctx)

    // 1. Attempt to execute the workflow method which will wait due to shouldProceed being false.
    try {
        workflow.waitingWorkflow("test")
    } catch (e: WaitSignal) {
        // expected exception when workflow is waiting for a signal
    }

    // 2. Send a signal to allow the workflow to proceed.
    workflow.updateShouldProceed(true)

    // 3. Retrieve the result after the signal has been processed and the workflow resumes.
    var result = workflow.getResultFor(workflow::waitingWorkflow).join()
    // result = "Waiting workflow executed with test"
}
