package com.example.trips;

/**
 * The inference half of the agent. Implementations may be wrong, slow, or unavailable; nothing they return is
 * acted on until deterministic code has checked it.
 */
public interface TripPlanner {
  /** Reads a free-text request into a destination and dates. */
  TripBrief understand(String request) throws PlannerUnavailableException;

  /** Chooses one offer per leg. Called again with the validator's objections when a proposal is refused. */
  Itinerary propose(PlanningContext context) throws PlannerUnavailableException;
}
