package com.airbnb.skipper.testutils;

import com.airbnb.skipper.RawActionInvocation;
import com.airbnb.skipper.RawRequestContextMiddleware;
import com.airbnb.skipper.WorkflowOptions;
import com.airbnb.skipper.util.ExtraRequestData;

/**
 * OSS-safe {@link RawRequestContextMiddleware} for Skipper tests.
 *
 * <p>Installs the invocation's {@link TestRequestContext} on the per-thread holder immediately
 * before the workflow / action / signal / compensation body runs, and clears it afterwards in the
 * engine's synchronous {@code finally} slot. This lets propagation tests assert that the context
 * persisted with a workflow is visible on the executor thread after a persistence reload — the
 * generic, auth-free equivalent of a host request-context middleware.
 */
public class TestRequestContextMiddleware implements RawRequestContextMiddleware {

  @Override
  public Object rawOnCreate(String workflowId, Object ctx) {
    // Pass-through: Skipper persists whatever context the caller supplied at workflow creation.
    return ctx;
  }

  @Override
  public Object rawBeforeExecution(RawActionInvocation invocation) {
    Object ctx = invocation.getRequestContext();
    if (ctx instanceof TestRequestContext) {
      TestRequestContext.setCurrentRequestContext((TestRequestContext) ctx);
    } else {
      TestRequestContext.setCurrentRequestContext(null);
    }
    return ctx;
  }

  @Override
  public void rawAfterExecution(RawActionInvocation invocation) {
    TestRequestContext.clearAllRequestContext();
  }

  @Override
  public void rawStoreWorkflowCreationMetadata(
      WorkflowOptions options, ExtraRequestData extraRequestData) {
    // No-op: the OSS test middleware carries no creation-time metadata.
  }
}
