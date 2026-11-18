package com.example.claims;

/**
 * The inference behind every agent. Implementations may be wrong, slow, or unavailable; nothing they return
 * moves money until {@link ClaimRules} has checked it.
 */
public interface ClaimsModel {
  /** The intake agent: reads a free-text claim and says what is still missing. */
  IntakeReport intake(String description) throws ModelUnavailableException;

  /** A specialist agent: answers its one question from the case file and the evidence it gathered. */
  Opinion assess(Specialist role, CaseFile file, Evidence evidence) throws ModelUnavailableException;

  /** The adjudicator agent: reconciles the specialists' opinions into one decision. */
  Decision adjudicate(AdjudicationInput input) throws ModelUnavailableException;
}
