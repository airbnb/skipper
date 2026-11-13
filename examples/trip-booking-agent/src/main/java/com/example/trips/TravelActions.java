package com.example.trips;

import com.airbnb.skipper.Actions;
import com.airbnb.skipper.Compensate;
import com.airbnb.skipper.Execute;
import com.airbnb.skipper.FixedRetryStrategy;
import com.airbnb.skipper.RetryStrategy;
import com.airbnb.skipper.RetryableError;
import java.time.Duration;
import javax.inject.Inject;

/** The side effects. Nothing here involves the model; it only executes what the validator let through. */
public class TravelActions extends Actions {
  @Inject TravelProvider provider;

  RetryStrategy providerOutage = new FixedRetryStrategy(Duration.ofMinutes(1), 3);

  @Execute
  public SearchResults search(TripBrief brief) {
    return new SearchResults(provider.search(brief));
  }

  @Execute(retryStrategy = "providerOutage")
  public BookingOutcome book(BookingRequest request) {
    try {
      return provider.book(request.tripId(), request.offer());
    } catch (ProviderUnavailableException e) {
      throw new RetryableError("provider unavailable, will retry", e);
    }
  }

  /** Runs for every completed {@code book} when the workflow fails, newest first. A sold-out answer booked nothing. */
  @Compensate(forExecute = "book")
  public void cancel(BookingRequest request, BookingOutcome outcome) {
    if (outcome.confirmed()) {
      provider.cancel(outcome.confirmationId());
    }
  }
}
