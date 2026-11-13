package com.example.trips;

import com.airbnb.skipper.Actions;
import com.airbnb.skipper.Execute;
import com.airbnb.skipper.ExponentialRetryStrategy;
import com.airbnb.skipper.RetryStrategy;
import com.airbnb.skipper.RetryableError;
import java.time.Duration;
import javax.inject.Inject;

/**
 * Inference as checkpointed actions. Once a call returns, its answer is stored with the instance: a restart
 * or a later resume reuses it instead of asking (and paying) the model again, and the workflow sees the same
 * answer every time it replays.
 */
public class PlannerActions extends Actions {
  @Inject TripPlanner planner;

  /** Named on the annotations below; Skipper looks the strategy up by field name on this class. */
  RetryStrategy modelOutage = new ExponentialRetryStrategy(Duration.ofSeconds(2), 5, 2.0, Duration.ofMinutes(1));

  @Execute(retryStrategy = "modelOutage")
  public TripBrief understand(TripRequest request) {
    try {
      return planner.understand(request.request());
    } catch (PlannerUnavailableException e) {
      throw new RetryableError("planner unavailable, will retry", e);
    }
  }

  @Execute(retryStrategy = "modelOutage")
  public Itinerary propose(PlanningContext context) {
    try {
      return planner.propose(context);
    } catch (PlannerUnavailableException e) {
      throw new RetryableError("planner unavailable, will retry", e);
    }
  }
}
