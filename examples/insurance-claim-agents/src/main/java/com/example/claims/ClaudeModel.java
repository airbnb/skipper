package com.example.claims;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;

/**
 * The agents backed by Claude. Each agent is one system prompt and one request with a structured output: the
 * SDK derives a JSON schema from the record the workflow expects, so the answer always parses. Whether it
 * is a good answer is not this class's job; {@link ClaimRules} decides that.
 */
public class ClaudeModel implements ClaimsModel {
  private static final String INTAKE =
      "You are the intake agent for a car insurer. Read the claimant's description and report the incident "
          + "type, the incident date as YYYY-MM-DD (an empty string if it is not given), and a one-sentence "
          + "summary. In `missing`, list what the claimant still has to tell us before the claim can be "
          + "reviewed, each as a short phrase that completes \"Please tell us ...\". A claim needs to say what "
          + "happened and when; a theft claim also needs a police report number. Leave `missing` empty when "
          + "nothing is missing. Do not ask for anything else.";

  private static final String COVERAGE =
      "You are the coverage specialist for a car insurer. Decide only whether the policy covers this kind of "
          + "incident. APPROVE with the most the claim can pay as the amount, or DENY with amount 0. Use REFER "
          + "only when the policy terms given do not settle it.";

  private static final String FRAUD =
      "You are the fraud specialist for a car insurer. Decide only whether the claim or the policy's history "
          + "is suspicious enough that a person should look. APPROVE when nothing stands out, REFER when "
          + "something does, and say what. The amount is always 0. You never DENY: that is a person's call.";

  private static final String DAMAGE =
      "You are the damage specialist for a car insurer. Decide only whether the amount claimed is reasonable "
          + "for this kind of repair. APPROVE with the amount you consider fair, which may be less than the "
          + "amount claimed. Use REFER when the description does not let you judge the cost.";

  private static final String ADJUDICATOR =
      "You are the adjudicator for a car insurer. Three specialists have reviewed a claim. Reconcile their "
          + "opinions into one decision: pay, with an amount in cents, or deny. A payout is the assessed "
          + "damage, capped at the policy limit, less the deductible. Never pay when coverage is denied. If an "
          + "earlier decision of yours was refused, fix exactly what the refusal names. Write the explanation "
          + "as one or two plain sentences addressed to the claimant.";

  private final AnthropicClient client;
  private final String model;

  /** Reads ANTHROPIC_API_KEY from the environment. */
  public ClaudeModel(String model) {
    this(AnthropicOkHttpClient.fromEnv(), model);
  }

  ClaudeModel(AnthropicClient client, String model) {
    this.client = client;
    this.model = model;
  }

  @Override
  public IntakeReport intake(String description) throws ModelUnavailableException {
    return ask(INTAKE, IntakeReport.class, description);
  }

  @Override
  public Opinion assess(Specialist role, CaseFile file, Evidence evidence) throws ModelUnavailableException {
    String system = role == Specialist.COVERAGE ? COVERAGE : role == Specialist.FRAUD ? FRAUD : DAMAGE;
    StringBuilder prompt = new StringBuilder(describe(file)).append("\nWhat you looked up:\n");
    evidence.facts().forEach(fact -> prompt.append("- ").append(fact).append('\n'));
    Opinion opinion = ask(system, Opinion.class, prompt.toString());
    // The role is the one that was asked, whatever the answer says.
    return new Opinion(role, opinion.verdict(), opinion.amountCents(), opinion.reasoning());
  }

  @Override
  public Decision adjudicate(AdjudicationInput input) throws ModelUnavailableException {
    StringBuilder prompt = new StringBuilder(describe(input.file())).append("\nSpecialist opinions:\n");
    for (Opinion opinion : input.opinions().items()) {
      prompt.append("- ").append(opinion.role()).append(": ").append(opinion.verdict());
      if (opinion.amountCents() > 0) prompt.append(", ").append(opinion.amountCents()).append(" cents");
      prompt.append(". ").append(opinion.reasoning()).append('\n');
    }
    if (!input.rejections().isEmpty()) {
      prompt.append("\nYour previous decision was refused:\n");
      input.rejections().forEach(reason -> prompt.append("- ").append(reason).append('\n'));
    }
    return ask(ADJUDICATOR, Decision.class, prompt.toString());
  }

  static String describe(CaseFile file) {
    Policy policy = file.policy();
    return "Claim: " + file.description() + "\n"
        + "Incident type: " + file.intake().incidentType() + ", date " + file.intake().incidentDate() + "\n"
        + "Amount claimed: " + file.claim().claimedCents() + " cents (" + Money.format(file.claim().claimedCents()) + ")\n"
        + "Policy " + policy.number() + ": covers " + policy.covers() + ", limit " + policy.limitCents()
        + " cents, deductible " + policy.deductibleCents() + " cents\n";
  }

  private <T> T ask(String system, Class<T> answer, String prompt) throws ModelUnavailableException {
    StructuredMessageCreateParams<T> params = MessageCreateParams.builder()
        .model(model)
        .maxTokens(16_000L)
        .system(system)
        .outputConfig(answer)
        .addUserMessage(prompt)
        .build();
    StructuredMessage<T> message;
    try {
      message = client.messages().create(params);
    } catch (RateLimitException | InternalServerException | AnthropicIoException e) {
      // Transient: AgentActions turns this into a retry with backoff. Anything else (a bad request, a
      // missing key) will not fix itself and fails the workflow.
      throw new ModelUnavailableException("the model is unavailable: " + e.getMessage(), e);
    }
    // A refusal or a truncated answer has no usable content; check before reading it.
    StopReason stop = message.stopReason().orElse(null);
    if (!StopReason.END_TURN.equals(stop)) {
      throw new IllegalStateException("the model stopped with " + stop + " instead of answering");
    }
    return message.content().stream()
        .flatMap(block -> block.text().stream())
        .map(text -> text.text())
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("the model returned no answer"));
  }
}
