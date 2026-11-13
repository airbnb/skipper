package com.example.claims;

import java.util.List;
import java.util.Map;

/** A stand-in for the insurer's policy, claims-history and repair-cost systems. */
public class InMemoryClaimRecords implements ClaimRecords {
  private static final List<IncidentType> STANDARD = List.of(IncidentType.COLLISION, IncidentType.GLASS, IncidentType.THEFT);

  private static final List<Policy> POLICIES = List.of(
      new Policy("P-1001", "Alice Martin", STANDARD, 2_000_000, 25_000, true, "acct-alice"),
      new Policy("P-2002", "Ben Okafor", STANDARD, 1_500_000, 50_000, true, "acct-ben"),
      new Policy("P-3003", "Carla Ruiz", List.of(IncidentType.COLLISION, IncidentType.GLASS), 1_000_000, 25_000, true, "acct-carla"),
      new Policy("P-4004", "Dan Lee", STANDARD, 2_000_000, 25_000, true, InMemoryPayments.CLOSED_ACCOUNT));

  private static final Map<String, Integer> CLAIMS_IN_LAST_YEAR = Map.of("P-2002", 2);

  private static final Map<IncidentType, Long> TYPICAL_COST_CENTS = Map.of(
      IncidentType.GLASS, 45_000L,
      IncidentType.COLLISION, 350_000L,
      IncidentType.THEFT, 1_200_000L,
      IncidentType.WEATHER, 400_000L);

  @Override
  public Policy policy(String number) {
    return POLICIES.stream().filter(p -> p.number().equals(number)).findFirst().orElse(null);
  }

  @Override
  public int claimsInLastYear(String policyNumber) {
    return CLAIMS_IN_LAST_YEAR.getOrDefault(policyNumber, 0);
  }

  @Override
  public long typicalCostCents(IncidentType type) {
    return TYPICAL_COST_CENTS.getOrDefault(type, 0L);
  }

  public List<Policy> policies() {
    return POLICIES;
  }
}
