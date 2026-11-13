package com.example.claims;

/** Everything a client needs to show a claim, read in one query. */
public record ClaimSnapshot(
    ClaimRequest claim,
    String stage,
    IntakeReport intake,
    Policy policy,
    String question,
    Opinions opinions,
    Decision recommendation,
    Journal escalations,
    ClaimResult result,
    Journal journal) {}
