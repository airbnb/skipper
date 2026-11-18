package com.example.claims;

import java.util.List;

public record Policy(
    String number,
    String holder,
    List<IncidentType> covers,
    long limitCents,
    long deductibleCents,
    boolean active,
    String payoutAccount) {}
