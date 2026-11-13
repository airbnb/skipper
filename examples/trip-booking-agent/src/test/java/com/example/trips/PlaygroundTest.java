package com.example.trips;

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
class PlaygroundTest {
  private final InMemoryTravelProvider provider = new InMemoryTravelProvider();
  private final HttpClient http = HttpClient.newHttpClient();
  private SkipperRuntime runtime;
  private HttpServer web;

  @BeforeEach
  void start(@TempDir Path dir) throws Exception {
    runtime = SkipperSetup.start(dir.resolve("trips.db").toString(), new ScriptedPlanner(), provider);
    web = WebServer.start(
        0,
        new TripResource(runtime.getWorkflowFactory().get(), provider, "scripted"),
        runtime.getAdminResource().get());
  }

  @AfterEach
  void stop() throws Exception {
    web.stop(0);
    runtime.getSkipperSchedulerManager().get().stop();
  }

  @Test
  void aTripIsPlannedSoldOutApprovedAndRebookedOverHttp() throws Exception {
    HttpResponse<String> created =
        post("api/trips", "{\"traveler\":\"alice\",\"request\":\"3 nights in Lisbon\",\"budgetCents\":150000}");
    assertEquals(200, created.statusCode(), created.body());
    String id = field(created.body(), "id");

    String proposed = awaitTrip(id, "\"stage\":\"AWAITING_APPROVAL\"");
    assertTrue(proposed.contains("\"hotelOfferId\":\"HT-BOUTIQUE\""), proposed);
    assertTrue(proposed.contains("\"request\":\"3 nights in Lisbon\""), proposed);

    assertEquals(204, post("api/provider/offers/HT-BOUTIQUE/sold-out", "{\"soldOut\":true}").statusCode());
    assertTrue(get("api/provider").body().contains("{\"offer\":{\"id\":\"HT-BOUTIQUE\""), "the catalog still lists it");
    assertEquals(204, post("api/trips/" + id + "/decision", "{\"approve\":true}").statusCode());

    String booked = awaitTrip(id, "\"stage\":\"BOOKED\"");
    assertTrue(booked.contains("\"hotelOfferId\":\"HT-HOSTEL\""), booked);
    assertTrue(booked.contains("HOTEL HT-BOUTIQUE is sold out, replanning around [FLIGHT]"), booked);
    assertEquals(3, provider.ledger().size());

    // A second answer has nothing to decide.
    assertEquals(409, post("api/trips/" + id + "/decision", "{\"approve\":false}").statusCode());
  }

  @Test
  void badInputAndUnknownTripsAreRefused() throws Exception {
    assertEquals(400, post("api/trips", "{\"request\":\" \",\"budgetCents\":150000}").statusCode());
    assertEquals(400, post("api/trips", "{\"request\":\"Lisbon\",\"budgetCents\":0}").statusCode());
    assertEquals(404, get("api/trips/trip-nope").statusCode());
  }

  @Test
  void bothPagesAreServedAndTheAdminApiListsTheTrip() throws Exception {
    String id = field(post("api/trips", "{\"request\":\"3 nights in Lisbon\",\"budgetCents\":150000}").body(), "id");
    awaitTrip(id, "\"stage\":\"AWAITING_APPROVAL\"");

    HttpResponse<String> playground = get("");
    assertEquals(200, playground.statusCode());
    assertTrue(playground.body().contains("<title>Trip-booking agent</title>"));

    HttpResponse<String> admin = get("skipper/admin/");
    assertEquals(200, admin.statusCode());
    assertTrue(admin.body().contains("Skipper Admin"));

    // The playground lists trips from this endpoint, by this key.
    assertTrue(get("skipper/admin/workflows?limit=20").body().contains("\"workflow_id\":\"" + id + "\""));
    // The admin UI's bulk actions post a JSON list of ids, which the Jackson feature reads.
    assertEquals(200, post("skipper/admin/workflows/bulk-cancel", "[\"" + id + "\"]").statusCode());
  }

  private String awaitTrip(String id, String expected) throws Exception {
    String body = "";
    for (int i = 0; i < 150; i++) {
      body = get("api/trips/" + id).body();
      if (body.contains(expected)) return body;
      Thread.sleep(200);
    }
    throw new AssertionError("never saw " + expected + " in " + body);
  }

  private static String field(String json, String name) {
    Matcher matcher = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
    assertTrue(matcher.find(), json);
    return matcher.group(1);
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
