package com.airbnb.skipper.testutils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.airbnb.skipper.Actions;
import com.airbnb.skipper.Execute;
import com.airbnb.skipper.Workflow;
import com.airbnb.skipper.WorkflowMethod;
import com.airbnb.skipper.api.WorkflowInstanceStatusView;
import java.util.concurrent.CompletableFuture;
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
