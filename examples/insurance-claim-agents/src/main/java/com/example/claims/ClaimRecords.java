package com.example.claims;

/** The insurer's systems of record: the deterministic tools the workflow and the specialists look things up in. */
public interface ClaimRecords {
  /** Null when there is no such policy. */
  Policy policy(String number);

  int claimsInLastYear(String policyNumber);

  long typicalCostCents(IncidentType type);
}
