package com.example.claims;

/** What every agent after intake works from. {@code description} includes anything the claimant added later. */
public record CaseFile(ClaimRequest claim, String description, IntakeReport intake, Policy policy) {}
