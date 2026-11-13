package com.example.trips;

import java.util.List;

/** The deterministic half's view of the outside world: the system that actually sells and cancels things. */
public interface TravelProvider {
  List<Offer> search(TripBrief brief);

  BookingOutcome book(String tripId, Offer offer) throws ProviderUnavailableException;

  void cancel(String confirmationId);
}
