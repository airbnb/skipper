package com.example.trips;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The default planner: no model, no network, the same answer every time, so the example runs anywhere and its
 * tests are exact. It behaves the way a model plausibly does. Its first proposal goes for the best-rated offer
 * on every leg and ignores the budget; once the validator objects, it finds the best-rated combination that fits.
 */
public class ScriptedPlanner implements TripPlanner {
  private static final Pattern CITY = Pattern.compile("\\b(?:in|to) ([A-Z][a-z]+)");
  private static final Pattern NIGHTS = Pattern.compile("(\\d+) nights?");
  private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

  @Override
  public TripBrief understand(String request) {
    Matcher city = CITY.matcher(request);
    Matcher nights = NIGHTS.matcher(request);
    Matcher date = DATE.matcher(request);
    return new TripBrief(
        city.find() ? city.group(1) : "Lisbon",
        date.find() ? date.group() : "2027-03-12",
        nights.find() ? Integer.parseInt(nights.group(1)) : 3);
  }

  @Override
  public Itinerary propose(PlanningContext context) {
    if (context.rejections().isEmpty()) {
      return new Itinerary(
          firstChoice(context, Leg.FLIGHT), firstChoice(context, Leg.HOTEL), firstChoice(context, Leg.CAR),
          "the best-rated option on every leg");
    }
    Itinerary best = null;
    int bestRating = -1;
    for (Offer flight : choices(context, Leg.FLIGHT)) {
      for (Offer hotel : choices(context, Leg.HOTEL)) {
        for (Offer car : choices(context, Leg.CAR)) {
          long total = flight.priceCents() + hotel.priceCents() + car.priceCents();
          int rating = flight.rating() + hotel.rating() + car.rating();
          if (total <= context.budgetCents() && rating > bestRating) {
            bestRating = rating;
            best = new Itinerary(flight.id(), hotel.id(), car.id(), "the best-rated combination within budget");
          }
        }
      }
    }
    // Nothing fits: answer with the cheapest anyway, as a model would, and let the validator refuse it.
    return best != null ? best : new Itinerary(
        cheapest(context, Leg.FLIGHT), cheapest(context, Leg.HOTEL), cheapest(context, Leg.CAR),
        "the cheapest available, though it is over budget");
  }

  /** What may be chosen for a leg: only the booked offer once it is booked, otherwise everything on sale. */
  private static List<Offer> choices(PlanningContext context, Leg leg) {
    List<Offer> choices = new ArrayList<>();
    for (Offer offer : context.offers()) {
      boolean locked = context.booked().contains(leg);
      if (offer.leg() == leg && (!locked || offer.id().equals(context.current().offerId(leg)))) {
        choices.add(offer);
      }
    }
    return choices;
  }

  /** Keeps the current pick while it is still on sale, otherwise takes the best-rated offer. */
  private static String firstChoice(PlanningContext context, Leg leg) {
    List<Offer> choices = choices(context, leg);
    if (context.current() != null) {
      String kept = context.current().offerId(leg);
      if (choices.stream().anyMatch(o -> o.id().equals(kept))) return kept;
    }
    return choices.stream().max(Comparator.comparingInt(Offer::rating)).map(Offer::id).orElse("none");
  }

  private static String cheapest(PlanningContext context, Leg leg) {
    return choices(context, leg).stream().min(Comparator.comparingLong(Offer::priceCents)).map(Offer::id).orElse("none");
  }
}
