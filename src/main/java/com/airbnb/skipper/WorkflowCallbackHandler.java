package com.airbnb.skipper;

import com.airbnb.skipper.api.WorkflowInstanceView;

public interface WorkflowCallbackHandler {
  /**
   * Called when a workflow instance is completed successfully.
   *
   * @param workflowInstance the workflow instance that was successfully completed.
   */
  void onSuccess(WorkflowInstanceView workflowInstance);

  /**
   * Called when a workflow instance reached a non-retryable error state.
   *
   * <p>This could be caused by one of the following:
   *
   * <ul>
   *   <li>A non-retryable exception was thrown either by an action or a workflow and was not
   *       handled.
   *   <li>The maximum number of retries was reached for a retryable exception.
   * </ul>
   *
   * @param workflowInstance the workflow instance that failed.
   */
  void onNonRetryableError(WorkflowInstanceView workflowInstance, Throwable error);

  /**
   * Called whenever the workflow instance enters a waiting state, which happens anytime the
   * workflow code reaches a conditional wait that has not been fulfilled.
   *
   * @param workflowInstance The workflow instance that is in a waiting state.
   */
  void onWorkflowInWaitingStatus(WorkflowInstanceView workflowInstance);

  /**
   * Called when a workflow instance's.
   *
   * @param workflowInstance the workflow instance that was cancelled.
   */
  void onWorkflowTimeout(WorkflowInstanceView workflowInstance);

  /**
   * Called when a workflow instance execution has exhausted all retries of a persistent action
   * retry strategy.
   *
   * @param workflowInstance the workflow instance.
   * @param error the last retryable error that caused the retries to be exhausted.
   */
  default void onRetriesExhausted(WorkflowInstanceView workflowInstance, Throwable lastError) {
    if (workflowInstance == null) {
      throw new java.lang.NullPointerException("workflowInstance is marked non-null but is null");
    }
    throw new UnsupportedOperationException("onRetriesExhausted is not implemented");
  }

  /**
   * Called when a workflow instance encounters a retryable error.
   *
   * <p>This method is invoked when the workflow execution encounters an error that can be retried,
   * such as transient failures or temporary unavailability of external services.
   *
   * <p>Given the fact that the workflow is not in a terminal state at this point, it is _very
   * inlikely_ but possible that the workflow execution had been continued in another process by the
   * time this method is called, and the workflow instance is no longer in a retryable state.
   *
   * @param workflowInstance the workflow instance that encountered the retryable error.
   * @param error the retryable error that was encountered.
   */
  default void onRetryableError(WorkflowInstanceView workflowInstance, Throwable error) {
    if (workflowInstance == null) {
      throw new java.lang.NullPointerException("workflowInstance is marked non-null but is null");
    }
    // Default empty implementation
  }

  /**
   * Called when a workflow compensation has completed successfully.
   *
   * <p>This method is invoked after the compensation flow has executed all compensation methods for
   * previously successful actions in reverse chronological order.
   *
   * @param workflowInstance the workflow instance whose compensation was completed.
   */
  default void onCompensationCompleted(WorkflowInstanceView workflowInstance) {
    if (workflowInstance == null) {
      throw new java.lang.NullPointerException("workflowInstance is marked non-null but is null");
    }
    // Default empty implementation
  }

  /**
   * Called when a workflow compensation has failed with a non-retryable error.
   *
   * <p>This method is invoked when the compensation flow encounters an error that cannot be
   * retried, such as a non-retryable exception thrown by a compensation method.
   *
   * @param workflowInstance the workflow instance whose compensation failed.
   * @param error the error that caused the compensation to fail, or null if no specific error
   *     information is available.
   */
  default void onCompensationError(WorkflowInstanceView workflowInstance, SkipperError error) {
    if (workflowInstance == null) {
      throw new java.lang.NullPointerException("workflowInstance is marked non-null but is null");
    }
    // Default empty implementation
  }

  /**
   * Called when a workflow instance is cancelled.
   *
   * <p>This method is invoked when the workflow execution gets cancelled by a flow explicitly
   * calling the cancel workflow method. The workflow is in a terminal state at this point.
   *
   * @param workflowInstance the workflow instance that got cancelled.
   * @param reason the reason provided for cancelling the workflow.
   */
  default void onCancelled(WorkflowInstanceView workflowInstance, String reason) {
    if (workflowInstance == null) {
      throw new java.lang.NullPointerException("workflowInstance is marked non-null but is null");
    }
    if (reason == null) {
      throw new java.lang.NullPointerException("reason is marked non-null but is null");
    }
    // Default empty implementation
  }
}
