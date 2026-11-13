package com.example.trips;

/** One offer per leg, chosen by the planner, with its reason. This is the shape the model must answer in. */
public record Itinerary(String flightOfferId, String hotelOfferId, String carOfferId, String rationale) {
  public String offerId(Leg leg) {
    switch (leg) {
      case FLIGHT:
        return flightOfferId;
      case HOTEL:
        return hotelOfferId;
      default:
        return carOfferId;
    }
  }
}
