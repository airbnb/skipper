package com.example.claims;

/** The three agents that review a claim in parallel. Each answers one question and nothing else. */
public enum Specialist {
  /** Does the policy cover this kind of incident? */
  COVERAGE,
  /** Is anything about the claim or the claimant's history suspicious? */
  FRAUD,
  /** Is the amount claimed in line with what this kind of repair costs? */
  DAMAGE
}
