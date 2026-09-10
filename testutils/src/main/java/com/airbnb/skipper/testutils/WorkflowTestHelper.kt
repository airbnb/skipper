package com.airbnb.skipper.testutils

import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.api.WorkflowInstanceStatusView
import com.airbnb.skipper.api.WorkflowInstanceView
import com.airbnb.skipper.factory.SkipperRuntime
import com.airbnb.skipper.internal.api.WaitSignal
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * Wait-and-assert helpers for one workflow instance, available as `helper` on a [WorkflowTest].
 *
 * Workflows run on Skipper's own threads, so a test drives the workflow, then waits for the
 * engine to report the status it expects, then asserts. Waiting polls the store on wall-clock
 * time ([maxAttempts] times [sleepTimeMs]), independent of the test's [MutableClock].
 *
 * Skipper's timers (`waitUntil` deadlines, `sleep`, and the delay before each **retry**) fire on
 * the runtime's clock. A [WorkflowTest] clock ticks with real time, so millisecond retry delays pass
 * on their own; a delay the test should not sit through (a long `sleep`, a deadline in days, the
 * 30-second compensation backoff) is reached with [fastForwardUntilWorkflowCompletes] or
 * [fastForwardUntilWorkflowReachesStatus], which step [clock] forward between polls. With a clock
 * fixed at the epoch those are the only way such a workflow ever finishes.
 */
class WorkflowTestHelper
    @JvmOverloads
    constructor(
        private val runtime: SkipperRuntime,
        private val workflowId: String,
        private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        private val sleepTimeMs: Long = DEFAULT_SLEEP_MS,
        /** The clock the runtime was configured with; required by the `fastForward...` methods. */
        private val clock: MutableClock? = null,
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

        /**
         * Steps [clock] forward by [step] between polls until the workflow reaches any of [statuses],
         * so timers the workflow is waiting on (retry delays, `sleep`, `waitUntil` deadlines) fire.
         *
         * Each retry schedules a fresh timer relative to the already-advanced clock, so a single
         * `clock.fastForward(...)` is not enough for a retried action: this advances the clock by [step]
         * on every poll until the status is reached, at most [maxAttempts] times. Keep [step] at or
         * above your retry delay and below any deadline you do **not** want to expire.
         */
        @JvmOverloads
        fun fastForwardUntilWorkflowReachesStatus(
            statuses: Set<WorkflowInstanceStatusView>,
            step: Duration = DEFAULT_FAST_FORWARD_STEP,
        ): WorkflowInstanceView {
            val clock =
                checkNotNull(clock) {
                    "fastForward... needs the runtime's MutableClock: pass it to WorkflowTestHelper " +
                        "(WorkflowTest does this for you)"
                }
            val engine = runtime.skipperEngine.get()
            var attempts = 0
            while (true) {
                val stored = engine.getWorkflow(workflowId)
                if (stored.isDefined && stored.get().status.toView() in statuses) {
                    waitForExecutionFlowToFinish(maxAttempts, sleepTimeMs)
                    return view(stored.get())
                }
                if (attempts++ > maxAttempts) {
                    val state = if (stored.isDefined) "it is ${stored.get().status}. ${describe(stored.get())}" else "it was never started"
                    throw AssertionError(
                        "Workflow $workflowId did not reach $statuses after $maxAttempts attempts of " +
                            "advancing the clock by $step; $state",
                    )
                }
                clock.fastForward(step)
                sleep(sleepTimeMs)
            }
        }

        @JvmOverloads
        fun fastForwardUntilWorkflowReachesStatus(
            status: WorkflowInstanceStatusView,
            step: Duration = DEFAULT_FAST_FORWARD_STEP,
        ): WorkflowInstanceView = fastForwardUntilWorkflowReachesStatus(setOf(status), step)

        /** Like [waitForWorkflowToComplete], but advances the clock so pending retries and timers fire. */
        @JvmOverloads
        fun fastForwardUntilWorkflowCompletes(step: Duration = DEFAULT_FAST_FORWARD_STEP): WorkflowInstanceView =
            fastForwardUntilWorkflowReachesStatus(TERMINAL, step)

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

            /** One second per poll: past any short retry delay, far from any real deadline. */
            @JvmField
            val DEFAULT_FAST_FORWARD_STEP: Duration = Duration.ofSeconds(1)

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
