package com.example.claims;

/** One specialist's answer. {@code amountCents} is what it thinks is payable, 0 unless it approves. */
public record Opinion(Specialist role, Verdict verdict, long amountCents, String reasoning) {}
