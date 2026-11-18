package com.example.claims;

import com.airbnb.skipper.Actions;
import com.airbnb.skipper.CheckpointMode;
import com.airbnb.skipper.Execute;
import com.airbnb.skipper.ExponentialRetryStrategy;
import com.airbnb.skipper.RetryStrategy;
import com.airbnb.skipper.RetryableError;
import java.time.Duration;
import javax.inject.Inject;

/**
 * Every model call, as an action saved the moment it returns ({@code IMMEDIATE_CHECKPOINT}). A restart or
 * a later resume reuses the saved answer instead of asking (and paying) the model again, and the workflow
 * sees the same answer every time it replays.
 *
 * <p>Skipper's default saves results only when the run next waits or ends, which costs fewer writes. That
 * is the wrong trade for inference: a crash in between would ask again and could get a different answer
 * than the one a later step already acted on. A call that was still in flight when the process died has
 * nothing saved and is made again.
 */
public class AgentActions extends Actions {
  @Inject ClaimsModel model;

  /** Named on the annotations below; Skipper looks the strategy up by field name on this class. */
  RetryStrategy modelOutage = new ExponentialRetryStrategy(Duration.ofSeconds(2), 5, 2.0, Duration.ofMinutes(1));

  @Execute(retryStrategy = "modelOutage", checkpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT)
  public IntakeReport intake(String description) {
    try {
      return model.intake(description);
    } catch (ModelUnavailableException e) {
      throw new RetryableError("model unavailable, will retry", e);
    }
  }

  @Execute(retryStrategy = "modelOutage", checkpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT)
  public Opinion assess(Assessment assessment) {
    try {
      SpecialistTask task = assessment.task();
      return model.assess(task.role(), task.file(), assessment.evidence());
    } catch (ModelUnavailableException e) {
      throw new RetryableError("model unavailable, will retry", e);
    }
  }

  @Execute(retryStrategy = "modelOutage", checkpointMode = CheckpointMode.IMMEDIATE_CHECKPOINT)
  public Decision adjudicate(AdjudicationInput input) {
    try {
      return model.adjudicate(input);
    } catch (ModelUnavailableException e) {
      throw new RetryableError("model unavailable, will retry", e);
    }
  }
}
