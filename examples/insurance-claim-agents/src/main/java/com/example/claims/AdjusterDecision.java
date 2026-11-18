package com.example.claims;

/** A human adjuster's answer on an escalated claim. */
public record AdjusterDecision(boolean approve, long payoutCents, String note) {}
