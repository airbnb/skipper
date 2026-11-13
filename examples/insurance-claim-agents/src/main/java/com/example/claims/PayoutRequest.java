package com.example.claims;

public record PayoutRequest(String claimId, String account, long amountCents) {}
