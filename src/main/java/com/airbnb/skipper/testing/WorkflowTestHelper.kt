package com.airbnb.skipper.testing

import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.api.WorkflowInstanceStatusView
import com.airbnb.skipper.api.WorkflowInstanceView
import com.airbnb.skipper.factory.SkipperRuntime
import com.airbnb.skipper.internal.api.WaitSignal
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * Wait-and-assert helpers for one workflow instance, available as `helper` on a [WorkflowTest].
 *
 * Workflows run on Skipper's own threads, so a test drives the workflow, then waits for the
 * engine to report the status it expects, then asserts. Waiting polls the store on wall-clock
 * time ([maxAttempts] times [sleepTimeMs]), independent of the test's [MutableClock].
 */
class WorkflowTestHelper(
    private val runtime: SkipperRuntime,
    private val workflowId: String,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val sleepTimeMs: Long = DEFAULT_SLEEP_MS,
) {
    /** Waits until the workflow has reached any of [statuses] and returns its view at that point. */
    fun waitForWorkflowToReachStatus(
        statuses: Set<WorkflowInstanceStatusView>,
        maxAttempts: Int = this.maxAttempts,
        sleepTimeMs: Long = this.sleepTimeMs,
    ): WorkflowInstanceView {
        val engine = runtime.skipperEngine.get()
        var stored = engine.getWorkflow(workflowId)
        var attempts = 0
        // The workflow may have been started from another thread and not be registered yet.
        while (stored.isEmpty) {
            if (attempts++ > maxAttempts) {
                throw AssertionError("Workflow $workflowId was not started after $maxAttempts attempts")
            }
            sleep(sleepTimeMs)
            stored = engine.getWorkflow(workflowId)
        }
        attempts = 0
        while (stored.get().status.toView() !in statuses) {
            if (attempts++ > maxAttempts) {
                throw AssertionError(
                    "Workflow $workflowId did not reach $statuses after $maxAttempts attempts; " +
                        "it is ${stored.get().status}. ${describe(stored.get())}",
                )
            }
            sleep(sleepTimeMs)
            stored = engine.getWorkflow(workflowId)
        }
        // The status is persisted slightly before the execution task is released, so wait for that
        // too; otherwise a signal or query from the test can race the tail of the execution.
        waitForExecutionFlowToFinish(maxAttempts, sleepTimeMs)
        return view(stored.get())
    }

    fun waitForWorkflowToReachStatus(status: WorkflowInstanceStatusView): WorkflowInstanceView = waitForWorkflowToReachStatus(setOf(status))

    /** Waits until the workflow is parked on a `waitUntil` (or a timer). */
    fun expectWorkflowToWait(): WorkflowInstanceView = waitForWorkflowToReachStatus(WorkflowInstanceStatusView.WAITING)

    /** Waits until the workflow reaches any terminal status and returns it; assert on `status` yourself. */
    fun waitForWorkflowToComplete(): WorkflowInstanceView = waitForWorkflowToReachStatus(TERMINAL)

    /** Waits until the scheduler no longer holds an execution task for the workflow. */
    fun waitForExecutionFlowToFinish(
        maxAttempts: Int = this.maxAttempts,
        sleepTimeMs: Long = this.sleepTimeMs,
    ) {
        val scheduler = runtime.scheduler.get()
        var attempts = 0
        while (scheduler.getTask<Any>(workflowId).isDefined) {
            if (attempts++ > maxAttempts) {
                throw AssertionError("The execution task for $workflowId was still scheduled after $maxAttempts attempts")
            }
            sleep(sleepTimeMs)
        }
    }

    /** Polls [condition] until it holds. */
    fun waitForCondition(
        maxAttempts: Int = this.maxAttempts,
        sleepTimeMs: Long = this.sleepTimeMs,
        condition: () -> Boolean,
    ) {
        var attempts = 0
        while (!condition()) {
            if (attempts++ > maxAttempts) {
                throw AssertionError("Condition was not met after $maxAttempts attempts")
            }
            sleep(sleepTimeMs)
        }
    }

    /** The current view of the workflow, including its action checkpoints. */
    fun currentView(): WorkflowInstanceView = currentViewOrNull() ?: throw AssertionError("Workflow $workflowId has not been started")

    /** Like [currentView], but `null` while the workflow has not been started. */
    fun currentViewOrNull(): WorkflowInstanceView? {
        val stored = runtime.skipperEngine.get().getWorkflow(workflowId)
        return if (stored.isEmpty) null else view(stored.get())
    }

    /** Asserts that [block] suspends the workflow (throws Skipper's internal wait signal). */
    fun expectWaitSignal(block: Runnable) {
        try {
            block.run()
        } catch (e: CompletionException) {
            if (e.cause is WaitSignal) return
            throw e
        } catch (e: ExecutionException) {
            if (e.cause is WaitSignal) return
            throw e
        } catch (e: WaitSignal) {
            return
        }
        throw AssertionError("Expected the workflow to wait, but the call completed")
    }

    private fun view(instance: WorkflowInstance): WorkflowInstanceView =
        instance.toView { runtime.workflowStore.get().getActionCheckpoints(workflowId).map { it.toView() } }

    private fun describe(instance: WorkflowInstance): String =
        "checkpoints=" + runtime.workflowStore.get().getActionCheckpoints(workflowId).map { it.toView() }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AssertionError("interrupted while waiting", e)
        }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 200
        const val DEFAULT_SLEEP_MS = 50L

        val TERMINAL: Set<WorkflowInstanceStatusView> =
            setOf(
                WorkflowInstanceStatusView.COMPLETED,
                WorkflowInstanceStatusView.ERROR,
                WorkflowInstanceStatusView.TIMEOUT,
                WorkflowInstanceStatusView.CANCELLED,
                WorkflowInstanceStatusView.COMPENSATION_COMPLETED,
            )
    }
}
