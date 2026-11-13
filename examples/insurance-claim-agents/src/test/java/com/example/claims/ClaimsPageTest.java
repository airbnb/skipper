package com.example.claims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.airbnb.skipper.factory.SkipperRuntime;
import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Boots the example the way Main does, on a throwaway store, and drives it over HTTP as the page would. */
class ClaimsPageTest {
  private final InMemoryClaimRecords records = new InMemoryClaimRecords();
  private final InMemoryPayments payments = new InMemoryPayments();
  private final HttpClient http = HttpClient.newHttpClient();
  private SkipperRuntime runtime;
  private HttpServer web;

  @BeforeEach
  void start(@TempDir Path dir) throws Exception {
    runtime = SkipperSetup.start(dir.resolve("claims.db").toString(), new ScriptedModel(), records, payments);
    web = WebServer.start(
        0,
        new ClaimResource(runtime.getWorkflowFactory().get(), records, "scripted"),
        runtime.getAdminResource().get());
  }

  @AfterEach
  void stop() throws Exception {
    web.stop(0);
    runtime.getSkipperSchedulerManager().get().stop();
  }

  @Test
  void aLargeClaimIsSentToTheAdjusterWhoCannotOverpayAndThenApproves() throws Exception {
    String id = send("{\"policyNumber\":\"P-1001\",\"description\":\"I was rear-ended on 2027-03-05.\",\"claimedCents\":420000}");

    String waiting = awaitClaim(id, "\"stage\":\"WITH_ADJUSTER\"");
    assertTrue(waiting.contains("\"recommendation\":{\"pay\":true,\"payoutCents\":395000"), waiting);
    assertTrue(waiting.contains("above the $2,000 limit for automatic approval"), waiting);

    HttpResponse<String> tooMuch = post("api/claims/" + id + "/adjuster", "{\"approve\":true,\"payoutCents\":900000,\"note\":\"all of it\"}");
    assertEquals(409, tooMuch.statusCode());
    assertTrue(tooMuch.body().contains("more than this policy can pay"), tooMuch.body());

    assertEquals(204, post("api/claims/" + id + "/adjuster", "{\"approve\":true,\"payoutCents\":395000,\"note\":\"ok\"}").statusCode());

    String paid = awaitClaim(id, "\"stage\":\"PAID\"");
    assertTrue(paid.contains("\"decidedBy\":\"adjuster\""), paid);
    assertEquals(2, payments.ledger().size());
    assertEquals(409, post("api/claims/" + id + "/adjuster", "{\"approve\":false,\"payoutCents\":0,\"note\":\"late\"}").statusCode());
  }

  @Test
  void aVagueClaimIsAskedAQuestionAndPaidOnceItIsAnswered() throws Exception {
    String id = send("{\"policyNumber\":\"P-1001\",\"description\":\"My car got damaged.\",\"claimedCents\":90000}");

    String asked = awaitClaim(id, "\"stage\":\"NEEDS_INFO\"");
    assertTrue(asked.contains("\"question\":\"what happened to the car, and the date it happened (YYYY-MM-DD)\""), asked);
    assertEquals(400, post("api/claims/" + id + "/info", "{\"text\":\" \"}").statusCode());
    assertEquals(204, post("api/claims/" + id + "/info", "{\"text\":\"A cart left a dent in the door on 2027-03-03.\"}").statusCode());

    String paid = awaitClaim(id, "\"stage\":\"PAID\"");
    assertTrue(paid.contains("\"payoutCents\":65000"), paid);
    assertEquals(409, post("api/claims/" + id + "/info", "{\"text\":\"one more thing\"}").statusCode());
  }

  @Test
  void badInputAndUnknownClaimsAreRefused() throws Exception {
    assertEquals(400, post("api/claims", "{\"policyNumber\":\"P-1001\",\"description\":\" \",\"claimedCents\":1000}").statusCode());
    assertEquals(400, post("api/claims", "{\"policyNumber\":\"P-1001\",\"description\":\"Dent\",\"claimedCents\":0}").statusCode());
    assertEquals(404, get("api/claims/claim-nope").statusCode());
  }

  @Test
  void thePagesAreServedAndTheAdminApiTellsClaimsFromTheirSpecialists() throws Exception {
    String id = send("{\"policyNumber\":\"P-1001\",\"description\":\"A stone cracked my windshield on 2027-03-02.\",\"claimedCents\":40000}");
    awaitClaim(id, "\"stage\":\"PAID\"");

    HttpResponse<String> page = get("");
    assertEquals(200, page.statusCode());
    assertTrue(page.body().contains("<title>Car insurance claims</title>"));
    String setup = get("api/setup").body();
    assertTrue(setup.contains("{\"policyNumber\":\"P-1001\",\"name\":\"Alice Martin\"}"), setup);
    assertTrue(setup.contains("\"label\":\"Cracked windshield\""), setup);

    HttpResponse<String> admin = get("skipper/admin/");
    assertEquals(200, admin.statusCode());
    assertTrue(admin.body().contains("Skipper Admin"));

    // The page lists claims from this endpoint and leaves out anything with a parent: the specialists.
    String listed = get("skipper/admin/workflows?limit=100").body();
    assertTrue(listed.contains("\"workflow_id\":\"" + id + "\""), listed);
    assertTrue(listed.contains("\"workflow_id\":\"" + id + "-fraud\""), listed);
    assertTrue(listed.contains("\"parent_workflow_id\":\"" + id + "\""), listed);
  }

  private String send(String json) throws Exception {
    HttpResponse<String> started = post("api/claims", json);
    assertEquals(200, started.statusCode(), started.body());
    Matcher matcher = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(started.body());
    assertTrue(matcher.find(), started.body());
    return matcher.group(1);
  }

  private String awaitClaim(String id, String expected) throws Exception {
    String body = "";
    for (int i = 0; i < 150; i++) {
      body = get("api/claims/" + id).body();
      if (body.contains(expected)) return body;
      Thread.sleep(200);
    }
    throw new AssertionError("never saw " + expected + " in " + body);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create(WebServer.url(web) + path)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String json) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(WebServer.url(web) + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
