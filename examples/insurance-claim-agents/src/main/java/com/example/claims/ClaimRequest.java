package com.example.claims;

/** Workflow input: what the claimant submitted. */
public record ClaimRequest(String claimId, String policyNumber, String description, long claimedCents) {}
