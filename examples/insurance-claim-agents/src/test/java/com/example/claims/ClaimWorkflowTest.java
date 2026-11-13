package com.example.claims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.airbnb.skipper.IWorkflowFactory;
import com.airbnb.skipper.SimpleInjector;
import com.airbnb.skipper.api.WorkflowInstanceStatusView;
import com.airbnb.skipper.testutils.Bind;
import com.airbnb.skipper.testutils.WorkflowTest;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Each test runs on its own in-memory Skipper runtime, where the claim and its three specialist child
 * workflows all execute. The @Bind fields are handed to the @Inject fields of the workflows and actions, so
 * the model is replaced by one the test controls and no test needs a network or an API key.
 */
class ClaimWorkflowTest extends WorkflowTest {
  @Bind(to = ClaimsModel.class) FakeModel model = new FakeModel();
  @Bind(to = ClaimRecords.class) InMemoryClaimRecords records = new InMemoryClaimRecords();
  @Bind(to = Payments.class) InMemoryPayments payments = new InMemoryPayments();

  /** The workflows start children and signal each other through the factory, which exists only once the runtime does. */
  @BeforeEach
  void bindWorkflowFactory() {
    ((SimpleInjector) config.getInjector()).register(IWorkflowFactory.class, workflowFactory);
  }

  @Test
  void aSmallClaimIsReviewedByThreeSpecialistsAndPaidAutomatically() {
    start("P-1001", "A stone hit my windshield on 2027-03-02 and it cracked.", 40_000);

    assertEquals(WorkflowInstanceStatusView.COMPLETED, helper.waitForWorkflowToComplete().getStatus());
    ClaimSnapshot claim = claim().snapshot();
    assertEquals(ClaimResult.paid(15_000, "We are paying the assessed damage, less your $250 deductible.", "automatic"), claim.result());
    assertEquals(
        List.of(Specialist.COVERAGE, Specialist.FRAUD, Specialist.DAMAGE),
        claim.opinions().inRoleOrder().items().stream().map(Opinion::role).toList());
    assertEquals(
        List.of(
            "Understood the claim as glass: A stone hit my windshield on 2027-03-02 and it cracked",
            "Coverage specialist: approve. The policy covers glass damage.",
            "Fraud specialist: approve. Nothing unusual in the claim or the policy's history.",
            "Damage specialist: approve. The amount is in line with the usual cost of $450.",
            "Refused the adjudicator's decision: a payout of $400 is more than this policy can pay for this claim, $150 after the $250 deductible",
            "Adjudicator: pay $150. We are paying the assessed damage, less your $250 deductible."),
        claim.journal().lines());
    assertEquals(List.of("RESERVE rsv-1 $150", "PAY $150 to acct-alice"), payments.ledger());
  }

  @Test
  void noAgentIsAskedTwiceHoweverOftenTheClaimResumes() {
    start("P-1001", "A stone hit my windshield on 2027-03-02 and it cracked.", 40_000);
    helper.waitForWorkflowToComplete();

    // The claim's method replayed from its first line after the review wait and after every signal. The
    // model was still called once per step: intake, three specialists, and two adjudication attempts.
    assertEquals(6, model.calls());
  }

  @Test
  void aLargePayoutWaitsForAnAdjuster() {
    start("P-1001", "I was rear-ended at a traffic light on 2027-03-05.", 420_000);
    awaitStage("WITH_ADJUSTER");

    ClaimSnapshot waiting = claim().snapshot();
    assertEquals(Decision.pay(395_000, "We are paying the assessed damage, less your $250 deductible."), waiting.recommendation());
    assertEquals(List.of("a payout of $3,950 is above the $2,000 limit for automatic approval"), waiting.escalations().lines());
    assertTrue(payments.ledger().isEmpty(), "nothing is reserved or paid before the adjuster decides");

    claim().adjusterDecides(new AdjusterDecision(true, 380_000, "Approved, less a pre-existing scratch."));

    helper.waitForWorkflowToComplete();
    assertEquals(ClaimResult.paid(380_000, "Approved, less a pre-existing scratch.", "adjuster"), claim().snapshot().result());
    assertEquals(List.of("RESERVE rsv-1 $3,800", "PAY $3,800 to acct-alice"), payments.ledger());
  }

  @Test
  void theRulesBindTheAdjusterToo() {
    start("P-1001", "I was rear-ended at a traffic light on 2027-03-05.", 420_000);
    awaitStage("WITH_ADJUSTER");
    AdjusterDecision tooMuch = new AdjusterDecision(true, 900_000, "Pay it all.");

    assertEquals(
        List.of("a payout of $9,000 is more than this policy can pay for this claim, $3,950 after the $250 deductible"),
        claim().adjusterCheck(tooMuch).lines());
    claim().adjusterDecides(tooMuch);
    assertEquals("WITH_ADJUSTER", claim().snapshot().stage(), "an approval over the limit is not accepted");

    claim().adjusterDecides(new AdjusterDecision(false, 0, "The other driver's insurer is liable."));

    helper.waitForWorkflowToComplete();
    assertEquals(ClaimResult.denied("The other driver's insurer is liable.", "adjuster"), claim().snapshot().result());
    assertTrue(payments.ledger().isEmpty());
  }

  @Test
  void aFraudReferralGoesToAnAdjusterWhateverTheAmount() {
    start("P-2002", "A stone hit my windshield on 2027-03-02 and it cracked.", 90_000);
    awaitStage("WITH_ADJUSTER");

    assertEquals(
        List.of("the fraud specialist asked for a human review: This would be claim number 3 on the policy in twelve months."),
        claim().snapshot().escalations().lines());
  }

  @Test
  void aVagueClaimAsksTheClaimantAndCarriesOnWithTheAnswer() {
    start("P-1001", "My car got damaged and I need it fixed.", 90_000);
    awaitStage("NEEDS_INFO");
    assertEquals("what happened to the car, and the date it happened (YYYY-MM-DD)", claim().snapshot().question());

    claim().provideInfo("A shopping cart hit the door on 2027-03-03 and left a dent.");

    helper.waitForWorkflowToComplete();
    ClaimSnapshot claim = claim().snapshot();
    assertEquals(IncidentType.COLLISION, claim.intake().incidentType());
    assertNull(claim.question());
    assertEquals("PAID", claim.result().status());
    assertEquals(65_000, claim.result().payoutCents());
  }

  @Test
  void aQuestionNobodyAnswersClosesTheClaim() {
    start("P-1001", "My car got damaged and I need it fixed.", 90_000);
    awaitStage("NEEDS_INFO");

    // The test clock ticks with real time; jump it past the seven-day window instead of waiting.
    clock.fastForward(ClaimWorkflow.INFO_WINDOW.plusMinutes(1));

    helper.waitForWorkflowToComplete();
    assertEquals(ClaimResult.closed("We did not hear back within 7 days."), claim().snapshot().result());
  }

  @Test
  void anIncidentThePolicyDoesNotCoverIsDenied() {
    start("P-3003", "A hail storm on 2027-03-04 damaged the roof and the hood.", 300_000);

    helper.waitForWorkflowToComplete();
    assertEquals(ClaimResult.denied("Your policy does not cover weather damage.", "automatic"), claim().snapshot().result());
    assertTrue(payments.ledger().isEmpty());
  }

  @Test
  void anUnknownPolicyIsDeniedBeforeAnySpecialistRuns() {
    start("P-9999", "A stone hit my windshield on 2027-03-02 and it cracked.", 40_000);

    helper.waitForWorkflowToComplete();
    assertEquals(ClaimResult.denied("There is no active policy P-9999.", "automatic"), claim().snapshot().result());
    assertEquals(1, model.calls(), "only the intake agent was asked");
  }

  @Test
  void aPaymentThatBouncesReleasesTheReservedMoney() {
    start("P-4004", "A branch broke my rear window glass on 2027-03-06.", 60_000);

    // The instance passes through ERROR on its way here; wait for the status that says the undo is done.
    helper.fastForwardUntilWorkflowReachesStatus(WorkflowInstanceStatusView.COMPENSATION_COMPLETED, Duration.ofSeconds(30));
    assertEquals(List.of("RESERVE rsv-1 $200", "RELEASE rsv-1"), payments.ledger());
  }

  @Test
  void aSpecialistThatNeverAnswersDoesNotHoldTheClaimUp() {
    model.silence(Specialist.FRAUD);
    start("P-1001", "A stone hit my windshield on 2027-03-02 and it cracked.", 40_000);
    await(() -> claim().snapshot().opinions().items().size() == 2);

    // The other two have reported. Jump past the review window: the claim moves on without the third.
    clock.fastForward(ClaimWorkflow.REVIEW_WINDOW.plusMinutes(1));
    awaitStage("WITH_ADJUSTER");

    ClaimSnapshot claim = claim().snapshot();
    assertEquals(List.of("the fraud specialist did not answer in time"), claim.escalations().lines());
    assertEquals(Decision.pay(15_000, "We are paying the assessed damage, less your $250 deductible."), claim.recommendation());
    assertTrue(payments.ledger().isEmpty());
  }

  @Test
  void aModelOutageIsRetriedWithBackoff() {
    model.failNext(2); // two outages on intake, then an answer; the action's strategy backs off between attempts
    start("P-1001", "A stone hit my windshield on 2027-03-02 and it cracked.", 40_000);

    // Each retry is a timer on the test clock; step the clock until the instance is done.
    assertEquals(
        WorkflowInstanceStatusView.COMPLETED,
        helper.fastForwardUntilWorkflowCompletes(Duration.ofSeconds(5)).getStatus());
    assertEquals("PAID", claim().snapshot().result().status());
  }

  @Test
  void thePayableAmountIsCappedByThePolicyLimitAndReducedByTheDeductible() {
    Policy policy = records.policy("P-3003"); // limit $10,000, deductible $250
    CaseFile file = new CaseFile(new ClaimRequest("c", "P-3003", "", 5_000_000), "", null, policy);

    assertEquals(975_000, ClaimRules.maxPayableCents(file));
    assertEquals(
        List.of("the policy does not cover this incident: not covered"),
        ClaimRules.violations(
            Decision.pay(100_000, ""), file,
            Opinions.none().with(new Opinion(Specialist.COVERAGE, Verdict.DENY, 0, "not covered"))));
  }

  private void start(String policyNumber, String description, long claimedCents) {
    workflowBuilder(ClaimWorkflow.class).build().handle(new ClaimRequest(workflowId, policyNumber, description, claimedCents));
  }

  private ClaimWorkflow claim() {
    return workflow(ClaimWorkflow.class);
  }

  private void awaitStage(String stage) {
    await(() -> stage.equals(claim().snapshot().stage()));
  }

  private void await(BooleanSupplier condition) {
    for (int attempt = 0; attempt < 300; attempt++) {
      if (condition.getAsBoolean()) return;
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }
    throw new AssertionError("the claim is at stage " + claim().snapshot().stage() + " after 30 seconds");
  }
}
