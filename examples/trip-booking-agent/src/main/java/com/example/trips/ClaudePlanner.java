package com.example.trips;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;

/**
 * The planner backed by Claude. Each call is one request with a structured output: the SDK derives a JSON
 * schema from the record the workflow expects, so the answer always parses. Whether it is a good answer is
 * not this class's job; {@link ItineraryValidator} decides that.
 */
public class ClaudePlanner implements TripPlanner {
  private static final String SYSTEM =
      "You plan trips for a booking service. You are given the traveler's request or a list of offers, and you "
          + "answer in the requested structure. Use only offer ids that appear in the list. The budget is a hard "
          + "limit on the sum of the three chosen offers; within it, prefer better-rated offers. Legs marked as "
          + "booked are already paid for and must keep their offer. If an earlier proposal was refused, fix "
          + "exactly what the refusal names. Keep the rationale to one sentence.";

  private final AnthropicClient client;
  private final String model;

  /** Reads ANTHROPIC_API_KEY from the environment. */
  public ClaudePlanner(String model) {
    this(AnthropicOkHttpClient.fromEnv(), model);
  }

  ClaudePlanner(AnthropicClient client, String model) {
    this.client = client;
    this.model = model;
  }

  @Override
  public TripBrief understand(String request) throws PlannerUnavailableException {
    return ask(TripBrief.class,
        "Read this trip request into a destination city, a check-in date (YYYY-MM-DD) and a number of nights.\n\n" + request);
  }

  @Override
  public Itinerary propose(PlanningContext context) throws PlannerUnavailableException {
    return ask(Itinerary.class, prompt(context));
  }

  private <T> T ask(Class<T> answer, String prompt) throws PlannerUnavailableException {
    StructuredMessageCreateParams<T> params = MessageCreateParams.builder()
        .model(model)
        .maxTokens(16_000L)
        .system(SYSTEM)
        .outputConfig(answer)
        .addUserMessage(prompt)
        .build();
    StructuredMessage<T> message;
    try {
      message = client.messages().create(params);
    } catch (RateLimitException | InternalServerException | AnthropicIoException e) {
      // Transient: PlannerActions turns this into a retry with backoff. Anything else (a bad request, a
      // missing key) will not fix itself and fails the workflow.
      throw new PlannerUnavailableException("the model is unavailable: " + e.getMessage(), e);
    }
    // A refusal or a truncated answer has no usable content; check before reading it.
    StopReason stop = message.stopReason().orElse(null);
    if (!StopReason.END_TURN.equals(stop)) {
      throw new IllegalStateException("the model stopped with " + stop + " instead of answering");
    }
    return message.content().stream()
        .flatMap(block -> block.text().stream())
        .map(text -> text.text())
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("the model returned no answer"));
  }

  static String prompt(PlanningContext context) {
    StringBuilder text = new StringBuilder();
    TripBrief brief = context.brief();
    text.append("Trip: ").append(brief.destination()).append(", check-in ").append(brief.checkIn())
        .append(", ").append(brief.nights()).append(" nights\n");
    text.append("Budget: ").append(Money.format(context.budgetCents())).append("\n\nOffers:\n");
    for (Offer offer : context.offers()) {
      text.append("- ").append(offer.id()).append(" (").append(offer.leg()).append(", ")
          .append(Money.format(offer.priceCents())).append(", rated ").append(offer.rating()).append("/5): ")
          .append(offer.description()).append('\n');
    }
    if (context.current() != null) {
      text.append("\nThe itinerary being revised, because one of its offers sold out:\n");
      for (Leg leg : Leg.values()) {
        text.append("- ").append(leg).append(": ").append(context.current().offerId(leg))
            .append(context.booked().contains(leg) ? " (booked, must not change)" : "").append('\n');
      }
    }
    if (!context.rejections().isEmpty()) {
      text.append("\nYour previous proposal was refused:\n");
      for (String reason : context.rejections()) {
        text.append("- ").append(reason).append('\n');
      }
    }
    text.append("\nChoose one flight, one hotel and one car.");
    return text.toString();
  }
}
