package com.example.claims;

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
 * Drives {@link ClaudeModel} against a local stand-in for the Messages API, so the request each agent sends
 * and the way each kind of response is read are checked without a network or an API key.
 */
class ClaudeModelTest {
  private static final CaseFile FILE = new CaseFile(
      new ClaimRequest("claim-1", "P-1001", "A stone cracked my windshield on 2027-03-02.", 40_000),
      "A stone cracked my windshield on 2027-03-02.",
      new IntakeReport(IncidentType.GLASS, "2027-03-02", "A stone cracked the windshield", List.of()),
      new InMemoryClaimRecords().policy("P-1001"));

  private HttpServer server;
  private ClaudeModel model;
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
    model = new ClaudeModel(
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
  void theIntakeAgentReadsAClaimIntoAReport() throws Exception {
    response = message("end_turn",
        "{\\\"incidentType\\\":\\\"THEFT\\\",\\\"incidentDate\\\":\\\"\\\",\\\"summary\\\":\\\"Car stolen\\\","
            + "\\\"missing\\\":[\\\"the date it happened\\\",\\\"the police report number\\\"]}");

    IntakeReport report = model.intake("My car was stolen.");

    assertEquals(
        new IntakeReport(IncidentType.THEFT, "", "Car stolen", List.of("the date it happened", "the police report number")),
        report);
    assertTrue(lastRequest.contains("\"json_schema\""), lastRequest);
    assertTrue(lastRequest.contains("You are the intake agent"), lastRequest);
    assertTrue(lastRequest.contains("My car was stolen."), lastRequest);
  }

  @Test
  void aSpecialistGetsItsOwnInstructionsAndEvidenceAndKeepsItsRole() throws Exception {
    // The answer claims to be from the coverage specialist; the role that was asked wins.
    response = message("end_turn",
        "{\\\"role\\\":\\\"COVERAGE\\\",\\\"verdict\\\":\\\"REFER\\\",\\\"amountCents\\\":0,\\\"reasoning\\\":\\\"Third claim this year.\\\"}");

    Opinion opinion = model.assess(
        Specialist.FRAUD, FILE, new Evidence(List.of("2 earlier claims on this policy in the last twelve months"), 2, 45_000));

    assertEquals(new Opinion(Specialist.FRAUD, Verdict.REFER, 0, "Third claim this year."), opinion);
    assertTrue(lastRequest.contains("You are the fraud specialist"), lastRequest);
    assertTrue(lastRequest.contains("2 earlier claims on this policy in the last twelve months"), lastRequest);
    assertTrue(lastRequest.contains("Amount claimed: 40000 cents ($400)"), lastRequest);
  }

  @Test
  void theAdjudicatorSeesEveryOpinionAndWhyItsLastDecisionWasRefused() throws Exception {
    response = message("end_turn", "{\\\"pay\\\":true,\\\"payoutCents\\\":15000,\\\"explanation\\\":\\\"Paid less the deductible.\\\"}");
    Opinions opinions = Opinions.none()
        .with(new Opinion(Specialist.COVERAGE, Verdict.APPROVE, 15_000, "Glass is covered."))
        .with(new Opinion(Specialist.DAMAGE, Verdict.APPROVE, 40_000, "In line with the usual cost."));

    Decision decision = model.adjudicate(
        new AdjudicationInput(FILE, opinions, List.of("a payout of $400 is more than this policy can pay for this claim")));

    assertEquals(Decision.pay(15_000, "Paid less the deductible."), decision);
    assertTrue(lastRequest.contains("You are the adjudicator"), lastRequest);
    assertTrue(lastRequest.contains("- DAMAGE: APPROVE, 40000 cents. In line with the usual cost."), lastRequest);
    assertTrue(lastRequest.contains("a payout of $400 is more than this policy can pay for this claim"), lastRequest);
  }

  @Test
  void anOverloadedApiIsReportedAsUnavailableSoTheActionRetries() {
    status = 529;
    response = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}";

    assertThrows(ModelUnavailableException.class, () -> model.intake("My car was stolen."));
  }

  @Test
  void aBadRequestIsNotRetried() {
    status = 400;
    response = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"nope\"}}";

    RuntimeException failure = assertThrows(RuntimeException.class, () -> model.intake("My car was stolen."));
    assertTrue(failure.getMessage().contains("nope"), failure.getMessage());
  }

  @Test
  void aRefusalIsAnErrorNotAnAnswer() {
    response = message("refusal", "");

    IllegalStateException failure = assertThrows(IllegalStateException.class, () -> model.intake("My car was stolen."));
    assertTrue(failure.getMessage().contains("refusal"), failure.getMessage());
  }

  private static String message(String stopReason, String escapedText) {
    return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
        + "\"content\":[{\"type\":\"text\",\"text\":\"" + escapedText + "\"}],"
        + "\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":10}}";
  }
}
