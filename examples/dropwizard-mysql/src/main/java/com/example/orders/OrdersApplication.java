package com.example.orders;

import com.airbnb.skipper.factory.SkipperRuntime;
import com.example.orders.skipper.SkipperInternalsModule;
import com.example.orders.skipper.SkipperManaged;
import com.example.orders.skipper.SkipperModule;
import com.example.orders.web.OrderResource;
import com.google.inject.Guice;
import com.google.inject.Injector;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.db.ManagedDataSource;
import java.util.Map;
import org.flywaydb.core.Flyway;

/**
 * Dropwizard + Guice + Skipper. Start with {@code server config.yml} (MySQL) or {@code server config-sqlite.yml}.
 *
 * <p>The admin UI is a plain JAX-RS resource, so it is registered with Jersey like any other and served at
 * /skipper/admin/ on the application port.
 */
public class OrdersApplication extends Application<OrdersConfiguration> {
  public static void main(String[] args) throws Exception {
    new OrdersApplication().run(args);
  }

  @Override
  public void initialize(Bootstrap<OrdersConfiguration> bootstrap) {
    // ${MYSQL_URL:-default} style substitution in config.yml.
    bootstrap.setConfigurationSourceProvider(new SubstitutingSourceProvider(
        bootstrap.getConfigurationSourceProvider(), new EnvironmentVariableSubstitutor(false)));
  }

  @Override
  public void run(OrdersConfiguration configuration, Environment environment) {
    ManagedDataSource dataSource = null;
    if (!"sqlite".equals(configuration.getSkipper().store)) {
      dataSource = configuration.getDatabase().build(environment.metrics(), "orders");
      environment.lifecycle().manage(dataSource);
      // Skipper does not create its MySQL schema. Its migrations ship in skipper-core under db/migration,
      // templated on a table-prefix placeholder; applying them here is idempotent, so every start may do it.
      Flyway.configure()
          .dataSource(dataSource)
          .locations("classpath:db/migration")
          .placeholders(Map.of("tablePrefix", "skipper_"))
          .load()
          .migrate();
    }

    Injector injector = Guice.createInjector(
        new OrdersModule(),
        new SkipperModule(configuration.getSkipper(), dataSource),
        new SkipperInternalsModule());
    SkipperRuntime runtime = injector.getInstance(SkipperRuntime.class);

    environment.lifecycle().manage(new SkipperManaged(runtime));
    environment.jersey().register(injector.getInstance(OrderResource.class));
    environment.jersey().register(runtime.getAdminResource().get());
  }
}
