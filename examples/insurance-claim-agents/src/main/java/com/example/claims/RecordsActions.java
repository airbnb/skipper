package com.example.claims;

import com.airbnb.skipper.Actions;
import com.airbnb.skipper.Execute;
import java.util.List;
import javax.inject.Inject;

/** Lookups in the insurer's systems of record. No model is involved in any of them. */
public class RecordsActions extends Actions {
  @Inject ClaimRecords records;

  /** Null when there is no such policy. */
  @Execute
  public Policy policy(String number) {
    return records.policy(number);
  }

  /** The tool each specialist uses before it forms an opinion: a different lookup for each question. */
  @Execute
  public Evidence gather(SpecialistTask task) {
    Policy policy = task.file().policy();
    IncidentType type = task.file().intake().incidentType();
    switch (task.role()) {
      case COVERAGE:
        return new Evidence(
            List.of(
                "The policy covers: " + policy.covers(),
                "Policy limit " + Money.format(policy.limitCents()) + ", deductible " + Money.format(policy.deductibleCents()),
                "The most this claim can pay is " + Money.format(ClaimRules.maxPayableCents(task.file()))),
            0, 0);
      case FRAUD:
        int prior = records.claimsInLastYear(policy.number());
        long typical = records.typicalCostCents(type);
        return new Evidence(
            List.of(
                prior + " earlier claims on this policy in the last twelve months",
                "A typical " + type + " claim costs " + Money.format(typical)),
            prior, typical);
      default:
        long cost = records.typicalCostCents(type);
        return new Evidence(List.of("A typical " + type + " repair costs " + Money.format(cost)), 0, cost);
    }
  }
}
