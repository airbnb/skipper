package com.example.claims;

import com.airbnb.skipper.Actions;
import com.airbnb.skipper.Execute;
import com.airbnb.skipper.FixedRetryStrategy;
import com.airbnb.skipper.IWorkflowFactory;
import com.airbnb.skipper.RetryStrategy;
import com.airbnb.skipper.RetryableError;
import java.time.Duration;
import javax.inject.Inject;

/** How a specialist hands its opinion back: a signal to the claim workflow that started it. */
public class ReportActions extends Actions {
  @Inject IWorkflowFactory workflows;

  RetryStrategy claimBusy = new FixedRetryStrategy(Duration.ofSeconds(1), 10);

  /**
   * Three specialists finish at about the same time and signal the same claim. Skipper applies signals to an
   * instance one at a time, so a report can find the claim busy; delivering again is safe because the claim
   * keeps one opinion per specialist.
   */
  @Execute(retryStrategy = "claimBusy")
  public void deliver(OpinionReport report) {
    try {
      workflows.invoke(ClaimWorkflow.class, report.claimId()).opinion(report.opinion());
    } catch (RuntimeException e) {
      throw new RetryableError("the claim could not take the report, will retry", e);
    }
  }
}
