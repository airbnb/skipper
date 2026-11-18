package com.example.claims;

import com.airbnb.skipper.IWorkflowFactory;
import com.airbnb.skipper.SimpleInjector;
import com.airbnb.skipper.SkipperConfig;
import com.airbnb.skipper.factory.SkipperRuntime;
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler;
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore;

/** Wires Skipper once per process: a file-backed SQLite store and the services the workflows and actions use. */
public final class SkipperSetup {
  private SkipperSetup() {}

  public static SkipperRuntime start(String dbPath, ClaimsModel model, ClaimRecords records, Payments payments) {
    SkipperConfig config = SkipperConfig.forService("claims-example");

    // The store and the scheduler must share one path. This file is the agents' memory: every opinion, the
    // claimant's answers, the adjuster's decision and the payment survive a restart.
    config.setWorkflowStore(new SqliteWorkflowStore.Factory(dbPath));
    config.setScheduler(new SqliteScheduler.Factory(dbPath));

    // Skipper's SimpleInjector fills the @Inject fields on workflows and actions. Swapping the model here is
    // the only difference between the scripted agents and the ones that call Claude.
    SimpleInjector injector =
        SimpleInjector.builder()
            .bind(ClaimsModel.class, model)
            .bind(ClaimRecords.class, records)
            .bind(Payments.class, payments)
            .build();
    config.setInjector(injector);

    SkipperRuntime runtime = new SkipperRuntime(config);
    // The claim workflow starts its specialists, and each specialist signals the claim, through the workflow
    // factory. The factory only exists once the runtime does, so it is registered afterwards.
    injector.register(IWorkflowFactory.class, runtime.getWorkflowFactory().get());
    runtime.getSkipperSchedulerManager().get().start();
    return runtime;
  }
}
