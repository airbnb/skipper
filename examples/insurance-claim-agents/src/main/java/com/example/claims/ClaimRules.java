package com.example.claims;

import java.util.ArrayList;
import java.util.List;

/**
 * The guardrail between the agents and the money. Pure and deterministic, so the workflow can call it
 * directly and replay it safely. It answers two questions about a decision: may it stand at all, and may it
 * stand without a person looking at it.
 */
public final class ClaimRules {
  /** Above this, a payout is never automatic, whatever the agents say. */
  public static final long AUTO_APPROVE_LIMIT_CENTS = 200_000;

  private ClaimRules() {}

  /** The most this claim can ever pay: the lesser of the policy limit and the amount claimed, less the deductible. */
  public static long maxPayableCents(CaseFile file) {
    long covered = Math.min(file.policy().limitCents(), file.claim().claimedCents());
    return Math.max(0, covered - file.policy().deductibleCents());
  }

  /** Why a decision cannot stand. These bind the adjudicator and the human adjuster alike. */
  public static List<String> violations(Decision decision, CaseFile file, Opinions opinions) {
    if (decision == null) {
      return List.of("no decision was returned");
    }
    List<String> violations = new ArrayList<>();
    if (decision.pay()) {
      long max = maxPayableCents(file);
      if (decision.payoutCents() <= 0) {
        violations.add("a payout must be more than zero");
      } else if (decision.payoutCents() > max) {
        violations.add(
            "a payout of " + Money.format(decision.payoutCents()) + " is more than this policy can pay for this claim, "
                + Money.format(max) + " after the " + Money.format(file.policy().deductibleCents()) + " deductible");
      }
      Opinion coverage = opinions.of(Specialist.COVERAGE);
      if (coverage != null && coverage.verdict() == Verdict.DENY) {
        violations.add("the policy does not cover this incident: " + coverage.reasoning());
      }
    }
    return violations;
  }

  /** Why a valid decision still needs a person. Empty means it may be carried out automatically. */
  public static List<String> escalations(Decision decision, Opinions opinions) {
    List<String> reasons = new ArrayList<>();
    for (Specialist role : Specialist.values()) {
      Opinion opinion = opinions.of(role);
      if (opinion == null) {
        reasons.add("the " + role.name().toLowerCase() + " specialist did not answer in time");
      } else if (opinion.verdict() == Verdict.REFER) {
        reasons.add("the " + role.name().toLowerCase() + " specialist asked for a human review: " + opinion.reasoning());
      }
    }
    if (decision == null) {
      reasons.add("the adjudicator could not reach a decision within the rules");
    } else if (decision.pay() && decision.payoutCents() > AUTO_APPROVE_LIMIT_CENTS) {
      reasons.add(
          "a payout of " + Money.format(decision.payoutCents()) + " is above the "
              + Money.format(AUTO_APPROVE_LIMIT_CENTS) + " limit for automatic approval");
    }
    return reasons;
  }
}
