package com.example.claims;

/** Input of a specialist's child workflow: which question to answer, about which case, for which claim. */
public record SpecialistTask(String claimId, Specialist role, CaseFile file) {}
