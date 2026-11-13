package com.example.trips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.airbnb.skipper.api.WorkflowInstanceStatusView;
import com.airbnb.skipper.testutils.Bind;
import com.airbnb.skipper.testutils.WorkflowTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Each test runs on its own in-memory Skipper runtime. The @Bind fields are handed to the actions' @Inject
 * fields, so the model is replaced by a planner the test controls and no test needs a network or an API key.
 *
 * <p>The bindings are captured before the test body runs: mutate a fake in place, do not reassign the field.
 */
class TripBookingWorkflowTest extends WorkflowTest {
  @Bind(to = TripPlanner.class) FakePlanner planner = new FakePlanner();
  @Bind(to = TravelProvider.class) InMemoryTravelProvider provider = new InMemoryTravelProvider();

  private static final TripRequest TRIP = new TripRequest("t-1", "alice", "3 nights in Lisbon from 2027-03-12", 150_000);

  @Test
  void anOverBudgetProposalIsRefusedAndTheNextOneWaitsForApproval() {
    startAndWaitForProposal();

    TripBookingWorkflow trip = workflow(TripBookingWorkflow.class);
    assertEquals("AWAITING_APPROVAL", trip.stage());
    assertEquals(new Itinerary("FL-DIRECT", "HT-BOUTIQUE", "CAR-SUV", "the best-rated combination within budget"), trip.proposal());
    assertEquals(
        List.of(
            "refused FL-DIRECT + HT-PALACE + CAR-SUV: total $1,910 exceeds the budget $1,500 by $410",
            "accepted FL-DIRECT + HT-BOUTIQUE + CAR-SUV at $1,490"),
        trip.journal().lines());
    assertTrue(provider.ledger().isEmpty(), "nothing is booked before the traveler approves");
  }

  @Test
  void anApprovedTripIsBookedLegByLeg() {
    startAndWaitForProposal();

    workflow(TripBookingWorkflow.class).decide(true);

    assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().getStatus());
    TripResult result = workflow(TripBookingWorkflow.class).result();
    assertEquals("BOOKED", result.status());
    assertEquals(149_000, result.totalCents());
    assertEquals(
        List.of("BOOK cnf-1 FL-DIRECT $620", "BOOK cnf-2 HT-BOUTIQUE $540", "BOOK cnf-3 CAR-SUV $330"),
        provider.ledger());
  }

  @Test
  void theModelIsNotAskedAgainWhenTheWorkflowResumes() {
    startAndWaitForProposal();
    int callsBeforeApproval = planner.calls();

    // The signal wakes the instance and the workflow method replays from its first line. The planner
    // actions are checkpointed, so the replay reads their recorded answers instead of calling the planner.
    workflow(TripBookingWorkflow.class).decide(true);
    helper.waitForWorkflowToComplete();

    assertEquals(3, callsBeforeApproval, "one call to understand the request and two proposals");
    assertEquals(callsBeforeApproval, planner.calls());
  }

  @Test
  void aDeclinedProposalBooksNothing() {
    startAndWaitForProposal();

    workflow(TripBookingWorkflow.class).decide(false);

    helper.waitForWorkflowToComplete();
    assertEquals("DECLINED", workflow(TripBookingWorkflow.class).result().status());
    assertTrue(provider.ledger().isEmpty());
  }

  @Test
  void theProposalExpires() {
    startAndWaitForProposal();

    // The test clock ticks with real time; jump it past the approval window instead of waiting.
    clock.fastForward(TripBookingWorkflow.APPROVAL_WINDOW.plusMinutes(1));

    assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().getStatus());
    assertEquals("DECLINED", workflow(TripBookingWorkflow.class).result().status());
    assertTrue(provider.ledger().isEmpty());
  }

  @Test
  void aSoldOutHotelIsReplacedAroundTheBookedFlight() {
    startAndWaitForProposal();
    provider.sellOut("HT-BOUTIQUE"); // taken by someone else while the proposal awaited approval

    workflow(TripBookingWorkflow.class).decide(true);

    assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().getStatus());
    TripResult result = workflow(TripBookingWorkflow.class).result();
    assertEquals("FL-DIRECT", result.itinerary().flightOfferId(), "the booked flight is kept");
    assertEquals("HT-HOSTEL", result.itinerary().hotelOfferId());
    assertEquals(122_000, result.totalCents());
    assertEquals(
        List.of("BOOK cnf-1 FL-DIRECT $620", "BOOK cnf-2 HT-HOSTEL $270", "BOOK cnf-3 CAR-SUV $330"),
        provider.ledger());
  }

  @Test
  void whenNoReplacementFitsTheBudgetTheBookedFlightIsCancelled() {
    startAndWaitForProposal();
    provider.sellOut("HT-BOUTIQUE", "HT-HOSTEL"); // only the palace is left, and it does not fit

    workflow(TripBookingWorkflow.class).decide(true);

    // The instance passes through ERROR on its way here; wait for the status that says the undo is done.
    helper.fastForwardUntilWorkflowReachesStatus(WorkflowInstanceStatusView.COMPENSATION_COMPLETED, Duration.ofSeconds(30));
    assertEquals(List.of("BOOK cnf-1 FL-DIRECT $620", "CANCEL cnf-1"), provider.ledger());
  }

  @Test
  void anInventedOfferNeverReachesTheProvider() {
    planner.alwaysPropose(new Itinerary("FL-DIRECT", "HT-IMAGINARY", "CAR-COMPACT", "a hotel that does not exist"));

    workflowBuilder(TripBookingWorkflow.class).build().bookTrip(TRIP);

    assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().getStatus());
    TripBookingWorkflow trip = workflow(TripBookingWorkflow.class);
    assertEquals("DECLINED", trip.result().status());
    assertEquals(TripBookingWorkflow.MAX_PLANNING_ATTEMPTS, trip.journal().lines().size());
    assertTrue(trip.journal().lines().get(0).contains("'HT-IMAGINARY' is not one of the available HOTEL offers"));
    assertEquals(0, provider.bookCalls());
  }

  @Test
  void aModelOutageIsRetriedWithBackoff() {
    planner.failNext(2); // two outages, then an answer; the action's strategy backs off between attempts
    workflowBuilder(TripBookingWorkflow.class).build().bookTrip(TRIP);

    // Each retry is a timer on the test clock; step the clock until the instance parks with a proposal.
    helper.fastForwardUntilWorkflowReachesStatus(WorkflowInstanceStatusView.WAITING, Duration.ofSeconds(5));

    assertEquals("AWAITING_APPROVAL", workflow(TripBookingWorkflow.class).stage());
    assertEquals(5, planner.calls(), "two failed attempts, then the three calls of a normal plan");
  }

  @Test
  void aProviderOutageIsRetried() {
    startAndWaitForProposal();
    provider.failNext(2);

    workflow(TripBookingWorkflow.class).decide(true);

    assertEquals(
        WorkflowInstanceStatusView.COMPLETED,
        helper.fastForwardUntilWorkflowCompletes(Duration.ofMinutes(1)).getStatus());
    assertEquals(5, provider.bookCalls(), "two 503s on the flight, then one call per leg");
    assertEquals(3, provider.ledger().size());
  }

  @Test
  void theValidatorHoldsABookedLegInPlace() {
    List<Offer> offers = provider.search(null);
    Itinerary current = new Itinerary("FL-DIRECT", "HT-HOSTEL", "CAR-COMPACT", "");
    PlanningContext context = new PlanningContext(150_000, null, offers, current, List.of(Leg.FLIGHT), List.of());

    Itinerary swapsTheFlight = new Itinerary("FL-ONESTOP", "HT-HOSTEL", "CAR-COMPACT", "");

    assertEquals(
        List.of("FLIGHT is already booked as FL-DIRECT and cannot change"),
        ItineraryValidator.violations(swapsTheFlight, context));
    assertEquals(List.of(), ItineraryValidator.violations(current, context));
    assertNotEquals(List.of(), ItineraryValidator.violations(new Itinerary("HT-HOSTEL", "FL-DIRECT", "CAR-SUV", ""), context));
  }

  private void startAndWaitForProposal() {
    workflowBuilder(TripBookingWorkflow.class).build().bookTrip(TRIP);
    helper.expectWorkflowToWait();
  }

  /** The scripted planner, plus the ways a model misbehaves: outages and answers that name things that do not exist. */
  static class FakePlanner implements TripPlanner {
    private final ScriptedPlanner scripted = new ScriptedPlanner();
    private int calls;
    private int failuresLeft;
    private Itinerary fixedAnswer;

    synchronized void failNext(int times) {
      failuresLeft = times;
    }

    synchronized void alwaysPropose(Itinerary answer) {
      fixedAnswer = answer;
    }

    synchronized int calls() {
      return calls;
    }

    @Override
    public synchronized TripBrief understand(String request) throws PlannerUnavailableException {
      countOrFail();
      return scripted.understand(request);
    }

    @Override
    public synchronized Itinerary propose(PlanningContext context) throws PlannerUnavailableException {
      countOrFail();
      return fixedAnswer != null ? fixedAnswer : scripted.propose(context);
    }

    private void countOrFail() throws PlannerUnavailableException {
      calls++;
      if (failuresLeft > 0) {
        failuresLeft--;
        throw new PlannerUnavailableException("529 overloaded", null);
      }
    }
  }
}
