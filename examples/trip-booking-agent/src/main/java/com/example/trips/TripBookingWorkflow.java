package com.example.trips;

import com.airbnb.skipper.QueryMethod;
import com.airbnb.skipper.SignalMethod;
import com.airbnb.skipper.StateField;
import com.airbnb.skipper.Workflow;
import com.airbnb.skipper.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A trip-booking agent. The model proposes and the workflow disposes:
 *
 * <ol>
 *   <li>inference reads the traveler's request into a destination and dates;
 *   <li>the provider is searched for offers;
 *   <li>inference picks one offer per leg, and the validator refuses anything unknown or over budget, handing
 *       its objections back for another attempt;
 *   <li>the instance parks until the traveler approves the proposal or the offer expires;
 *   <li>each leg is booked. A sold-out leg goes back to inference for a replacement that fits around what is
 *       already booked. If none fits, the workflow fails and Skipper cancels every booking made so far.
 * </ol>
 *
 * Every model call and every booking is a checkpointed action, so a crash at any point resumes from the last
 * completed step with the same answers, never a second booking and never a second bill for the same prompt.
 */
public class TripBookingWorkflow extends Workflow {
  static final int MAX_PLANNING_ATTEMPTS = 3;
  static final Duration APPROVAL_WINDOW = Duration.ofHours(24);

  private final PlannerActions planner = actions(PlannerActions.class);
  private final TravelActions travel = actions(TravelActions.class);

  @StateField TripRequest request;
  @StateField String stage = "NEW";
  @StateField Boolean approved;
  @StateField Itinerary proposal;
  @StateField Journal journal = Journal.empty();
  @StateField TripResult result;

  @WorkflowMethod(returnType = TripResult.class)
  public CompletableFuture<TripResult> bookTrip(TripRequest request) {
    // The method runs again from the top on every resume, replaying recorded action results. The journal is
    // rebuilt by that replay, so start it empty rather than appending to the restored copy.
    journal = Journal.empty();
    this.request = request;

    stage = "UNDERSTANDING";
    TripBrief brief = planner.understand(request);
    stage = "SEARCHING";
    List<Offer> offers = travel.search(brief).offers();

    stage = "PLANNING";
    Itinerary itinerary = plan(new PlanningContext(request.budgetCents(), brief, offers, null, List.of(), List.of()));
    if (itinerary == null) {
      return finish(TripResult.declined("the planner found no itinerary within " + Money.format(request.budgetCents())));
    }

    proposal = itinerary;
    stage = "AWAITING_APPROVAL";
    // Parks the instance (status WAITING) with no thread held. A signal or the deadline wakes it.
    if (!waitUntil(() -> approved != null, APPROVAL_WINDOW)) {
      return finish(TripResult.declined("the offer expired after " + APPROVAL_WINDOW));
    }
    if (!approved) {
      return finish(TripResult.declined("declined by the traveler"));
    }

    stage = "BOOKING";
    List<Leg> booked = new ArrayList<>();
    List<String> confirmations = new ArrayList<>();
    for (Leg leg : Leg.values()) {
      // Ends when the leg is booked or the planner runs out of offers: each pass removes one.
      while (!booked.contains(leg)) {
        Offer offer = ItineraryValidator.find(offers, itinerary.offerId(leg));
        BookingOutcome outcome = travel.book(new BookingRequest(request.tripId(), offer));
        if (outcome.confirmed()) {
          booked.add(leg);
          confirmations.add(outcome.confirmationId());
          continue;
        }
        journal = journal.with(leg + " " + offer.id() + " is sold out, replanning around " + booked);
        offers = without(offers, offer);
        itinerary = plan(new PlanningContext(request.budgetCents(), brief, offers, itinerary, List.copyOf(booked), List.of()));
        if (itinerary == null) {
          // Thrown from workflow code, so non-retryable: the instance fails and Skipper runs
          // TravelActions.cancel for every leg booked above, newest first.
          throw new IllegalStateException("no replacement for " + offer.id() + " fits the budget");
        }
        proposal = itinerary;
      }
    }
    return finish(TripResult.booked(itinerary, confirmations, ItineraryValidator.totalCents(itinerary, offers)));
  }

  /**
   * The inference loop. The planner gets a bounded number of attempts; each refusal is fed back as the reason
   * to do better. Returns null when no attempt passes, and never returns an itinerary the validator refused.
   */
  private Itinerary plan(PlanningContext context) {
    for (int attempt = 1; attempt <= MAX_PLANNING_ATTEMPTS; attempt++) {
      Itinerary candidate = planner.propose(context);
      List<String> violations = ItineraryValidator.violations(candidate, context);
      if (violations.isEmpty()) {
        journal = journal.with("accepted " + describe(candidate) + " at " + Money.format(ItineraryValidator.totalCents(candidate, context.offers())));
        return candidate;
      }
      journal = journal.with("refused " + describe(candidate) + ": " + String.join("; ", violations));
      context = context.withRejections(violations);
    }
    return null;
  }

  private static String describe(Itinerary itinerary) {
    return itinerary == null
        ? "nothing"
        : itinerary.flightOfferId() + " + " + itinerary.hotelOfferId() + " + " + itinerary.carOfferId();
  }

  private static List<Offer> without(List<Offer> offers, Offer soldOut) {
    List<Offer> remaining = new ArrayList<>(offers);
    remaining.remove(soldOut);
    return remaining;
  }

  private CompletableFuture<TripResult> finish(TripResult outcome) {
    stage = outcome.status();
    result = outcome;
    return CompletableFuture.completedFuture(outcome);
  }

  /** The traveler's answer to the proposal, delivered while the instance is parked. */
  @SignalMethod(persist = true)
  public void decide(boolean decision) {
    this.approved = decision;
  }

  /** What the traveler asked for, so a client holding only the instance id can show it. */
  @QueryMethod
  public TripRequest request() {
    return request;
  }

  @QueryMethod
  public String stage() {
    return stage;
  }

  /** The itinerary awaiting approval, or the latest one once booking has started. */
  @QueryMethod
  public Itinerary proposal() {
    return proposal;
  }

  @QueryMethod
  public Journal journal() {
    return journal;
  }

  /** Null until the workflow reaches a terminal decision. Lets callers read the outcome without holding a future. */
  @QueryMethod
  public TripResult result() {
    return result;
  }
}
