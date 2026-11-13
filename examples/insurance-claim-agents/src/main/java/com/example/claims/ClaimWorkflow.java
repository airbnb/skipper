package com.example.claims;

import com.airbnb.skipper.IWorkflowFactory;
import com.airbnb.skipper.QueryMethod;
import com.airbnb.skipper.SignalMethod;
import com.airbnb.skipper.StateField;
import com.airbnb.skipper.Workflow;
import com.airbnb.skipper.WorkflowMethod;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;

/**
 * Adjudicates an insurance claim by orchestrating several agents:
 *
 * <ol>
 *   <li>an intake agent reads the claim; if something is missing the claimant is asked and the instance
 *       waits, for days if need be;
 *   <li>the policy is looked up, and a missing or lapsed one ends the claim there;
 *   <li>three specialist agents (coverage, fraud, damage) review the case in parallel, each as a child
 *       workflow, and the claim waits for their opinions up to a deadline;
 *   <li>an adjudicator agent reconciles the opinions into one decision, which {@link ClaimRules} checks;
 *   <li>a decision the rules will not carry out automatically waits for a human adjuster;
 *   <li>an approved payout is reserved and paid, and the reservation is released if the payment fails.
 * </ol>
 *
 * The agents propose; the workflow and the rules decide what happens. Every model call, lookup and payment
 * is a checkpointed action, so a crash at any point resumes from the last completed step.
 */
public class ClaimWorkflow extends Workflow {
  static final int MAX_INFO_ROUNDS = 2;
  static final int MAX_ADJUDICATION_ATTEMPTS = 3;
  static final Duration INFO_WINDOW = Duration.ofDays(7);
  static final Duration REVIEW_WINDOW = Duration.ofMinutes(10);

  @Inject IWorkflowFactory workflows;

  private final AgentActions agent = actions(AgentActions.class);
  private final RecordsActions records = actions(RecordsActions.class);
  private final PaymentActions payments = actions(PaymentActions.class);

  @StateField ClaimRequest claim;
  @StateField String stage = "RECEIVED";
  @StateField IntakeReport intake;
  @StateField Policy policy;
  @StateField String question;
  @StateField Journal answers = Journal.empty();
  @StateField Opinions opinions = Opinions.none();
  @StateField Opinions reviewed;
  @StateField Decision recommendation;
  @StateField Journal escalations = Journal.empty();
  @StateField AdjusterDecision adjuster;
  @StateField ClaimResult result;
  @StateField Journal journal = Journal.empty();

  @WorkflowMethod(returnType = ClaimResult.class)
  public CompletableFuture<ClaimResult> handle(ClaimRequest claim) {
    // The method runs again from the top on every resume, replaying recorded action results. The journal is
    // rebuilt by that replay, so start it empty rather than appending to the restored copy.
    journal = Journal.empty();
    this.claim = claim;

    // 1. Intake. Each answer from the claimant is appended to the description and read again.
    stage = "INTAKE";
    String description = claim.description();
    intake = agent.intake(description);
    for (int round = 0; !intake.missing().isEmpty(); round++) {
      if (round == MAX_INFO_ROUNDS) {
        return finish(ClaimResult.closed("We could not get the details needed to review this claim."));
      }
      question = String.join(", and ", intake.missing());
      stage = "NEEDS_INFO";
      note("Asked the claimant for " + question);
      int expected = round + 1;
      // Parks the instance (status WAITING) with no thread held. A signal or the deadline wakes it.
      if (!waitUntil(() -> answers.lines().size() >= expected, INFO_WINDOW)) {
        return finish(ClaimResult.closed("We did not hear back within " + INFO_WINDOW.toDays() + " days."));
      }
      description = description + "\n" + answers.lines().get(round);
      stage = "INTAKE";
      intake = agent.intake(description);
    }
    question = null;
    note("Understood the claim as " + intake.incidentType().name().toLowerCase() + ": " + intake.summary());

    // 2. The policy. Deterministic: no agent gets a say on a policy that does not exist or has lapsed.
    policy = records.policy(claim.policyNumber());
    if (policy == null || !policy.active()) {
      return finish(ClaimResult.denied("There is no active policy " + claim.policyNumber() + ".", "automatic"));
    }
    CaseFile file = new CaseFile(claim, description, intake, policy);

    // 3. Three specialists in parallel, each a child workflow that reports back through opinion().
    stage = "SPECIALIST_REVIEW";
    for (Specialist role : Specialist.values()) {
      // The id is an idempotency key: when this method replays, a child that already exists is not started again.
      workflows.builder(SpecialistWorkflow.class, getId() + "-" + role.name().toLowerCase())
          .runAsync()
          .detached()
          .asChildOf(this)
          .build()
          .review(new SpecialistTask(getId(), role, file));
    }
    // Wait for all three, but not forever: after the window the claim moves on with whoever answered, and
    // the rules send it to a person because an opinion is missing.
    waitUntil(() -> opinions.items().size() == Specialist.values().length, REVIEW_WINDOW);
    if (reviewed == null) {
      // Frozen once. A report that arrives after the window must not change what later steps replay with.
      reviewed = opinions.inRoleOrder();
    }
    for (Opinion opinion : reviewed.items()) {
      note(name(opinion.role()) + " specialist: " + opinion.verdict().name().toLowerCase() + ". " + opinion.reasoning());
    }

    // 4. The adjudicator proposes; the rules decide whether the proposal may stand.
    stage = "ADJUDICATION";
    Decision decision = adjudicate(file);
    recommendation = decision;
    List<String> reasons = ClaimRules.escalations(decision, reviewed);
    String decidedBy = "automatic";

    // 5. Anything the rules will not carry out automatically waits for a person, however long that takes.
    if (!reasons.isEmpty()) {
      escalations = new Journal(reasons);
      stage = "WITH_ADJUSTER";
      note("Sent to an adjuster because " + String.join("; ", reasons));
      waitUntil(() -> adjuster != null);
      decision = adjuster.approve()
          ? Decision.pay(adjuster.payoutCents(), adjuster.note())
          : Decision.deny(adjuster.note());
      decidedBy = "adjuster";
      note("The adjuster " + (decision.pay() ? "approved " + Money.format(decision.payoutCents()) : "denied the claim"));
    }
    if (!decision.pay()) {
      return finish(ClaimResult.denied(decision.explanation(), decidedBy));
    }

    // 6. Pay. If the payment cannot be made, the workflow fails and Skipper releases the reservation.
    stage = "PAYING";
    PayoutRequest payout = new PayoutRequest(getId(), policy.payoutAccount(), decision.payoutCents());
    payments.reserve(payout);
    payments.pay(payout);
    return finish(ClaimResult.paid(decision.payoutCents(), decision.explanation(), decidedBy));
  }

  /**
   * The adjudicator gets a bounded number of attempts; each refusal is fed back as the reason to do better.
   * Returns null when no attempt passes, and never returns a decision the rules refused.
   */
  private Decision adjudicate(CaseFile file) {
    List<String> rejections = List.of();
    for (int attempt = 1; attempt <= MAX_ADJUDICATION_ATTEMPTS; attempt++) {
      Decision candidate = agent.adjudicate(new AdjudicationInput(file, reviewed, rejections));
      rejections = ClaimRules.violations(candidate, file, reviewed);
      if (rejections.isEmpty()) {
        note("Adjudicator: " + (candidate.pay() ? "pay " + Money.format(candidate.payoutCents()) : "deny") + ". " + candidate.explanation());
        return candidate;
      }
      note("Refused the adjudicator's decision: " + String.join("; ", rejections));
    }
    return null;
  }

  private void note(String line) {
    journal = journal.with(line);
  }

  private static String name(Specialist role) {
    String lower = role.name().toLowerCase();
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }

  private CompletableFuture<ClaimResult> finish(ClaimResult outcome) {
    stage = outcome.status();
    result = outcome;
    return CompletableFuture.completedFuture(outcome);
  }

  /** The claimant's reply to a request for more information. */
  @SignalMethod(persist = true)
  public void provideInfo(String answer) {
    answers = answers.with(answer);
  }

  /** A specialist's report, delivered by its child workflow. Ignored once the review window has closed. */
  @SignalMethod(persist = true)
  public void opinion(Opinion opinion) {
    if (reviewed == null) {
      opinions = opinions.with(opinion);
    }
  }

  /**
   * The human adjuster's answer. The rules bind people too: an approval above what the policy can pay is
   * not accepted, and the claim keeps waiting.
   */
  @SignalMethod(persist = true)
  public void adjusterDecides(AdjusterDecision decision) {
    if (adjusterViolations(decision).isEmpty()) {
      adjuster = decision;
    }
  }

  /** Why an adjuster's decision would be refused; empty when it is acceptable. Lets a client check before signalling. */
  @QueryMethod
  public Journal adjusterCheck(AdjusterDecision decision) {
    return new Journal(adjusterViolations(decision));
  }

  private List<String> adjusterViolations(AdjusterDecision decision) {
    if (!"WITH_ADJUSTER".equals(stage)) {
      return List.of("this claim is not waiting for an adjuster");
    }
    Decision asDecision = decision.approve() ? Decision.pay(decision.payoutCents(), decision.note()) : Decision.deny(decision.note());
    return ClaimRules.violations(asDecision, new CaseFile(claim, claim.description(), intake, policy), reviewed);
  }

  /** Everything a client needs to show the claim, without holding a future. */
  @QueryMethod
  public ClaimSnapshot snapshot() {
    return new ClaimSnapshot(claim, stage, intake, policy, question, opinions, recommendation, escalations, result, journal);
  }
}
