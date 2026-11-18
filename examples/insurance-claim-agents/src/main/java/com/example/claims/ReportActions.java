package com.example.claims;

import com.airbnb.skipper.Actions;
import com.airbnb.skipper.Execute;
import com.airbnb.skipper.FixedRetryStrategy;
import com.airbnb.skipper.IWorkflowFactory;
import com.airbnb.skipper.RetryStrategy;
import com.airbnb.skipper.TerminalWorkflowError;
import java.time.Duration;
import javax.inject.Inject;

/** How a specialist hands its opinion back: a signal to the claim workflow that started it. */
public class ReportActions extends Actions {
  @Inject IWorkflowFactory workflows;

  RetryStrategy claimBusy = new FixedRetryStrategy(Duration.ofSeconds(1), 10);

  /**
   * A signal can fail, so the report retries. Three specialists finish at about the same time and signal
   * the same claim; Skipper applies signals to an instance one at a time and the losers get an
   * OptimisticLockingError. The signal call retries that itself a few times, and because the error is a
   * RetryableError, letting it out of this action retries the whole delivery on the strategy named here.
   * Delivering again is safe because the claim keeps one opinion per specialist.
   *
   * <p>A claim that has already ended takes no signals. Its specialist has nobody left to report to, which
   * is not a failure of the specialist, so that one case is let go.
   */
  @Execute(retryStrategy = "claimBusy")
  public void deliver(OpinionReport report) {
    try {
      workflows.invoke(ClaimWorkflow.class, report.claimId()).opinion(report.opinion());
    } catch (TerminalWorkflowError claimHasEnded) {
      // Nothing to deliver to. The opinion stays in this specialist's own timeline.
    }
  }
}
