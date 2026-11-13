package com.example.claims;

import com.airbnb.skipper.IWorkflowFactory;
import com.airbnb.skipper.api.WorkflowInstanceStatusView;
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
 * Starts the claim agents on a file-backed SQLite store and serves two pages: one for making claims and
 * acting as the adjuster, and Skipper's admin UI for looking inside each instance.
 *
 * <p>With {@code --demo} it instead runs the sample claims from {@link Samples} without a browser, answering
 * as the claimant and the adjuster where the workflow waits for one, and exits.
 *
 * <p>With ANTHROPIC_API_KEY set the agents are Claude; otherwise they are scripted.
 */
public final class Main {
  private static final Set<WorkflowInstanceStatusView> ENDED = EnumSet.of(
      WorkflowInstanceStatusView.COMPLETED,
      WorkflowInstanceStatusView.COMPENSATION_COMPLETED,
      WorkflowInstanceStatusView.COMPENSATION_ERROR);

  public static void main(String[] args) throws Exception {
    List<String> arguments = new ArrayList<>(List.of(args));
    boolean demo = arguments.remove("--demo");
    String dbPath = arguments.isEmpty() ? "claims.db" : arguments.get(0);
    int port = Integer.parseInt(System.getenv().getOrDefault("CLAIMS_PORT", "8090"));
    boolean useModel = System.getenv("ANTHROPIC_API_KEY") != null;
    String modelId = System.getenv().getOrDefault("CLAIMS_MODEL", "claude-opus-5-5");
    ClaimsModel model = useModel ? new ClaudeModel(modelId) : new ScriptedModel();
    String agents = useModel ? modelId : "scripted (set ANTHROPIC_API_KEY to use Claude)";
    InMemoryClaimRecords records = new InMemoryClaimRecords();
    InMemoryPayments payments = new InMemoryPayments();
    System.out.println("agents: " + agents);

    SkipperRuntime runtime = SkipperSetup.start(dbPath, model, records, payments);
    IWorkflowFactory factory = runtime.getWorkflowFactory().get();
    HttpServer web = WebServer.start(port, new ClaimResource(factory, records, agents), runtime.getAdminResource().get());
    try {
      if (demo) {
        runDemo(factory);
        System.out.println("\npayments ledger: " + payments.ledger());
      } else {
        System.out.println("claims page: " + WebServer.url(web));
        System.out.println("admin UI:    " + WebServer.url(web) + "skipper/admin/");
        System.out.println("Press Ctrl-C to stop. Claims are kept in " + dbPath + " and are still there on the next start.");
        Thread.currentThread().join();
      }
    } finally {
      web.stop(0);
      runtime.getSkipperSchedulerManager().get().stop();
    }
  }

  /** Runs each sample to its end, playing the claimant and an adjuster who accepts the recommendation. */
  private static void runDemo(IWorkflowFactory factory) throws InterruptedException {
    String run = UUID.randomUUID().toString().substring(0, 8);
    int number = 0;
    for (Samples.Sample sample : Samples.ALL) {
      String id = "claim-" + run + "-" + ++number;
      System.out.println("\n" + sample.label() + " (" + Money.format(sample.claimedCents()) + " on " + sample.policyNumber() + ")");
      factory.builder(ClaimWorkflow.class, id).build()
          .handle(new ClaimRequest(id, sample.policyNumber(), sample.description(), sample.claimedCents()));

      // The future is not held: a parked instance does not complete it. Watch the persisted state instead.
      ClaimWorkflow claim = factory.invoke(ClaimWorkflow.class, id);
      String answered = "";
      Instant deadline = Instant.now().plus(Duration.ofMinutes(5));
      WorkflowInstanceStatusView status;
      while (!ENDED.contains(status = claim.getWorkflowInstanceView().getStatus())) {
        if (Instant.now().isAfter(deadline)) {
          throw new IllegalStateException(id + " is still " + status + " at stage " + claim.snapshot().stage());
        }
        ClaimSnapshot snapshot = claim.snapshot();
        // Answer each wait once; the same stage is seen again until the instance has moved on.
        String waitingOn = snapshot.stage() + snapshot.question();
        if (status == WorkflowInstanceStatusView.WAITING && !waitingOn.equals(answered)) {
          if ("NEEDS_INFO".equals(snapshot.stage())) {
            System.out.println("  claimant is asked for " + snapshot.question() + ", and replies");
            claim.provideInfo(sample.reply());
            answered = waitingOn;
          } else if ("WITH_ADJUSTER".equals(snapshot.stage())) {
            Decision recommended = snapshot.recommendation();
            boolean approve = recommended != null && recommended.pay();
            System.out.println("  adjuster " + (approve ? "approves " + Money.format(recommended.payoutCents()) : "denies"));
            claim.adjusterDecides(new AdjusterDecision(approve, approve ? recommended.payoutCents() : 0, "Reviewed by an adjuster."));
            answered = waitingOn;
          }
        }
        Thread.sleep(200);
      }
      ClaimSnapshot end = claim.snapshot();
      end.journal().lines().forEach(line -> System.out.println("  " + line));
      System.out.println("  => " + (end.result() == null
          ? status + ": the payment failed and the reservation was released"
          : end.result().status() + (end.result().payoutCents() > 0 ? " " + Money.format(end.result().payoutCents()) : "")
              + " (" + end.result().decidedBy() + "): " + end.result().explanation()));
    }
  }
}
