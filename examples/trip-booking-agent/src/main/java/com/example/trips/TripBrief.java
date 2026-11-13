package com.example.trips;

/** The structured reading of a free-text request. Produced by inference, consumed by the provider search. */
public record TripBrief(String destination, String checkIn, int nights) {}
