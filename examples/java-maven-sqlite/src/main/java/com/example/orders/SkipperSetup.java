package com.example.orders;

import com.airbnb.skipper.SimpleInjector;
import com.airbnb.skipper.SkipperConfig;
import com.airbnb.skipper.factory.SkipperRuntime;
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler;
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore;

/**
 * Wires Skipper once per process. Build the runtime once and hold it for the life of the process: it owns the
 * scheduler that drives workflows forward and hands out the workflow factory.
 */
public final class SkipperSetup {
  private SkipperSetup() {}

  public static SkipperRuntime start(String dbPath, InventoryService inventory, PaymentGateway payments, Carrier carrier) {
    SkipperConfig config = SkipperConfig.forService("orders-example");

    // A file-backed SQLite store survives restarts on a single node. The store and the scheduler must share
    // one path. Leave both unset for the in-memory default (fine for a quick look, gone at exit).
    config.setWorkflowStore(new SqliteWorkflowStore.Factory(dbPath));
    config.setScheduler(new SqliteScheduler.Factory(dbPath));

    // No DI framework here: Skipper's own SimpleInjector fills the @Inject fields on the Actions classes.
    // A service on Spring or Guice adapts its container to SkipperInjector instead; see the framework examples.
    config.setInjector(
        SimpleInjector.builder()
            .bind(InventoryService.class, inventory)
            .bind(PaymentGateway.class, payments)
            .bind(Carrier.class, carrier)
            .build());

    SkipperRuntime runtime = new SkipperRuntime(config);
    runtime.getSkipperSchedulerManager().get().start();
    return runtime;
  }
}
