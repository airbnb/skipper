package com.airbnb.skipper.testutils

import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.api.WaitSignal
import com.airbnb.skipper.internal.scheduler.Scheduler
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import javax.inject.Inject

/**
 * Factory to create TestHelper instances.
 * Use this class in your test setup to associate your workflowId to the test helper.
 *
 * # Example
 * ```java
 * @Inject TestHelperFactory testHelperFactory;
 * String workflowId;
 * TestHelper helper;
 *
 * @BeforeEach
 * public void setup() {
 *   workflowId = UUID.randomUUID().toString();
 *   helper = testHelperFactory.create(workflowId);
 * }
 * ```
 */
class TestHelperFactory
    @Inject
    constructor(private val skipperEngine: SkipperEngine, private val scheduler: Scheduler) {
        fun create(workflowId: String): TestHelper {
            return TestHelper(skipperEngine, scheduler, workflowId)
        }
    }

/**
 * Helper to make integration testing of workflows easier.
 */
class TestHelper(private val skipperEngine: SkipperEngine, private val scheduler: Scheduler, private val workflowId: String) {
    private val defaultMaxAttempts = 200
    private val defaultSleepBetweenAttemptsInMs = 100L

    /**
     * Wait for a workflow to reach a specific status.
     *
     * @param status the status to wait for
     * @param workflowId the workflow ID
     * @param maxAttempts the maximum number of attempts to wait for the workflow to reach the status
     * @param sleepTimeMs the time to wait between attempts in millis
     * @return the workflow instance once it reaches the status
     */
    fun waitForWorkflowToReachStatus(
        status: Set<WorkflowInstance.Status>,
        maxAttempts: Int = 50,
        sleepTimeMs: Long = 100
    ): WorkflowInstance {
        // Wait for the workflow to be registered. This handles async workflow starts (e.g. when
        // the workflow is launched via async(Dispatchers.Default) { } and the test thread reaches
        // this point before skipperEngine.startWorkflow() has been called on the background thread.
        var storedWorkflow = skipperEngine.getWorkflow(workflowId)
        var attempts = 0
        while (!storedWorkflow.isDefined) {
            if (attempts > maxAttempts) {
                throw AssertionError("Workflow $workflowId was not registered after $maxAttempts attempts")
            }
            waitFor(sleepTimeMs)
            storedWorkflow = skipperEngine.getWorkflow(workflowId)
            attempts++
        }
        attempts = 0
        while (!status.contains(storedWorkflow.get().status)) {
            if (attempts > maxAttempts) {
                val workflowResult = if (storedWorkflow.get().status.isTerminal()) "result=${storedWorkflow.get().flattenResult()}" else ""
                throw AssertionError(
                    "Workflow did not reach status $status after $maxAttempts attempts. $workflowResult workflowInstance=$storedWorkflow"
                )
            }
            waitFor(sleepTimeMs)
            storedWorkflow = skipperEngine.getWorkflow(workflowId)
            attempts++
        }
        // The processing might still be ongoing, so in order to avoid race conditions between the test handle and the workflow execution flow,
        // let's wait until the execution flow thread completes processing and dequeues the execution task, this will guarantee that the test handle
        // won't race with the execution flow thread.
        waitForExecutionFlowToFinish(maxAttempts, sleepTimeMs)
        return storedWorkflow.get()
    }

    /**
     * Wait for a condition to be met with default max attempts and sleep time.
     */
    fun waitForCondition(condition: () -> Boolean) {
        waitForCondition(condition, defaultMaxAttempts, defaultSleepBetweenAttemptsInMs)
    }

    /**
     * Wait for a condition to be met.
     * This is a more generalized version of the waitForWorkflowToReachStatus method.
     */
    fun waitForCondition(
        condition: () -> Boolean,
        maxAttempts: Int = 50,
        sleepTimeMs: Long = 100
    ) {
        var attempts = 0
        while (!condition()) {
            if (attempts > maxAttempts) {
                throw AssertionError("Condition was not met after $maxAttempts attempts")
            }
            waitFor(sleepTimeMs)
            attempts++
        }
    }

    /**
     * Wait for the execution flow to finish processing the workflow.
     * This is useful to avoid the test handle to race with the execution flow thread.
     */
    fun waitForExecutionFlowToFinish(
        maxAttempts: Int,
        sleepTimeMs: Long
    ) {
        var task = scheduler.getTask<Any>(workflowId)
        var attempts = 0
        while (task.isDefined) {
            if (attempts > maxAttempts) {
                throw AssertionError("Task for workflow $workflowId did not get dequeued after $maxAttempts attempts. task=$task")
            }
            waitFor(sleepTimeMs)
            task = scheduler.getTask(workflowId)
            attempts++
        }
    }

    /**
     * Wait for the execution flow to finish processing the workflow.
     * This is useful to avoid the test handle to race with the execution flow thread.
     */
    fun waitForExecutionFlowToFinish() {
        waitForExecutionFlowToFinish(defaultMaxAttempts, defaultSleepBetweenAttemptsInMs)
    }

    /**
     * Wait for a workflow to reach any of the given statuses.
     *
     * @param status the set of statuses to wait for. The workflow will be considered to have reached the status if it reaches any of
     * the statuses in the set.
     * @return the workflow instance once it reaches the status
     */
    fun waitForWorkflowToReachStatus(status: Set<WorkflowInstance.Status>): WorkflowInstance {
        return waitForWorkflowToReachStatus(status, defaultMaxAttempts, defaultSleepBetweenAttemptsInMs)
    }

    /**
     * Wait for a workflow to reach a specific status.
     *
     * @param status the status to wait for
     * @return the workflow instance once it reaches the status
     */
    fun waitForWorkflowToReachStatus(status: WorkflowInstance.Status): WorkflowInstance {
        return waitForWorkflowToReachStatus(setOf(status))
    }

    /**
     * Wait for a workflow to reach the WAITING status.
     *
     * @return the workflow instance once it reaches the WAITING status
     */
    fun expectWorkflowToWait(): WorkflowInstance {
        return waitForWorkflowToReachStatus(WorkflowInstance.Status.WAITING)
    }

    /**
     * Wait for a workflow to reach a terminal state.
     *
     * @return the workflow instance once it reaches the terminal state
     */
    fun waitForWorkflowToComplete(): WorkflowInstance {
        return waitForWorkflowToReachStatus(
            setOf(
                WorkflowInstance.Status.COMPLETED,
                WorkflowInstance.Status.ERROR,
                WorkflowInstance.Status.TIMEOUT
            )
        )
    }

    /**
     * Wait for a specific amount of time.
     */
    fun waitFor(sleepMillis: Long) {
        try {
            Thread.sleep(sleepMillis)
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }
    }

    /**
     * Expect a WaitSignal to be thrown by the lambda.
     */
    fun expectWaitSignal(lambda: Runnable) {
        try {
            lambda.run()
            throw AssertionError("Expected WaitSignal to be thrown but nothing was thrown!")
        } catch (e: CompletionException) {
            if (e.cause !is WaitSignal) throw AssertionError("Expected WaitSignal but got ${e.cause}", e)
        } catch (e: ExecutionException) {
            if (e.cause !is WaitSignal) throw AssertionError("Expected WaitSignal but got ${e.cause}", e)
        } catch (e: WaitSignal) {
            // expected
        }
    }
}
