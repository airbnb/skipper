package com.example.trips;

import com.airbnb.skipper.SimpleInjector;
import com.airbnb.skipper.SkipperConfig;
import com.airbnb.skipper.factory.SkipperRuntime;
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler;
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore;

/** Wires Skipper once per process: a file-backed SQLite store and the two services the actions call. */
public final class SkipperSetup {
  private SkipperSetup() {}

  public static SkipperRuntime start(String dbPath, TripPlanner planner, TravelProvider provider) {
    SkipperConfig config = SkipperConfig.forService("trip-agent-example");

    // The store and the scheduler must share one path. This file is the agent's memory: proposals, the
    // traveler's decision and every booking survive a restart.
    config.setWorkflowStore(new SqliteWorkflowStore.Factory(dbPath));
    config.setScheduler(new SqliteScheduler.Factory(dbPath));

    // Skipper's SimpleInjector fills the @Inject fields on the Actions classes. Swapping the planner here is
    // the only difference between the scripted agent and the one that calls a model.
    config.setInjector(
        SimpleInjector.builder()
            .bind(TripPlanner.class, planner)
            .bind(TravelProvider.class, provider)
            .build());

    SkipperRuntime runtime = new SkipperRuntime(config);
    runtime.getSkipperSchedulerManager().get().start();
    return runtime;
  }
}
