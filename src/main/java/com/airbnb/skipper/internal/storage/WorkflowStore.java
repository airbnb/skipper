package com.airbnb.skipper.internal.storage;

import com.airbnb.skipper.Timer;
import com.airbnb.skipper.WorkflowInstance;
import com.airbnb.skipper.internal.CheckpointTag;
import com.airbnb.skipper.internal.api.ActionCheckpoint;
import com.airbnb.skipper.internal.api.PersistedSignal;
import io.vavr.Tuple2;
import io.vavr.Tuple3;
import io.vavr.collection.List;
import io.vavr.control.Option;

public interface WorkflowStore {
  /**
   * Create a new workflow instance. If a workflow instance already exists with the same correlation
   * ID, this method will fail.
   *
   * @param request The creation request
   * @return The newly created and persisted workflow instance. In the case that a workflow instance
   *     with the same ID already exists, the returned future will be completed exceptionally with a
   *     {@link EntityAlreadyExists} exception.
   */
  WorkflowInstance createWorkflow(WorkflowCreationRequest request) throws EntityAlreadyExists;

  /**
   * Update a workflow instance. This method is thread safe.
   *
   * @param request The update request
   * @return The updated workflow instance.
   */
  WorkflowInstance updateWorkflow(WorkflowUpdateRequest request);

  /**
   * Get workflow by workflow ID.
   *
   * @param workflowId The workflow ID
   * @return the workflow instance.
   */
  Option<WorkflowInstance> getWorkflow(String workflowId);

  /**
   * Count the number of workflows in a given status.
   *
   * @param statuses The list of statuses to count workflows for.
   * @return The number of workflows in the given statuses. The implementation might be forced to
   *     place an upper limit on the number of statuses it can handle. E.g. the count API in a
   *     remote-store impl can be expensive, so this method might limit the number of statuses to a
   *     few thousands.
   */
  long countWorkflowsByStatus(List<WorkflowInstance.Status> statuses);

  /**
   * Find workflows with exhausted retries.
   *
   * <p>This method will return workflows that have exhausted their retries and are in a waiting for
   * manual trigger of a retry. This includes workflows in RETRIES_EXHAUSTED and COMPENSATION_ERROR
   * states.
   *
   * @param limit The maximum number of workflows to return.
   * @param sortField The field to sort the results by, or {@code null} to let the store return them
   *     in whatever order is cheapest for it to produce — still deterministic, but unspecified.
   *     Sorting is not backed by an index in every store implementation, so it can be prohibitively
   *     expensive once an owner accumulates a large backlog of stuck workflows. Callers that do not
   *     depend on a particular order (e.g. counting the backlog, or listing it for manual triage)
   *     should pass {@code null}.
   * @param sortDirection The direction to apply to {@code sortField}. Ignored when {@code
   *     sortField} is {@code null}, since there is no sort to direct.
   * @return A list of workflow instances that have exhausted their retries. Note that the actual
   *     number of workflow in the given status may be larger that the limit. It is expected that
   *     the caller will retry the workflows based on the returned list, and that it will call this
   *     method again to get the next batch of workflows with exhausted retries.
   */
  List<WorkflowInstance> findWorkflowsWithExhaustedRetries(
      int limit, WorkflowSortField sortField, WorkflowSortDirection sortDirection);

  /**
   * Get all action checkpoints for a workflow.
   *
   * @param workflowId The workflow ID
   * @return The list of action checkpoints
   */
  List<ActionCheckpoint> getActionCheckpoints(String workflowId);

  /**
   * Store action checkpoints for a workflow.
   *
   * @param workflowId The workflow ID
   * @param checkpoints The list of action checkpoints to store
   * @return The list of action checkpoints that were stored
   */
  List<ActionCheckpoint> storeActionCheckpoints(
      String workflowId, List<ActionCheckpoint> checkpoints);

  /**
   * Update a workflow instance, store action checkpoints and insert timers atomically.
   *
   * <p>In case this method fails, the workflow instance will not be updated and no action
   * checkpoints will be stored and no timers will be created/updated.
   *
   * <p>In case a list of timers is provided, they will be created with a "create if not exists"
   * logic. If the timer already exists, and it is in a non-terminal state, this will be a no-op. If
   * the timer already exists, and it is in a terminal state, this will raise a retryable error.
   *
   * @param request The workflow update request
   * @param checkpoints The list of action checkpoints to store
   * @param timers The list of timers to create
   * @return A tuple containing the updated workflow instance and the list of action checkpoints
   *     that were stored and the list of timers that were created or updated.
   */
  Tuple3<WorkflowInstance, List<ActionCheckpoint>, List<Timer>>
      updateWorkflowAndStoreCheckpointsAndTimers(
          WorkflowUpdateRequest request, List<ActionCheckpoint> checkpoints, List<Timer> timers);

  /**
   * Create a new timer.
   *
   * <p>This function won't actually schedule the timer execution. It will only create the timer
   * model and store it.
   *
   * @param timer The timer creation request
   * @return The newly created timer
   */
  Timer createTimer(TimerCreationRequest timer);

  /**
   * Expire a timer.
   *
   * <p>If the underlying timer is in a terminal state, this method will do nothing and return
   * false. Otherwise, it will expire the timer and return true.
   *
   * @param workflowId The workflow ID associated to the timer.
   * @param timerId The timer ID to expire.
   * @return true if the timer was successfully expired, false otherwise.
   */
  boolean expireTimer(String workflowId, String timerId);

  /**
   * Cancel a timer.
   *
   * <p>If the underlying timer is in a terminal state, this method will do nothing and return
   * false. Otherwise, it will cancel the timer and return true.
   *
   * <p>This is deprecated and only used in tests.
   *
   * @param workflowId The workflow ID associated to the timer.
   * @param timerId The timer ID to cancel.
   * @return true if the timer was successfully canceled, false otherwise.
   */
  @Deprecated
  boolean cancelTimer(String workflowId, String timerId);

  /**
   * Get all timers for a workflow instance.
   *
   * @param workflowId The workflow ID
   * @return The list of timers
   */
  List<Timer> getTimers(String workflowId);

  /**
   * Get a timer by workflow ID and timer ID.
   *
   * @param workflowId The workflow ID
   * @param timerId The timer ID
   * @return The timer
   */
  Option<Timer> getTimer(String workflowId, String timerId);

  /**
   * Permanently delete a workflow instance and all its associated data (action checkpoints, timers,
   * persisted signals). The workflow must exist. This is an irreversible operation intended for
   * admin use only.
   *
   * @param workflowId The workflow ID to delete
   * @throws IllegalArgumentException if the workflow does not exist
   */
  void deleteWorkflow(String workflowId);

  /**
   * Reset a workflow from ERROR status to RUNNING status and delete the latest non-transient error
   * checkpoint atomically.
   *
   * <p>This operation is intended for manual operator intervention to "undo" the latest execution
   * that put the workflow into an ERROR state. Only workflows in ERROR status can be reset.
   *
   * @param workflowId The workflow ID to reset
   * @return The updated workflow instance after reset, or empty if workflow not found or not in
   *     ERROR status
   * @throws IllegalStateException if the workflow is not in ERROR status
   */
  Tuple2<WorkflowInstance, Option<ActionCheckpoint>> resetWorkflowFromError(String workflowId);

  /**
   * Rewind a workflow to a given pivot checkpoint, atomically:
   *
   * <ol>
   *   <li>Set the workflow status to {@link WorkflowInstance.Status#RUNNING} and reset its result,
   *       regardless of its current status. This works even for workflows in a terminal status.
   *   <li>Delete the pivot checkpoint and every checkpoint that happened after it (ordered by
   *       execution order).
   * </ol>
   *
   * <p>This operation is intended for manual operator intervention to recover workflows affected by
   * an incident, by replaying execution from the pivot point onwards. The caller is responsible for
   * scheduling the returned workflow for execution.
   *
   * <p>The pivot is matched against the stored checkpoints following {@link CheckpointTag#matches}:
   * name-based when the tag carries a checkpoint name, positional (action class + method +
   * iteration) otherwise.
   *
   * @param workflowId The workflow ID to rewind
   * @param pivot The checkpoint tag identifying the pivot checkpoint
   * @return A tuple of the updated workflow instance and the list of checkpoints that were deleted
   *     (the pivot and everything after it), ordered by execution order.
   * @throws IllegalArgumentException if the workflow or the pivot checkpoint does not exist
   */
  Tuple2<WorkflowInstance, List<ActionCheckpoint>> rewindWorkflow(
      String workflowId, CheckpointTag pivot);

  /**
   * List all distinct workflow types (class + method pairs) stored for this owner. Uses an
   * efficient skip-scan strategy on the composite index rather than scanning all rows.
   *
   * @return A list of (workflowClass, workflowMethod) pairs.
   */
  default List<Tuple2<String, String>> listDistinctWorkflowTypes() {
    throw new UnsupportedOperationException(
        "listDistinctWorkflowTypes is not supported by this WorkflowStore implementation");
  }

  /**
   * Search for workflow instances matching the given filter criteria.
   *
   * <p><b>Performance note:</b> The MySQL implementation pushes all filters into SQL and uses
   * cursor-based keyset pagination. A remote-store implementation may push most filters to the
   * server (owner, status, single entry point via composite index; date range, cursor time bound,
   * and parentWorkflowId via addFilter). Only multiple entry points and cursor workflowId
   * tie-breaking remain client-side. The scan is bounded by MAX_SCAN_PAGES. This is designed for
   * admin tooling (low QPS, human-initiated), not for production query paths.
   *
   * @param filter The search filter (entry points, statuses, date range, cursor for pagination)
   * @param limit Maximum number of results to return
   * @return A list of matching workflow instances, ordered by created_at/updated_at descending.
   */
  default List<WorkflowInstance> findWorkflows(WorkflowSearchFilter filter, int limit) {
    if (filter == null) {
      throw new java.lang.NullPointerException("filter is marked non-null but is null");
    }
    throw new UnsupportedOperationException(
        "findWorkflows is not supported by this WorkflowStore implementation");
  }

  /**
   * Persist a signal record before it is executed.
   *
   * <p>This is used by signal methods that opt into persistence via {@code @SignalMethod(persist =
   * true)}. The record is written with status {@link PersistedSignal.Status#PENDING} so that, even
   * if signal execution subsequently fails or the process crashes, the signal (and its input) is
   * durably recorded and can be manually replayed.
   *
   * @param signal The signal to persist. Its {@code id} is ignored and assigned by the store.
   * @return The persisted signal with its assigned {@code id}.
   */
  default PersistedSignal persistSignal(PersistedSignal signal) {
    if (signal == null) {
      throw new java.lang.NullPointerException("signal is marked non-null but is null");
    }
    throw new UnsupportedOperationException(
        "persistSignal is not supported by this WorkflowStore implementation");
  }

  /**
   * Atomically update a workflow instance and update the status of a persisted signal.
   *
   * <p>Used on the successful path of signal execution so that advancing the workflow state and
   * marking the signal as {@link PersistedSignal.Status#EXECUTED} commit together. If either write
   * fails, neither is applied.
   *
   * @param request The workflow update request.
   * @param signalId The id of the persisted signal to update.
   * @param status The new status for the persisted signal.
   * @return A tuple of the updated workflow instance and the updated persisted signal.
   */
  default Tuple2<WorkflowInstance, PersistedSignal> updateWorkflowAndMarkSignal(
      WorkflowUpdateRequest request, long signalId, PersistedSignal.Status status) {
    if (request == null) {
      throw new java.lang.NullPointerException("request is marked non-null but is null");
    }
    if (status == null) {
      throw new java.lang.NullPointerException("status is marked non-null but is null");
    }
    throw new UnsupportedOperationException(
        "updateWorkflowAndMarkSignal is not supported by this WorkflowStore implementation");
  }

  /**
   * Update the status (and optional error) of a persisted signal.
   *
   * <p>Used on the failure path of signal execution to mark a signal as {@link
   * PersistedSignal.Status#FAILED}. This is best-effort: if it fails, the signal remains in its
   * previous (e.g. {@link PersistedSignal.Status#PENDING}) state and is still replayable.
   *
   * @param workflowId The id of the workflow the signal targets.
   * @param signalId The id of the persisted signal to update.
   * @param status The new status for the persisted signal.
   * @param error A human-readable error trace to record, or null. Only meaningful for {@link
   *     PersistedSignal.Status#FAILED}. Stored for operator reference only.
   * @return The updated persisted signal.
   */
  default PersistedSignal updateSignalStatus(
      String workflowId, long signalId, PersistedSignal.Status status, String error) {
    if (workflowId == null) {
      throw new java.lang.NullPointerException("workflowId is marked non-null but is null");
    }
    if (status == null) {
      throw new java.lang.NullPointerException("status is marked non-null but is null");
    }
    throw new UnsupportedOperationException(
        "updateSignalStatus is not supported by this WorkflowStore implementation");
  }

  /**
   * Get all persisted signals for a workflow, ordered by creation time ascending.
   *
   * @param workflowId The workflow ID.
   * @return The list of persisted signals.
   */
  default List<PersistedSignal> getPersistedSignals(String workflowId) {
    if (workflowId == null) {
      throw new java.lang.NullPointerException("workflowId is marked non-null but is null");
    }
    throw new UnsupportedOperationException(
        "getPersistedSignals is not supported by this WorkflowStore implementation");
  }

  /**
   * Get a single persisted signal by workflow ID and signal ID.
   *
   * @param workflowId The workflow ID.
   * @param signalId The persisted signal ID.
   * @return The persisted signal, or empty if not found.
   */
  default Option<PersistedSignal> getPersistedSignal(String workflowId, long signalId) {
    if (workflowId == null) {
      throw new java.lang.NullPointerException("workflowId is marked non-null but is null");
    }
    throw new UnsupportedOperationException(
        "getPersistedSignal is not supported by this WorkflowStore implementation");
  }
}
