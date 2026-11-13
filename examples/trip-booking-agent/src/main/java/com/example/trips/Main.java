package com.example.trips;

import com.airbnb.skipper.IWorkflowFactory;
import com.airbnb.skipper.api.WorkflowInstanceStatusView;
import com.airbnb.skipper.api.WorkflowInstanceView;
import com.airbnb.skipper.factory.SkipperRuntime;
import com.sun.net.httpserver.HttpServer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Starts the agent on a file-backed SQLite store and serves two pages: a playground for starting trips and
 * answering their proposals, and Skipper's admin UI for looking inside each instance.
 *
 * <p>With {@code --demo} it instead runs three scripted trips through {@link TripBookingWorkflow} and exits:
 *
 * <ol>
 *   <li>one that is planned, approved and booked;
 *   <li>one whose hotel sells out after approval, so the planner picks another around the booked flight;
 *   <li>one where no replacement fits the budget, so the flight that was already booked is cancelled.
 * </ol>
 *
 * With ANTHROPIC_API_KEY set the planner is Claude; otherwise it is the scripted one.
 */
public final class Main {
  private static final String REQUEST = "A long weekend in Lisbon for two, 3 nights from 2027-03-12, somewhere central";
  private static final long BUDGET_CENTS = 150_000;

  public static void main(String[] args) throws Exception {
    List<String> arguments = new ArrayList<>(List.of(args));
    boolean demo = arguments.remove("--demo");
    String dbPath = arguments.isEmpty() ? "trips.db" : arguments.get(0);
    int port = Integer.parseInt(System.getenv().getOrDefault("TRIP_AGENT_PORT", "8090"));
    boolean useModel = System.getenv("ANTHROPIC_API_KEY") != null;
    String model = System.getenv().getOrDefault("TRIP_AGENT_MODEL", "claude-opus-5-5");
    TripPlanner planner = useModel ? new ClaudePlanner(model) : new ScriptedPlanner();
    InMemoryTravelProvider provider = new InMemoryTravelProvider();
    String plannerName = useModel ? model : "scripted (set ANTHROPIC_API_KEY to use Claude)";
    System.out.println("planner: " + plannerName);

    SkipperRuntime runtime = SkipperSetup.start(dbPath, planner, provider);
    IWorkflowFactory factory = runtime.getWorkflowFactory().get();
    HttpServer web = WebServer.start(
        port, new TripResource(factory, provider, plannerName), runtime.getAdminResource().get());
    try {
      if (demo) {
        runDemo(factory, provider);
      } else {
        System.out.println("playground: " + WebServer.url(web));
        System.out.println("admin UI:   " + WebServer.url(web) + "skipper/admin/");
        System.out.println("Press Ctrl-C to stop. Trips are kept in " + dbPath + " and are still there on the next start.");
        Thread.currentThread().join();
      }
    } finally {
      web.stop(0);
      runtime.getSkipperSchedulerManager().get().stop();
    }
  }

  private static void runDemo(IWorkflowFactory factory, InMemoryTravelProvider provider) throws InterruptedException {
    String run = UUID.randomUUID().toString().substring(0, 8);
    // 1. Planned, approved, booked.
    String booked = propose(factory, "trip-" + run + "-booked");
    approve(factory, booked);
    report(factory, booked, WorkflowInstanceStatusView.COMPLETED);

    // 2. The hotel in the approved proposal sells out before it can be booked. The flight is already paid
    //    for, so the planner has to find another hotel that still fits around it.
    provider.restock();
    String replanned = propose(factory, "trip-" + run + "-replanned");
    provider.sellOut(factory.invoke(TripBookingWorkflow.class, replanned).proposal().hotelOfferId());
    approve(factory, replanned);
    report(factory, replanned, WorkflowInstanceStatusView.COMPLETED);

    // 3. Every hotel the budget allows sells out. No replacement fits, the workflow fails, and Skipper
    //    cancels the flight it had already booked.
    provider.restock();
    String abandoned = propose(factory, "trip-" + run + "-abandoned");
    provider.sellOut("HT-BOUTIQUE", "HT-HOSTEL");
    approve(factory, abandoned);
    report(factory, abandoned, WorkflowInstanceStatusView.COMPENSATION_COMPLETED);

    System.out.println("\nprovider ledger: " + provider.ledger());
  }

  /** Starts a trip and waits for it to park with a proposal. The future is not held: a parked instance does not complete it. */
  private static String propose(IWorkflowFactory factory, String id) throws InterruptedException {
    factory.builder(TripBookingWorkflow.class, id).build().bookTrip(new TripRequest(id, "alice", REQUEST, BUDGET_CENTS));
    awaitStatus(factory, id, EnumSet.of(WorkflowInstanceStatusView.WAITING), Duration.ofMinutes(3));
    TripBookingWorkflow trip = factory.invoke(TripBookingWorkflow.class, id);
    System.out.println("\n" + id + " -> WAITING, stage=" + trip.stage());
    Itinerary proposal = trip.proposal();
    System.out.println("  proposal: " + proposal.flightOfferId() + " + " + proposal.hotelOfferId() + " + "
        + proposal.carOfferId() + " (" + proposal.rationale() + ")");
    return id;
  }

  /** Any process holding the same store can deliver the traveler's decision; here it is the same one. */
  private static void approve(IWorkflowFactory factory, String id) {
    factory.invoke(TripBookingWorkflow.class, id).decide(true);
  }

  private static void report(IWorkflowFactory factory, String id, WorkflowInstanceStatusView expected)
      throws InterruptedException {
    WorkflowInstanceView view = awaitStatus(factory, id, EnumSet.of(expected), Duration.ofMinutes(3));
    TripBookingWorkflow trip = factory.invoke(TripBookingWorkflow.class, id);
    TripResult result = trip.result();
    System.out.println(id + " -> " + view.getStatus()
        + (result == null ? "" : ", " + result.status() + " " + result.confirmations() + " for " + Money.format(result.totalCents())));
    trip.journal().lines().forEach(line -> System.out.println("  " + line));
  }

  /** Polls the persisted status until it is one of the expected ones. */
  static WorkflowInstanceView awaitStatus(IWorkflowFactory factory, String id,
      Set<WorkflowInstanceStatusView> expected, Duration timeout) throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (true) {
      WorkflowInstanceView view = factory.invoke(TripBookingWorkflow.class, id).getWorkflowInstanceView();
      if (expected.contains(view.getStatus())) return view;
      if (Instant.now().isAfter(deadline)) {
        throw new IllegalStateException(id + " is " + view.getStatus() + " after " + timeout + ", expected " + expected);
      }
      Thread.sleep(200);
    }
  }
}
