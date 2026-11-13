package com.example.trips;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The guardrail between inference and side effects. Pure and deterministic, so the workflow can call it
 * directly and replay it safely. An itinerary is bookable only when this returns no violations.
 */
public final class ItineraryValidator {
  private ItineraryValidator() {}

  public static List<String> violations(Itinerary itinerary, PlanningContext context) {
    List<String> violations = new ArrayList<>();
    if (itinerary == null) {
      return List.of("no itinerary was returned");
    }
    for (Leg leg : Leg.values()) {
      String id = itinerary.offerId(leg);
      Offer offer = find(context.offers(), id);
      if (offer == null || offer.leg() != leg) {
        violations.add(leg + ": '" + id + "' is not one of the available " + leg + " offers");
      } else if (context.booked().contains(leg) && !Objects.equals(id, context.current().offerId(leg))) {
        violations.add(leg + " is already booked as " + context.current().offerId(leg) + " and cannot change");
      }
    }
    if (violations.isEmpty()) {
      long total = totalCents(itinerary, context.offers());
      if (total > context.budgetCents()) {
        violations.add(
            "total " + Money.format(total) + " exceeds the budget " + Money.format(context.budgetCents())
                + " by " + Money.format(total - context.budgetCents()));
      }
    }
    return violations;
  }

  public static long totalCents(Itinerary itinerary, List<Offer> offers) {
    long total = 0;
    for (Leg leg : Leg.values()) {
      total += find(offers, itinerary.offerId(leg)).priceCents();
    }
    return total;
  }

  public static Offer find(List<Offer> offers, String id) {
    return offers.stream().filter(o -> o.id().equals(id)).findFirst().orElse(null);
  }
}
