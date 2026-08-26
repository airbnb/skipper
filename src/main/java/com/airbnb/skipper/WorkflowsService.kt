package com.airbnb.skipper

import com.airbnb.skipper.api.WorkflowInstanceStatusView
import com.airbnb.skipper.api.WorkflowInstanceView
import com.airbnb.skipper.internal.CheckpointTag
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.storage.WorkflowSortDirection
import com.airbnb.skipper.internal.storage.WorkflowSortField
import com.airbnb.skipper.internal.storage.WorkflowStore
import io.vavr.Tuple2
import io.vavr.control.Option
import java.util.Optional
import java.util.stream.Collectors
import javax.inject.Inject
import org.slf4j.LoggerFactory

/**
 * WorkflowsService is a component that can be used by the clients to execute operations that span
 * multiple workflows.
 *
 * For operations that target a specific single workflow instance, clients should use
 * [WorkflowFactory] to get the workflow instance first, then use [Workflow] API.
 */
open class WorkflowsService
    @Inject
    constructor(
        private val workflowStore: WorkflowStore,
        private val skipperEngine: SkipperEngine,
        private val scheduler: Scheduler
    ) {
        /**
         * Count the number of workflow instances that are in the specified statuses.
         *
         * @param statusViews a list of workflow instance statuses to count.
         * @return the number of workflow instances that are in the specified statuses.
         */
        fun countWorkflowsByStatus(statusViews: List<@JvmSuppressWildcards WorkflowInstanceStatusView>): Long {
            val statuses =
                statusViews.stream()
                    .map { v -> WorkflowInstance.Status.fromView(v) }
                    .collect(Collectors.toList())
            return workflowStore.countWorkflowsByStatus(io.vavr.collection.List.ofAll(statuses))
        }

        /**
         * Find all the workflow instances that have exhausted their retries and are waiting for
         * manual intervention.
         *
         * This method will return the workflow instances that have been waiting for the longest
         * time first. It will truncate the results to the specified limit.
         *
         * @param limit the maximum number of workflow instances to return. Please use a reasonable
         *     limit in the order of 1000 or less to avoid performance issues.
         * @return a list of workflow instances that have exhausted their retries and are waiting for
         *     manual intervention.
         */
        fun findWorkflowsWithExhaustedRetries(limit: Int): List<WorkflowInstanceView> {
            require(limit > 0 && limit <= BATCH_OPERATION_LIMIT) {
                "limit must be between 1 and $BATCH_OPERATION_LIMIT, inclusive."
            }
            return workflowStore
                // Not caller-configurable: this method's contract above promises longest-waiting
                // first, so the sort is part of its published behaviour. The admin endpoints, which
                // make no such promise, are the ones that opt out of it.
                .findWorkflowsWithExhaustedRetries(
                    limit,
                    WorkflowSortField.UPDATED_AT,
                    WorkflowSortDirection.ASC,
                )
                .map { w ->
                    w.toView {
                        workflowStore
                            .getActionCheckpoints(w.workflowId)
                            .map { it.toView() }
                    }
                }
                .toJavaList()
        }

        /**
         * Schedule an execution for the specified workflow instances.
         *
         * The workflow instances must be in a non-terminal state for the execution to be scheduled.
         * An example of a non-terminal state is [WorkflowInstance.Status.RUNNING] or
         * [WorkflowInstance.Status.RETRIES_EXHAUSTED].
         *
         * The actual executions will happen asynchronously, this method will not wait for the actual
         * executions to complete. Use another mechanism to monitor the status of the workflow.
         *
         * @param workflowIds a list of workflow instance IDs to re-execute.
         * @return a map of workflow instance IDs to the result of the re-execution. An empty Option
         *     indicates a successful re-execution, while a non-empty Option contains an exception if
         *     the re-execution schedule failed.
         */
        fun reExecuteWorkflows(workflowIds: List<String>): Map<String, Optional<Throwable>> {
            val result = HashMap<String, Optional<Throwable>>()
            for (workflowId in workflowIds) {
                try {
                    val workflowInstance =
                        workflowStore
                            .getWorkflow(workflowId)
                            .getOrElseThrow {
                                IllegalArgumentException("workflow instance not found: $workflowId")
                            }
                    check(!workflowInstance.status.isTerminal()) {
                        "Cannot re-execute a terminal workflow instance: $workflowId. " +
                            "Consider using cloneAsNew API instead."
                    }
                    // Schedule the execution of the workflow instance. Bypass in-process execution
                    // to avoid overwhelming the current process with too many executions. By
                    // scheduling the executions in the persistent scheduler, the executions will be
                    // distributed.
                    if (workflowInstance.status.isCompensationInProgress()) {
                        skipperEngine.scheduleCompensationTask(workflowInstance)
                    } else {
                        skipperEngine.scheduleExecution(workflowInstance, false)
                    }
                    result[workflowId] = Optional.empty()
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable
                ) {
                    result[workflowId] = Optional.of(e)
                }
            }
            return result
        }

        /**
         * Get the list of failed tasks.
         *
         * @param limit the maximum number of failed tasks to return. Must be between 1 and 1000,
         *     inclusive.
         * @return a list of task IDs that have failed.
         */
        @JvmSuppressWildcards
        fun inspectDeadLetterQueue(limit: Int): List<Task<Any>> {
            require(limit > 0 && limit <= BATCH_OPERATION_LIMIT) {
                "limit must be between 1 and $BATCH_OPERATION_LIMIT, inclusive."
            }
            return scheduler.getFailedTasks<Any>().take(limit).toJavaList()
        }

        /**
         * Redrive the dead letter queue by requeuing failed tasks.
         *
         * @param tasks the list of tasks to requeue.
         * @return a list of task IDs that were successfully requeued.
         */
        @JvmSuppressWildcards
        fun redriveDeadLetterQueue(tasks: List<Task<Any>>): List<String> {
            val requeuedTasks = ArrayList<String>()
            for (task in tasks) {
                try {
                    scheduler.requeueFailedTask(task.id)
                    requeuedTasks.add(task.id)
                    log.info("Re-queued task {}", task.id)
                } catch (e: Exception) {
                    log.error("Failed to requeue task: {}", task.id, e)
                }
            }
            return requeuedTasks
        }

        /**
         * Remove the failed tasks from the DLQ.
         *
         * The tasks removed will be lost forever, so use this method with caution.
         *
         * @param tasks the list of tasks to remove.
         * @return a list of task IDs that were successfully removed.
         */
        @JvmSuppressWildcards
        fun removeFromDeadLetterQueue(tasks: List<Task<Any>>): List<String> {
            val requeuedTasks = ArrayList<String>()
            for (task in tasks) {
                try {
                    scheduler.remove(task)
                    log.info("Removed task {} from scheduler", task.id)
                    requeuedTasks.add(task.id)
                } catch (e: Exception) {
                    log.error("Failed to remove task: {}", task.id, e)
                }
            }
            return requeuedTasks
        }

        /**
         * Cancel the specified workflow instances. This will mark the workflow instances as CANCELED
         * and will remove them from the scheduler. If the task is in DLQ it will be removed.
         *
         * @param workflowIds a list of workflow instance IDs to cancel. Their status must be a
         *     non-terminal status otherwise it will fail.
         * @param reason the reason for canceling the workflow instances. This will be recorded in
         *     the result.
         * @return a list of workflow instance IDs that were successfully canceled.
         */
        fun cancelWorkflows(
            workflowIds: List<String>,
            reason: String
        ): List<String> {
            val cancelledWorkflowIds = ArrayList<String>()
            for (workflowId in workflowIds) {
                try {
                    skipperEngine.cancelWorkflow(workflowId, reason)
                    cancelledWorkflowIds.add(workflowId)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable
                ) {
                    log.error("Failed to cancel workflow instance: {}", workflowId, e)
                }
            }
            return cancelledWorkflowIds
        }

        /**
         * Force-delete a single workflow instance. Cancels first if not in terminal state, then
         * permanently removes the workflow and all associated data from the database. This operation
         * is irreversible.
         *
         * @param workflowId the workflow instance ID to delete
         */
        fun deleteWorkflow(workflowId: String) {
            skipperEngine.forceDeleteWorkflow(workflowId)
        }

        /**
         * Reset the specified workflow instances from error state. This will reset the workflow
         * status and delete the error checkpoint atomically.
         *
         * After resetting, the workflow will be scheduled for execution.
         *
         * @param workflowIds a list of workflow instance IDs to reset. Their status must be ERROR
         *     otherwise it will fail.
         * @return a list of workflow instance IDs that were successfully reset.
         */
        fun resetWorkflowsFromError(workflowIds: List<String>): List<String> {
            val resetWorkflowIds = ArrayList<String>()
            for (workflowId in workflowIds) {
                try {
                    // Step 1: Reset workflow status and delete error checkpoint atomically
                    val resetResult: Tuple2<WorkflowInstance, Option<ActionCheckpoint>> =
                        workflowStore.resetWorkflowFromError(workflowId)
                    val resetWorkflow = resetResult._1
                    // Step 2: Schedule execution of the reset workflow
                    skipperEngine.scheduleExecution(resetWorkflow, false)
                    resetWorkflowIds.add(workflowId)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable
                ) {
                    log.error("Failed to reset workflow instance: {}", workflowId, e)
                }
            }
            return resetWorkflowIds
        }

        /**
         * Rewind a workflow to a given pivot checkpoint. This is an admin operation intended to
         * recover workflows affected by an incident. It can be applied regardless of whether the
         * workflow is currently in a terminal status or not.
         *
         * The rewind:
         *
         *  1. Sets the workflow status to [WorkflowInstance.Status.RUNNING].
         *  2. Deletes the pivot checkpoint and every action checkpoint that happened after it.
         *  3. Schedules the workflow for immediate execution, so it replays from the pivot onwards.
         *
         * Steps 1 and 2 happen atomically. The execution scheduled in step 3 happens asynchronously;
         * this method does not wait for it to complete.
         *
         * @param workflowId the workflow instance ID to rewind.
         * @param pivot the checkpoint tag identifying the pivot checkpoint. The pivot and every later
         *     checkpoint are deleted. Matching follows [CheckpointTag.matches]: name-based when the
         *     tag carries a checkpoint name, positional otherwise.
         * @return the updated workflow instance after the rewind.
         */
        fun rewindWorkflow(
            workflowId: String,
            pivot: CheckpointTag
        ): WorkflowInstance {
            val result: Tuple2<WorkflowInstance, io.vavr.collection.List<ActionCheckpoint>> =
                workflowStore.rewindWorkflow(workflowId, pivot)
            val rewoundWorkflow = result._1
            // Schedule the execution in the persistent scheduler (bypass in-process execution) so
            // the workflow replays from the pivot point onwards.
            skipperEngine.scheduleExecution(rewoundWorkflow, false)
            return rewoundWorkflow
        }

        /**
         * Rewind a workflow to the checkpoint with the given name. Checkpoint names are unique within
         * a single workflow instance. See [rewindWorkflow] for the full semantics.
         *
         * @param workflowId the workflow instance ID to rewind.
         * @param pivotCheckpointName the name of the pivot checkpoint. The pivot and every later
         *     checkpoint are deleted.
         * @return the updated workflow instance after the rewind.
         * @throws IllegalArgumentException if no checkpoint with the given name exists for the
         *     workflow.
         */
        fun rewindWorkflow(
            workflowId: String,
            pivotCheckpointName: String
        ): WorkflowInstance {
            val pivot =
                workflowStore
                    .getActionCheckpoints(workflowId)
                    .map { it.checkpointTag }
                    .find { tag -> pivotCheckpointName == tag.checkpointName }
                    .getOrElseThrow {
                        IllegalArgumentException(
                            "No checkpoint named '$pivotCheckpointName' found for workflow $workflowId"
                        )
                    }
            return rewindWorkflow(workflowId, pivot)
        }

        companion object {
            private val log = LoggerFactory.getLogger(WorkflowsService::class.java)

            const val BATCH_OPERATION_LIMIT: Int = 1000
        }
    }
