package com.example.trips;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for a real booking system: a fixed catalog, a ledger of what was booked and cancelled, and
 * switches to sell an offer out or fail the next few calls. A real one would be an HTTP client.
 */
public class InMemoryTravelProvider implements TravelProvider {
  private static final List<Offer> CATALOG = List.of(
      new Offer("FL-DIRECT", Leg.FLIGHT, "direct, morning departure", 62_000, 5),
      new Offer("FL-ONESTOP", Leg.FLIGHT, "one stop, late arrival", 38_000, 3),
      new Offer("HT-PALACE", Leg.HOTEL, "five-star palace, river view", 96_000, 5),
      new Offer("HT-BOUTIQUE", Leg.HOTEL, "boutique hotel, old town", 54_000, 4),
      new Offer("HT-HOSTEL", Leg.HOTEL, "private room in a hostel", 27_000, 2),
      new Offer("CAR-SUV", Leg.CAR, "SUV with full insurance", 33_000, 5),
      new Offer("CAR-COMPACT", Leg.CAR, "compact, basic insurance", 15_000, 3));

  private final Set<String> soldOut = new HashSet<>();
  private final List<String> ledger = new ArrayList<>();
  private final AtomicInteger confirmations = new AtomicInteger();
  private int failuresLeft;
  private int bookCalls;

  @Override
  public synchronized List<Offer> search(TripBrief brief) {
    List<Offer> available = new ArrayList<>();
    for (Offer offer : CATALOG) {
      if (!soldOut.contains(offer.id())) available.add(offer);
    }
    return available;
  }

  @Override
  public synchronized BookingOutcome book(String tripId, Offer offer) throws ProviderUnavailableException {
    bookCalls++;
    if (failuresLeft > 0) {
      failuresLeft--;
      throw new ProviderUnavailableException("503 from the booking system");
    }
    if (soldOut.contains(offer.id())) {
      return BookingOutcome.soldOut(offer.id() + " is no longer available");
    }
    String confirmationId = "cnf-" + confirmations.incrementAndGet();
    ledger.add("BOOK " + confirmationId + " " + offer.id() + " " + Money.format(offer.priceCents()));
    return BookingOutcome.ok(confirmationId);
  }

  @Override
  public synchronized void cancel(String confirmationId) {
    ledger.add("CANCEL " + confirmationId);
  }

  /** Someone else takes the last one. Offers already returned by a search still name it. */
  public synchronized void sellOut(String... offerIds) {
    soldOut.addAll(List.of(offerIds));
  }

  public synchronized void restock() {
    soldOut.clear();
  }

  public synchronized void restock(String offerId) {
    soldOut.remove(offerId);
  }

  public synchronized boolean isSoldOut(String offerId) {
    return soldOut.contains(offerId);
  }

  /** Everything the provider sells, sold out or not. */
  public List<Offer> catalog() {
    return CATALOG;
  }

  public synchronized void failNext(int times) {
    failuresLeft = times;
  }

  public synchronized int bookCalls() {
    return bookCalls;
  }

  public synchronized List<String> ledger() {
    return new ArrayList<>(ledger);
  }
}
