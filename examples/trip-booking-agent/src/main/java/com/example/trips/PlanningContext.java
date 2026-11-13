package com.example.trips;

import java.util.List;

/**
 * Everything the planner may use for one proposal. The same shape serves the first plan and every replan:
 *
 * <ul>
 *   <li>{@code offers} is what can still be bought; a sold-out offer is simply no longer in it;
 *   <li>{@code current} is the itinerary being revised, null for the first plan;
 *   <li>{@code booked} lists the legs of {@code current} that are already paid for and cannot change;
 *   <li>{@code rejections} is why the validator refused the previous attempt, empty on the first one.
 * </ul>
 */
public record PlanningContext(
    long budgetCents,
    TripBrief brief,
    List<Offer> offers,
    Itinerary current,
    List<Leg> booked,
    List<String> rejections) {

  public PlanningContext withRejections(List<String> reasons) {
    return new PlanningContext(budgetCents, brief, offers, current, booked, reasons);
  }
}
