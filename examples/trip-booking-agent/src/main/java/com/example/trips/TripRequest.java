package com.example.trips;

/**
 * Workflow input: what the traveler typed, plus the one number the model is never trusted with. The budget is
 * set by the caller and enforced by {@link ItineraryValidator}, whatever the model proposes.
 */
public record TripRequest(String tripId, String traveler, String request, long budgetCents) {}
