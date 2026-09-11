package com.example.orders;

import com.airbnb.skipper.SkipperError;
import com.airbnb.skipper.WorkflowCallbackHandler;
import com.airbnb.skipper.api.WorkflowInstanceView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Lifecycle notifications for long-running instances. Registered by class; Skipper's injector instantiates it. */
public class OrderCallbackHandler implements WorkflowCallbackHandler {
  private static final Logger log = LoggerFactory.getLogger(OrderCallbackHandler.class);

  @Override
  public void onSuccess(WorkflowInstanceView workflow) {
    log.info("{} completed", workflow.getId());
  }

  @Override
  public void onNonRetryableError(WorkflowInstanceView workflow, Throwable error) {
    log.info("{} failed: {}", workflow.getId(), error.getMessage());
  }

  @Override
  public void onWorkflowInWaitingStatus(WorkflowInstanceView workflow) {
    log.info("{} is waiting for a signal", workflow.getId());
  }

  @Override
  public void onWorkflowTimeout(WorkflowInstanceView workflow) {
    log.info("{} timed out", workflow.getId());
  }

  @Override
  public void onRetryableError(WorkflowInstanceView workflow, Throwable error) {
    log.info("{} hit a retryable error, will retry: {}", workflow.getId(), error.getMessage());
  }

  @Override
  public void onCompensationCompleted(WorkflowInstanceView workflow) {
    log.info("{} compensation completed", workflow.getId());
  }

  @Override
  public void onCompensationError(WorkflowInstanceView workflow, SkipperError error) {
    log.info("{} compensation failed: {}", workflow.getId(), error.getMessage());
  }
}
