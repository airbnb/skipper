package com.airbnb.skipper.testutils.trace

import com.airbnb.skipper.Timer
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.CheckpointTag
import com.airbnb.skipper.internal.api.ActionCheckpoint
import com.airbnb.skipper.internal.api.PersistedSignal
import com.airbnb.skipper.internal.storage.TimerCreationRequest
import com.airbnb.skipper.internal.storage.WorkflowCreationRequest
import com.airbnb.skipper.internal.storage.WorkflowSearchFilter
import com.airbnb.skipper.internal.storage.WorkflowSortDirection
import com.airbnb.skipper.internal.storage.WorkflowSortField
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.internal.storage.WorkflowUpdateRequest
import com.airbnb.skipper.testutils.trace.TraceRecorder.Touched
import io.vavr.Tuple2
import io.vavr.Tuple3
import io.vavr.collection.List
import io.vavr.control.Option

/**
 * A [WorkflowStore] that records every call that writes a workflow, timer, checkpoint or signal row,
 * and the few reads the model treats as steps (see [getWorkflow]).
 */
internal class TracingWorkflowStore(
    private val delegate: WorkflowStore,
    private val recorder: TraceRecorder,
) : WorkflowStore {
    private companion object {
        val MODELLED_READS = listOf("WorkflowExecutionTaskHandler.handle", "SkipperEngine.runSignal")
        val MODELLED_TIMER_READS = listOf("WorkflowExecutionTaskHandler.handle")
    }

    private fun <R> write(
        op: String,
        workflowId: String,
        args: Map<String, Any?> = emptyMap(),
        call: () -> R,
    ): R = recorder.record(op, args, { listOf(Touched(workflowId)) }, call)

    /** What an update asks for: the status it writes and the version it expects to replace. */
    private fun updateArgs(request: WorkflowUpdateRequest): Map<String, Any?> =
        mapOf("newStatus" to request.newStatus, "heldVersion" to request.workflowInstance.version)

    override fun createWorkflow(request: WorkflowCreationRequest): WorkflowInstance =
        write("createWorkflow", request.workflowId) { delegate.createWorkflow(request) }

    override fun updateWorkflow(request: WorkflowUpdateRequest): WorkflowInstance =
        write("updateWorkflow", request.workflowInstance.workflowId, updateArgs(request)) { delegate.updateWorkflow(request) }

    override fun updateWorkflowAndStoreCheckpointsAndTimers(
        request: WorkflowUpdateRequest,
        checkpoints: List<ActionCheckpoint>,
        timers: List<Timer>,
    ): Tuple3<WorkflowInstance, List<ActionCheckpoint>, List<Timer>> =
        write(
            "updateWorkflowAndStoreCheckpointsAndTimers",
            request.workflowInstance.workflowId,
            updateArgs(request) + mapOf("timers" to timers.toJavaList().associate { it.id to it.status.name }),
        ) { delegate.updateWorkflowAndStoreCheckpointsAndTimers(request, checkpoints, timers) }

    override fun updateWorkflowAndMarkSignal(
        request: WorkflowUpdateRequest,
        signalId: Long,
        status: PersistedSignal.Status,
    ): Tuple2<WorkflowInstance, PersistedSignal> =
        write("updateWorkflowAndMarkSignal", request.workflowInstance.workflowId, updateArgs(request)) {
            delegate.updateWorkflowAndMarkSignal(request, signalId, status)
        }

    override fun storeActionCheckpoints(
        workflowId: String,
        checkpoints: List<ActionCheckpoint>,
    ): List<ActionCheckpoint> = write("storeActionCheckpoints", workflowId) { delegate.storeActionCheckpoints(workflowId, checkpoints) }

    override fun createTimer(timer: TimerCreationRequest): Timer = write("createTimer", timer.workflowId) { delegate.createTimer(timer) }

    override fun expireTimer(
        workflowId: String,
        timerId: String,
    ): Boolean = write("expireTimer", workflowId, mapOf("timer" to timerId)) { delegate.expireTimer(workflowId, timerId) }

    @Deprecated("see WorkflowStore.cancelTimer")
    override fun cancelTimer(
        workflowId: String,
        timerId: String,
    ): Boolean = write("cancelTimer", workflowId, mapOf("timer" to timerId)) { delegate.cancelTimer(workflowId, timerId) }

    override fun deleteWorkflow(workflowId: String) = write("deleteWorkflow", workflowId) { delegate.deleteWorkflow(workflowId) }

    override fun resetWorkflowFromError(workflowId: String): Tuple2<WorkflowInstance, Option<ActionCheckpoint>> =
        write("resetWorkflowFromError", workflowId) { delegate.resetWorkflowFromError(workflowId) }

    override fun rewindWorkflow(
        workflowId: String,
        pivot: CheckpointTag,
    ): Tuple2<WorkflowInstance, List<ActionCheckpoint>> = write("rewindWorkflow", workflowId) { delegate.rewindWorkflow(workflowId, pivot) }

    override fun persistSignal(signal: PersistedSignal): PersistedSignal =
        write("persistSignal", signal.workflowId) { delegate.persistSignal(signal) }

    override fun updateSignalStatus(
        workflowId: String,
        signalId: Long,
        status: PersistedSignal.Status,
        error: String?,
    ): PersistedSignal = write("updateSignalStatus", workflowId) { delegate.updateSignalStatus(workflowId, signalId, status, error) }

    /**
     * Reads pass through untraced, except the ones the model treats as steps of their own: a task
     * handler loading the instance it is about to run and then its timers, and a signal loading the
     * instance it applies to. Logging them pins down when each read happened, so the checker does not
     * have to guess. The test helpers' constant polling stays out of the trace.
     */
    override fun getWorkflow(workflowId: String): Option<WorkflowInstance> =
        if (TraceRecorder.calledFrom(MODELLED_READS)) {
            write("getWorkflow", workflowId) { delegate.getWorkflow(workflowId) }
        } else {
            delegate.getWorkflow(workflowId)
        }

    override fun countWorkflowsByStatus(statuses: List<WorkflowInstance.Status>): Long = delegate.countWorkflowsByStatus(statuses)

    override fun findWorkflowsWithExhaustedRetries(
        limit: Int,
        sortField: WorkflowSortField?,
        sortDirection: WorkflowSortDirection?,
    ): List<WorkflowInstance> = delegate.findWorkflowsWithExhaustedRetries(limit, sortField, sortDirection)

    override fun getActionCheckpoints(workflowId: String): List<ActionCheckpoint> = delegate.getActionCheckpoints(workflowId)

    override fun getTimers(workflowId: String): List<Timer> =
        if (TraceRecorder.calledFrom(MODELLED_TIMER_READS)) {
            write("getTimers", workflowId) { delegate.getTimers(workflowId) }
        } else {
            delegate.getTimers(workflowId)
        }

    override fun getTimer(
        workflowId: String,
        timerId: String,
    ): Option<Timer> = delegate.getTimer(workflowId, timerId)

    override fun listDistinctWorkflowTypes(): List<Tuple2<String, String>> = delegate.listDistinctWorkflowTypes()

    override fun findWorkflows(
        filter: WorkflowSearchFilter,
        limit: Int,
    ): List<WorkflowInstance> = delegate.findWorkflows(filter, limit)

    override fun getPersistedSignals(workflowId: String): List<PersistedSignal> = delegate.getPersistedSignals(workflowId)

    override fun getPersistedSignal(
        workflowId: String,
        signalId: Long,
    ): Option<PersistedSignal> = delegate.getPersistedSignal(workflowId, signalId)
}
