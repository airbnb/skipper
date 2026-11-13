package com.example.trips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.airbnb.skipper.api.WorkflowInstanceStatusView;
import com.airbnb.skipper.factory.SkipperRuntime;
import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Boots the example the way Main does, on a throwaway store, and reads the admin UI over HTTP. */
class AdminUiTest {
  @Test
  void theAdminUiShowsAParkedTrip(@TempDir Path dir) throws Exception {
    SkipperRuntime runtime =
        SkipperSetup.start(dir.resolve("trips.db").toString(), new ScriptedPlanner(), new InMemoryTravelProvider());
    HttpServer adminUi = AdminUi.start(runtime, 0);
    try {
      runtime.getWorkflowFactory().get().builder(TripBookingWorkflow.class, "trip-ui").build()
          .bookTrip(new TripRequest("trip-ui", "alice", "3 nights in Lisbon", 150_000));
      Main.awaitStatus(runtime.getWorkflowFactory().get(), "trip-ui",
          EnumSet.of(WorkflowInstanceStatusView.WAITING), Duration.ofSeconds(30));

      HttpResponse<String> page = get(AdminUi.url(adminUi));
      assertEquals(200, page.statusCode());
      assertTrue(page.body().contains("Skipper Admin"), "the single-page app is served");

      // What the page fetches for an instance: its checkpoints include both planner calls and the search.
      HttpResponse<String> instance = get(AdminUi.url(adminUi) + "workflows/trip-ui");
      assertEquals(200, instance.statusCode());
      assertTrue(instance.body().contains("WAITING"), instance.body());
      assertTrue(instance.body().contains("PlannerActions"), instance.body());

      // The UI's bulk actions post a JSON list of ids, which is what the Jackson feature is registered for.
      HttpResponse<String> bulk = HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create(AdminUi.url(adminUi) + "workflows/bulk-cancel"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString("[\"trip-ui\"]"))
              .build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, bulk.statusCode(), bulk.body());
    } finally {
      adminUi.stop(0);
      runtime.getSkipperSchedulerManager().get().stop();
    }
  }

  private static HttpResponse<String> get(String url) throws Exception {
    return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
  }
}
