package com.example.trips;

import java.util.List;

/** What the provider had on sale when the trip was planned. */
public record SearchResults(List<Offer> offers) {}
