package com.example.orders.skipper;

import com.airbnb.skipper.factory.SkipperRuntime;
import io.dropwizard.lifecycle.Managed;

/** Starts the scheduler with the server and stops it on shutdown. */
public final class SkipperManaged implements Managed {
  private final SkipperRuntime runtime;

  public SkipperManaged(SkipperRuntime runtime) {
    this.runtime = runtime;
  }

  @Override
  public void start() {
    runtime.getSkipperSchedulerManager().get().start();
  }

  @Override
  public void stop() throws Exception {
    runtime.getSkipperSchedulerManager().get().stop();
  }
}
