package com.airbnb.skipper.testutils;

import static org.mockito.Mockito.mock;

import com.airbnb.skipper.ComponentFactory;
import com.airbnb.skipper.FeatureGate;
import com.airbnb.skipper.IWorkflowFactory;
import com.airbnb.skipper.Knobs;
import com.airbnb.skipper.RawRequestContextMiddleware;
import com.airbnb.skipper.SimpleInjector;
import com.airbnb.skipper.SkipperConfig;
import com.airbnb.skipper.factory.SkipperRuntime;
import com.airbnb.skipper.internal.SkipperEngine;
import com.airbnb.skipper.internal.SkipperSchedulerManager;
import com.airbnb.skipper.internal.scheduler.LeaseRenewalManager;
import com.airbnb.skipper.internal.scheduler.Scheduler;
import com.airbnb.skipper.internal.scheduler.SchedulerExecutionQueue;
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler;
import com.airbnb.skipper.internal.storage.WorkflowStore;
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore;
import com.airbnb.skipper.testutils.trace.Tracing;
import com.airbnb.skipper.util.SkipperInternalDeps;
import java.time.Clock;

/**
 * Convenience wrapper for tests. Creates a {@link SkipperConfig} with sensible test defaults
 * (mocked FeatureGate, Knobs, etc.) and a {@link SkipperRuntime} on top of it.
 *
 * <p>Tests can customize the config before accessing the runtime:
 *
 * <pre>{@code
 * TestRuntime testRuntime = new TestRuntime();
 * testRuntime.getConfig().setSchedulerTaskMaxRetries(5);
 * SkipperRuntime runtime = testRuntime.getRuntime();
 * }</pre>
 *
 * <p>Storage defaults to the SQLite backend running with zero configuration against an ephemeral,
 * per-{@code TestRuntime} shared in-memory database (see {@code
 * JdbcTransactionManager.SqliteFactory}). This keeps the OSS test harness free of any internal
 * storage dependency while still exercising the full JDBC store / scheduler code paths.
 * Storage-specific tests override the data source via {@code getConfig().setMySqlDataSource(...)} /
 * {@code setSqliteDataSource(...)}.
 *
 * <p>Request context is carried by the OSS-safe {@link TestRequestContext}: the runtime wires
 * {@link TestRequestContextMiddleware} (installs the context on the thread-local around each
 * invocation) and {@link TestRequestContextSerde} (round-trips it across persistence), so
 * propagation tests can assert context survives a persistence reload without any auth machinery.
 */
public class TestRuntime {
  private final SkipperConfig config;
  private SkipperRuntime runtime;

  /**
   * The mock {@link FeatureGate} created during construction. Tests can stub behaviour on this mock
   * before the runtime is materialised (e.g. {@code Mockito.when(deps.featureGate...)}).
   */
  public final FeatureGate featureGate;

  public TestRuntime() {
    config = SkipperConfig.forService("test-service");
    // Use a unique tenant per TestRuntime to isolate persisted data between tests.
    config.setTenant("test-" + java.util.UUID.randomUUID());
    // Default to the SQLite backend, which self-bootstraps an ephemeral in-memory database with
    // zero external infrastructure (no external database server).
    config.setWorkflowStore(new SqliteWorkflowStore.Factory());
    config.setScheduler(new SqliteScheduler.Factory());

    FeatureGate fg = mock(FeatureGate.class);
    this.featureGate = fg;
    config.setFeatureGate(c -> fg);
    config.setKnobs(c -> mock(Knobs.class));

    // Use short durations for tests so that scheduler retries happen quickly.
    config.setSchedulerTaskLeaseDuration(java.time.Duration.ofSeconds(10));
    config.setTaskUnexpectedErrorRetryDelay(java.time.Duration.ZERO);

    // Wire the OSS test context plumbing so workflows that span multiple actions (e.g.
    // coroutine-suspended workflows) keep their TestRequestContext across persistence, and
    // handlers can read the installed context off the thread-local.
    config.setRequestContextMiddleware(
        (ComponentFactory<RawRequestContextMiddleware>) c -> new TestRequestContextMiddleware());
    config.setRequestContextSerde(c -> new TestRequestContextSerde());
  }

  public SkipperConfig getConfig() {
    return config;
  }

  /**
   * Sets the clock on the underlying config. Convenience setter so tests can write {@code
   * deps.clock = Clock.systemUTC()}.
   */
  public void setClock(Clock clock) {
    config.setUtcClock(clock);
  }

  /** Returns the clock from the underlying config. */
  public Clock getClock() {
    return config.getUtcClock();
  }

  /**
   * Registers a binding in the underlying {@link SimpleInjector}. This allows tests to provide
   * additional dependencies (e.g. stateful singletons) that are injected into workflows/actions.
   *
   * <p>Must be called <em>before</em> {@link #getRuntime()} is first invoked.
   */
  public <T> void addBinding(Class<T> type, T instance) {
    if (config.getInjector() instanceof SimpleInjector) {
      SimpleInjector si = (SimpleInjector) config.getInjector();
      si.register(type, instance);
    } else {
      throw new IllegalStateException(
          "addBinding() requires a SimpleInjector, but the injector is "
              + config.getInjector().getClass().getSimpleName());
    }
  }

  public SkipperRuntime getRuntime() {
    if (runtime == null) {
      Tracing.installIfRequested(config);
      runtime = new SkipperRuntime(config);
      // Register IWorkflowFactory for user code that @Inject-s it.
      // Some code uses @Named("SkipperWorkflowFactory") IWorkflowFactory, some uses unqualified.
      // Skipper-internal fields (ActionExecutor, SkipperEngine, ContextPropagator, RetryStrategy)
      // are now set directly via SkipperInternalDeps in injectWorkflowMembers().
      if (config.getInjector() instanceof SimpleInjector) {
        SimpleInjector si = (SimpleInjector) config.getInjector();
        si.register(IWorkflowFactory.class, runtime.getWorkflowFactory().get());
        si.register(
            IWorkflowFactory.class,
            com.airbnb.skipper.SkipperAnnotationNames.SKIPPER_WORKFLOW_FACTORY,
            runtime.getWorkflowFactory().get());
      }
    }
    return runtime;
  }

  // --- Convenience accessors that delegate to the runtime ---

  public Scheduler getScheduler() {
    return getRuntime().getScheduler().get();
  }

  public WorkflowStore getWorkflowStore() {
    return getRuntime().getWorkflowStore().get();
  }

  public SchedulerExecutionQueue getSchedulerExecutionQueue() {
    return getRuntime().getSchedulerExecutionQueue().get();
  }

  public LeaseRenewalManager getLeaseRenewalManager() {
    return getRuntime().getLeaseRenewalManager().get();
  }

  public SkipperEngine getSkipperEngine() {
    return getRuntime().getSkipperEngine().get();
  }

  public IWorkflowFactory getWorkflowFactory() {
    return getRuntime().getWorkflowFactory().get();
  }

  public SkipperSchedulerManager getSchedulerManager() {
    return getRuntime().getSkipperSchedulerManager().get();
  }

  public FeatureGate getFeatureGate() {
    return getRuntime().getFeatureGate().get();
  }

  public SkipperInternalDeps getInternalDeps() {
    return getRuntime().getInternalDeps().get();
  }
}
