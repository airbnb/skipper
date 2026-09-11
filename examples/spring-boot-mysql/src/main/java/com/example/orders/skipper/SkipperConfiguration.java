package com.example.orders.skipper;

import com.airbnb.skipper.IWorkflowFactory;
import com.airbnb.skipper.SkipperConfig;
import com.airbnb.skipper.factory.SkipperRuntime;
import com.airbnb.skipper.internal.scheduler.mysql.MySqlScheduler;
import com.airbnb.skipper.internal.storage.mysql.MySqlWorkflowStore;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One Skipper runtime per process, on the application's own MySQL DataSource. Flyway (Spring Boot managed) has
 * applied Skipper's schema by the time the scheduler starts, because lifecycle beans start after every singleton
 * is initialised.
 */
@Configuration
public class SkipperConfiguration {

  @Bean
  SkipperRuntime skipperRuntime(DataSource dataSource, AutowireCapableBeanFactory beanFactory) {
    SkipperConfig config = SkipperConfig.forService("orders-spring");
    // Several replicas can share this store; the scheduler leases work between them.
    config.setWorkflowStore(new MySqlWorkflowStore.Factory());
    config.setScheduler(new MySqlScheduler.Factory());
    config.setMySqlDataSource(dataSource);
    config.setInjector(new SpringSkipperInjector(beanFactory));
    return new SkipperRuntime(config);
  }

  @Bean
  IWorkflowFactory workflowFactory(SkipperRuntime runtime) {
    return runtime.getWorkflowFactory().get();
  }

  /** Starts the scheduler once the context is up and stops it first on shutdown. */
  @Bean
  SmartLifecycle skipperScheduler(SkipperRuntime runtime) {
    return new SmartLifecycle() {
      private volatile boolean running;

      @Override
      public void start() {
        runtime.getSkipperSchedulerManager().get().start();
        running = true;
      }

      @Override
      public void stop() {
        try {
          runtime.getSkipperSchedulerManager().get().stop();
        } catch (Exception e) {
          throw new IllegalStateException("Skipper scheduler did not stop cleanly", e);
        }
        running = false;
      }

      @Override
      public boolean isRunning() {
        return running;
      }
    };
  }
}
