package com.example.trips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link ClaudePlanner} against a local stand-in for the Messages API, so the request it builds and
 * the way it reads each kind of response are checked without a network or an API key.
 */
class ClaudePlannerTest {
  private static final PlanningContext CONTEXT = new PlanningContext(
      150_000,
      new TripBrief("Lisbon", "2027-03-12", 3),
      new InMemoryTravelProvider().search(null),
      new Itinerary("FL-DIRECT", "HT-BOUTIQUE", "CAR-SUV", "first pick"),
      List.of(Leg.FLIGHT),
      List.of("total $1,910 exceeds the budget $1,500 by $410"));

  private HttpServer server;
  private ClaudePlanner planner;
  private volatile int status = 200;
  private volatile String response;
  private volatile String lastRequest;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/messages", exchange -> {
      lastRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      byte[] body = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("content-type", "application/json");
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    planner = new ClaudePlanner(
        AnthropicOkHttpClient.builder()
            .apiKey("test-key")
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
            .maxRetries(0)
            .build(),
        "claude-opus-5-5");
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void aStructuredAnswerBecomesAnItinerary() throws Exception {
    response = message("end_turn",
        "{\\\"flightOfferId\\\":\\\"FL-DIRECT\\\",\\\"hotelOfferId\\\":\\\"HT-HOSTEL\\\",\\\"carOfferId\\\":\\\"CAR-SUV\\\",\\\"rationale\\\":\\\"fits\\\"}");

    Itinerary itinerary = planner.propose(CONTEXT);

    assertEquals(new Itinerary("FL-DIRECT", "HT-HOSTEL", "CAR-SUV", "fits"), itinerary);
    // The request asks for the Itinerary schema and tells the model everything the validator will check.
    assertTrue(lastRequest.contains("\"json_schema\""), lastRequest);
    assertTrue(lastRequest.contains("hotelOfferId"), lastRequest);
    assertTrue(lastRequest.contains("HT-PALACE (HOTEL, $960, rated 5/5)"), lastRequest);
    assertTrue(lastRequest.contains("FLIGHT: FL-DIRECT (booked, must not change)"), lastRequest);
    assertTrue(lastRequest.contains("exceeds the budget $1,500 by $410"), lastRequest);
  }

  @Test
  void aStructuredAnswerBecomesABrief() throws Exception {
    response = message("end_turn", "{\\\"destination\\\":\\\"Lisbon\\\",\\\"checkIn\\\":\\\"2027-03-12\\\",\\\"nights\\\":3}");

    assertEquals(new TripBrief("Lisbon", "2027-03-12", 3), planner.understand("a long weekend in Lisbon"));
  }

  @Test
  void anOverloadedApiIsReportedAsUnavailableSoTheActionRetries() {
    status = 529;
    response = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}";

    assertThrows(PlannerUnavailableException.class, () -> planner.propose(CONTEXT));
  }

  @Test
  void aBadRequestIsNotRetried() {
    status = 400;
    response = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"nope\"}}";

    RuntimeException failure = assertThrows(RuntimeException.class, () -> planner.propose(CONTEXT));
    assertTrue(failure.getMessage().contains("nope"), failure.getMessage());
  }

  @Test
  void aRefusalIsAnErrorNotAnAnswer() {
    response = message("refusal", "");

    IllegalStateException failure = assertThrows(IllegalStateException.class, () -> planner.propose(CONTEXT));
    assertTrue(failure.getMessage().contains("refusal"), failure.getMessage());
  }

  private static String message(String stopReason, String escapedText) {
    return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
        + "\"content\":[{\"type\":\"text\",\"text\":\"" + escapedText + "\"}],"
        + "\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":10}}";
  }
}
