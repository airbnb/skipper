package com.example.claims;

import java.util.List;

/**
 * What a specialist looked up before forming an opinion. {@code facts} is the readable version handed to the
 * model; the two numbers are the same findings in a form code can use.
 */
public record Evidence(List<String> facts, int priorClaims, long typicalCostCents) {}
