package com.airbnb.skipper.testutils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.airbnb.skipper.Actions;
import com.airbnb.skipper.Execute;
import com.airbnb.skipper.FixedRetryStrategy;
import com.airbnb.skipper.RetryStrategy;
import com.airbnb.skipper.RetryableError;
import com.airbnb.skipper.Workflow;
import com.airbnb.skipper.WorkflowMethod;
import com.airbnb.skipper.api.WorkflowInstanceStatusView;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import javax.inject.Inject;
import org.junit.jupiter.api.Test;

/** The same harness from Java: plain fields, {@code workflowBuilder(Class)}, {@code helper}. */
public class WorkflowTestJavaTest extends WorkflowTest {
  @Bind UnaryOperator<String> shout = s -> s.toUpperCase() + "!";

  @Test
  public void runsAWorkflowFromJava() throws Exception {
    ShoutWorkflow workflow = workflowBuilder(ShoutWorkflow.class).build();

    assertEquals("HELLO!", workflow.shout("hello").get());
    assertEquals(
        WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().getStatus());
    assertEquals(workflowId, helper.currentView().getId());
  }

  @Test
  public void retriesAreTimersOnTheTestClock() throws Exception {
    failuresRemaining.set(2);

    workflowBuilder(FlakyWorkflow.class).build().run("x");

    // helper.waitForWorkflowToComplete() would never return: advance the clock instead.
    assertEquals(
        WorkflowInstanceStatusView.COMPLETED,
        helper.fastForwardUntilWorkflowCompletes().getStatus());
    assertEquals("ok", workflow(FlakyWorkflow.class).run("x").get());
  }

  @Bind AtomicInteger failuresRemaining = new AtomicInteger();

  public static class FlakyActions extends Actions {
    @Inject AtomicInteger failuresRemaining;

    RetryStrategy retries = new FixedRetryStrategy(Duration.ofMillis(200), 3);

    @Execute(retryStrategy = "retries")
    public String attempt(String input) {
      if (failuresRemaining.getAndDecrement() > 0) {
        throw new RetryableError("flaky", new IllegalStateException("503"));
      }
      return "ok";
    }
  }

  public static class FlakyWorkflow extends Workflow {
    private final FlakyActions actions = actions(FlakyActions.class);

    @WorkflowMethod(returnType = String.class)
    public CompletableFuture<String> run(String input) {
      return CompletableFuture.completedFuture(actions.attempt(input));
    }
  }

  public static class ShoutActions extends Actions {
    @Inject UnaryOperator<String> shout;

    @Execute
    public String apply(String input) {
      return shout.apply(input);
    }
  }

  public static class ShoutWorkflow extends Workflow {
    private final ShoutActions actions = actions(ShoutActions.class);

    @WorkflowMethod(returnType = String.class)
    public CompletableFuture<String> shout(String input) {
      return CompletableFuture.completedFuture(actions.apply(input));
    }
  }
}
