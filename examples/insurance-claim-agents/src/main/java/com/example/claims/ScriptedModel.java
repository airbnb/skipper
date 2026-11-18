package com.example.claims;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The default agents: no model, no network, the same answer every time, so the example runs anywhere and its
 * tests are exact. Each agent follows simple keyword rules, and the adjudicator makes the mistake a model
 * plausibly makes: its first decision forgets the deductible, and it corrects itself once the rules object.
 */
public class ScriptedModel implements ClaimsModel {
  private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

  @Override
  public IntakeReport intake(String description) {
    String text = description.toLowerCase();
    IncidentType type =
        matches(text, "windshield", "window", "glass") ? IncidentType.GLASS
            : matches(text, "stolen", "theft", "broke in") ? IncidentType.THEFT
            : matches(text, "hail", "flood", "storm", "tree fell") ? IncidentType.WEATHER
            : matches(text, "collision", "crash", "rear-ended", "hit", "dent", "scratch", "accident") ? IncidentType.COLLISION
            : IncidentType.UNKNOWN;
    Matcher date = DATE.matcher(description);
    String incidentDate = date.find() ? date.group() : "";

    List<String> missing = new ArrayList<>();
    if (type == IncidentType.UNKNOWN) missing.add("what happened to the car");
    if (incidentDate.isEmpty()) missing.add("the date it happened (YYYY-MM-DD)");
    if (type == IncidentType.THEFT && !text.contains("police report")) missing.add("the police report number");
    String summary = description.split("[.\\n]")[0].trim();
    return new IntakeReport(type, incidentDate, summary, missing);
  }

  @Override
  public Opinion assess(Specialist role, CaseFile file, Evidence evidence) {
    long claimed = file.claim().claimedCents();
    IncidentType type = file.intake().incidentType();
    switch (role) {
      case COVERAGE:
        return file.policy().covers().contains(type)
            ? new Opinion(role, Verdict.APPROVE, ClaimRules.maxPayableCents(file), "The policy covers " + type.name().toLowerCase() + " damage.")
            : new Opinion(role, Verdict.DENY, 0, "Your policy does not cover " + type.name().toLowerCase() + " damage.");
      case FRAUD:
        if (evidence.priorClaims() >= 2) {
          return new Opinion(role, Verdict.REFER, 0,
              "This would be claim number " + (evidence.priorClaims() + 1) + " on the policy in twelve months.");
        }
        if (claimed > 3 * evidence.typicalCostCents()) {
          return new Opinion(role, Verdict.REFER, 0, "The amount is more than three times what such a claim usually costs.");
        }
        return new Opinion(role, Verdict.APPROVE, 0, "Nothing unusual in the claim or the policy's history.");
      default:
        long typical = evidence.typicalCostCents();
        if (claimed <= typical + typical / 4) {
          return new Opinion(role, Verdict.APPROVE, claimed, "The amount is in line with the usual cost of " + Money.format(typical) + ".");
        }
        return new Opinion(role, Verdict.APPROVE, typical,
            "The amount is above the usual cost; " + Money.format(typical) + " is a fair figure.");
    }
  }

  @Override
  public Decision adjudicate(AdjudicationInput input) {
    Opinion coverage = input.opinions().of(Specialist.COVERAGE);
    Opinion damage = input.opinions().of(Specialist.DAMAGE);
    if (coverage != null && coverage.verdict() == Verdict.DENY) {
      return Decision.deny(coverage.reasoning());
    }
    long assessed = damage != null ? damage.amountCents() : input.file().claim().claimedCents();
    if (input.rejections().isEmpty()) {
      return Decision.pay(assessed, "We are paying the assessed damage in full.");
    }
    long payable = Math.min(assessed, input.file().policy().limitCents()) - input.file().policy().deductibleCents();
    return payable > 0
        ? Decision.pay(payable, "We are paying the assessed damage, less your " + Money.format(input.file().policy().deductibleCents()) + " deductible.")
        : Decision.deny("The damage is below your " + Money.format(input.file().policy().deductibleCents()) + " deductible.");
  }

  private static boolean matches(String text, String... words) {
    for (String word : words) {
      if (text.contains(word)) return true;
    }
    return false;
  }
}
