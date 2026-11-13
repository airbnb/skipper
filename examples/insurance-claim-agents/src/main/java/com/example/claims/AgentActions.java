package com.example.claims;

import com.airbnb.skipper.Actions;
import com.airbnb.skipper.Execute;
import com.airbnb.skipper.ExponentialRetryStrategy;
import com.airbnb.skipper.RetryStrategy;
import com.airbnb.skipper.RetryableError;
import java.time.Duration;
import javax.inject.Inject;

/**
 * Every model call, as a checkpointed action. Once a call returns, its answer is stored with the instance: a
 * restart or a later resume reuses it instead of asking (and paying) the model again, and the workflow sees
 * the same answer every time it replays.
 */
public class AgentActions extends Actions {
  @Inject ClaimsModel model;

  /** Named on the annotations below; Skipper looks the strategy up by field name on this class. */
  RetryStrategy modelOutage = new ExponentialRetryStrategy(Duration.ofSeconds(2), 5, 2.0, Duration.ofMinutes(1));

  @Execute(retryStrategy = "modelOutage")
  public IntakeReport intake(String description) {
    try {
      return model.intake(description);
    } catch (ModelUnavailableException e) {
      throw new RetryableError("model unavailable, will retry", e);
    }
  }

  @Execute(retryStrategy = "modelOutage")
  public Opinion assess(Assessment assessment) {
    try {
      SpecialistTask task = assessment.task();
      return model.assess(task.role(), task.file(), assessment.evidence());
    } catch (ModelUnavailableException e) {
      throw new RetryableError("model unavailable, will retry", e);
    }
  }

  @Execute(retryStrategy = "modelOutage")
  public Decision adjudicate(AdjudicationInput input) {
    try {
      return model.adjudicate(input);
    } catch (ModelUnavailableException e) {
      throw new RetryableError("model unavailable, will retry", e);
    }
  }
}
