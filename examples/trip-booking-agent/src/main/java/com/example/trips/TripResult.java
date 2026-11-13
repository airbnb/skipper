package com.example.trips;

import java.util.List;

public record TripResult(String status, Itinerary itinerary, List<String> confirmations, long totalCents, String note) {
  public static TripResult booked(Itinerary itinerary, List<String> confirmations, long totalCents) {
    return new TripResult("BOOKED", itinerary, confirmations, totalCents, itinerary.rationale());
  }

  public static TripResult declined(String why) {
    return new TripResult("DECLINED", null, List.of(), 0, why);
  }
}
