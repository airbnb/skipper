package com.example.trips;

/** Something the travel provider can sell for one leg of the trip. The rating is out of five. */
public record Offer(String id, Leg leg, String description, long priceCents, int rating) {}
