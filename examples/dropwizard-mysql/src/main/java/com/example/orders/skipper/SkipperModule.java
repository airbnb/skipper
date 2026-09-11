package com.example.orders.skipper;

import com.airbnb.skipper.IWorkflowFactory;
import com.airbnb.skipper.SkipperConfig;
import com.airbnb.skipper.factory.SkipperRuntime;
import com.airbnb.skipper.internal.scheduler.mysql.MySqlScheduler;
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler;
import com.airbnb.skipper.internal.storage.mysql.MySqlWorkflowStore;
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore;
import com.example.orders.OrdersConfiguration;
import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import javax.sql.DataSource;

/** One Skipper runtime per process, wired to whichever store the YAML selects and to Guice for injection. */
public final class SkipperModule extends AbstractModule {
  private final OrdersConfiguration.SkipperFactory settings;
  private final DataSource dataSource; // null for the sqlite store

  public SkipperModule(OrdersConfiguration.SkipperFactory settings, DataSource dataSource) {
    this.settings = settings;
    this.dataSource = dataSource;
  }

  @Provides
  @Singleton
  SkipperRuntime skipperRuntime(Injector guice) {
    SkipperConfig config = SkipperConfig.forService("orders-dropwizard");
    if ("sqlite".equals(settings.store)) {
      config.setWorkflowStore(new SqliteWorkflowStore.Factory(settings.sqlitePath));
      config.setScheduler(new SqliteScheduler.Factory(settings.sqlitePath));
    } else {
      // Several replicas can share this store; the scheduler leases work between them.
      config.setWorkflowStore(new MySqlWorkflowStore.Factory());
      config.setScheduler(new MySqlScheduler.Factory());
      config.setMySqlDataSource(dataSource);
    }
    config.setInjector(new GuiceSkipperInjector(guice));
    return new SkipperRuntime(config);
  }

  @Provides
  @Singleton
  IWorkflowFactory workflowFactory(SkipperRuntime runtime) {
    return runtime.getWorkflowFactory().get();
  }
}
