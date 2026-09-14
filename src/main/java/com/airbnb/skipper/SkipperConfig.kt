package com.airbnb.skipper

import com.airbnb.skipper.internal.DefaultExceptionClassifier
import com.airbnb.skipper.internal.ExceptionClassifier
import com.airbnb.skipper.internal.cluster.ClusterMembershipManager
import com.airbnb.skipper.internal.cluster.SingleMemberClusterMembershipManager
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.mysql.MySqlScheduler
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler
import com.airbnb.skipper.internal.serde.Serde
import com.airbnb.skipper.internal.serde.SimplePojoSerde
import com.airbnb.skipper.internal.serde.SmartSerde
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.internal.storage.mysql.MySqlWorkflowStore
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore
import com.airbnb.skipper.util.SpanTagger
import com.google.common.util.concurrent.ThreadFactoryBuilder
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.sql.DataSource

/**
 * A configuration object for Skipper. This object is used to configure the various
 * components of the system.
 *
 * All nullable fields are optional and Skipper will use defaults if they are not provided.
 *
 * Note: this is intentionally a regular `class` (not a `data class`) so that Kotlin does not
 * auto-generate a `copy(...)` method. Several critical fields ([tenant], [defaultCheckpointMode])
 * are body-declared with custom setter logic and would NOT be preserved by `copy()` — silently
 * resetting [tenant] to `"default"` would route reads/writes to the wrong tenant namespace, which
 * would be catastrophic. Constructing a new config or mutating in place is the only supported way
 * to derive one config from another.
 */
class SkipperConfig(
    /** The provider for the `Clock` to use throughout the system. */
    var utcClock: Clock = Clock.systemUTC(),
    /**
     * The factory for the `WorkflowStore` implementation to use. Defaults to the embedded SQLite store,
     * which needs no configuration: with [sqliteDataSource] unset it runs against an ephemeral in-memory
     * database. Set [MySqlWorkflowStore.Factory] (with [mySqlDataSource]) for a shared production store.
     */
    var workflowStore: ComponentFactory<out WorkflowStore> = SqliteWorkflowStore.Factory(),
    /**
     * The factory for the `Scheduler` implementation to use. Defaults to the embedded SQLite scheduler;
     * pair it with the same backend as [workflowStore].
     */
    var scheduler: ComponentFactory<out Scheduler> = SqliteScheduler.Factory(),
    /**
     * The factory for the internal [SimplePojoSerde] Skipper uses to encode workflow-state
     * envelopes and extra-request-data. Defaults to a serde built against Skipper's own pinned
     * Jackson version.
     *
     * This is the plug for the diamond-dependency case: a host whose runtime classpath resolves an
     * ABI-incompatible `jackson-module-kotlin` (e.g. one pinned higher by another SDK) sets this to
     * a serde built in its OWN pinned versions, so the Kotlin module is constructed against the
     * Jackson the host actually runs — avoiding the `NoSuchMethodError` that a shared, ABI-frozen
     * default serde would cause. See [SimplePojoSerde.buildObjectMapper].
     *
     * The default [serde] strategy ([SmartSerde]) is threaded through this same factory, so plugging
     * here also fixes the composite serde — not just the raw POJO slot.
     */
    var simplePojoSerde: ComponentFactory<out SimplePojoSerde> = ComponentFactory { SimplePojoSerde() },
    /** The factory for the `Serde` implementation to use. */
    var serde: ComponentFactory<out Serde> = ComponentFactory { SmartSerde(it.simplePojoSerde.create(it)) },
    /** The factory for the `ClusterMembershipManager` implementation to use. */
    var clusterMembershipManager: ComponentFactory<out ClusterMembershipManager> = SingleMemberClusterMembershipManager.Factory(),
    /** Some cluster membership manager implementations may need to be configured with a heartbeat interval. */
    var clusterHeartBeatInterval: Duration = Duration.ofSeconds(10),
    /**
     * The identifier this instance registers under in the cluster membership table. Must be unique
     * among the instances sharing a store and [tenant]. Defaults (`null`) to the local hostname,
     * which is fine for one instance per host; set it explicitly (e.g. to a pod/container name) when
     * several instances run on one host, otherwise they collide on one member row and receive the
     * same task partition. Only consulted by cluster-aware managers such as
     * `JdbcClusterMembershipManager`.
     */
    var clusterMemberName: String? = null,
    /** This is the time each task has to finish processing before it is considered staled and is retried. */
    var schedulerTaskLeaseDuration: Duration = Duration.ofMinutes(8),
    /** The maximum number of times a task can be retried before it is considered failed. */
    var schedulerTaskMaxRetries: Int = 100,
    /** The time to wait before retrying a task that failed unexpectedly. */
    var taskUnexpectedErrorRetryDelay: Duration = Duration.ofSeconds(30),
    /** The main thread pool used by Skipper internals. */
    var mainThreadPool: ExecutorService = Executors.newCachedThreadPool(ThreadFactoryBuilder().setNameFormat("skipper-main-%d").build()),
    /** The thread pool used to process workflow executions. */
    var taskHandlerThreadPool: ExecutorService = Executors.newCachedThreadPool(ThreadFactoryBuilder().setNameFormat("task-handler-%d").build()),
    /** The default retry strategy to use for actions that do not specify one. */
    var defaultRetryStrategy: RetryStrategy = FixedRetryStrategy(Duration.ofMillis(100), 5),
    /** The retry strategy to use for compensation actions when they encounter retryable errors. */
    var compensationRetryStrategy: RetryStrategy = ExponentialRetryStrategy(Duration.ofSeconds(30), 20, 1.5, Duration.ofMinutes(3)),
    /**
     * The maximum time to wait for a result to be available when a workflow is on a retry cycle.
     * This is just the time that the client will actively wait for the result, the actual execution will
     * continue regardless.
     */
    var resultPollingTimeLimit: Duration = Duration.ofSeconds(30),
    /** The name of the service using Skipper. This must be universally unique across all users of Skipper. */
    var serviceName: String,
    var workflowTimeout: Duration? = null,
    /**
     * The JDBC [DataSource] for the MySQL database used by Skipper.
     * Required when using [MySqlWorkflowStore] or [MySqlScheduler].
     */
    var mySqlDataSource: DataSource? = null,
    /**
     * An optional pre-built JDBC [DataSource] for the SQLite database used by Skipper. Only
     * consulted when using the SQLite-backed `WorkflowStore`/`Scheduler`. When `null` (the default),
     * the SQLite backend runs with zero configuration against an ephemeral shared in-memory database.
     * Supply a `SQLiteDataSource` with a `jdbc:sqlite:<path>` URL for a durable on-disk database.
     */
    var sqliteDataSource: DataSource? = null,
    /**
     * This is the amount of time before lease expiration after which the lease manager will attempt to renew the lease.
     * E.g. if the lease ends at 10:00 and the grace period is 5 seconds, the lease manager will attempt to renew the lease at 9:59:55.
     */
    var leaseRenewalGracePeriod: Duration = Duration.ofSeconds(5),
    /**
     * Legacy dev-mode isolation flag. The open-source core does not act on this field directly; it
     * is retained for backward compatibility and is consulted only by integration layers that
     * install a dev-mode [shouldIsolateTenant] policy (e.g. Airbnb's `AirbnbSkipperConfig`, which
     * isolates dev tenants only when this is true).
     *
     * Prefer configuring [shouldIsolateTenant] directly — it is explicit and environment-agnostic.
     */
    var uniqueTenantInDevMode: Boolean = true,
    /**
     * The maximum time to wait per executor for in-flight tasks to complete during graceful shutdown.
     * Each executor (main and task handler) will wait up to this duration before forcefully terminating.
     */
    var gracefulShutdownTimeout: Duration = Duration.ofSeconds(30),
    /**
     * The [ContextPropagator] instance used to capture and restore thread-local context
     * (request context, tracing spans, MDC, etc.) across coroutine suspension boundaries.
     *
     * Defaults to [ContextPropagator.NOOP] which does nothing. To propagate request context,
     * tracing spans, or other thread-local state, provide a custom [ContextPropagator]
     * implementation.
     */
    var contextPropagator: ContextPropagator = ContextPropagator.NOOP,
    /** The injector used to instantiate user-defined Workflow, Action, and CallbackHandler classes. */
    var injector: SkipperInjector = SimpleInjector.builder().build(),
    /** The factory for the FeatureGate. Defaults to InMemoryFeatureGate (all features enabled). */
    var featureGate: ComponentFactory<out FeatureGate> = ComponentFactory { InMemoryFeatureGate() },
    /** The factory for the Knobs. Defaults to InMemoryKnobs (no values configured). */
    var knobs: ComponentFactory<out Knobs> = ComponentFactory { InMemoryKnobs() },
    /** The workflow options to use globally. */
    var workflowOptions: WorkflowOptions = WorkflowOptions(),
    /**
     * The factory for the [RawRequestContextMiddleware] — the host's hook into Skipper's
     * request-context lifecycle (onCreate, beforeExecution, afterExecution). Defaults to
     * [RawRequestContextMiddleware.NOOP], which is appropriate for deployments that don't
     * carry per-request identity. Host services that need typed request context can wire a
     * custom middleware (e.g. one extending the typed [RequestContextMiddleware] for ergonomic
     * typed signatures).
     */
    var requestContextMiddleware: ComponentFactory<out RawRequestContextMiddleware> =
        ComponentFactory { RawRequestContextMiddleware.NOOP },
    /**
     * The factory for the [RequestContextSerde] — the host's plug for how the opaque
     * request-context payload is encoded on the wire. Defaults to [RequestContextSerde.NOOP],
     * which drops the payload on write and reads back `null`. Host services can wire a custom
     * serde to persist their own request-context format.
     */
    var requestContextSerde: ComponentFactory<out RequestContextSerde> =
        ComponentFactory { RequestContextSerde.NOOP },
    /** The ExceptionClassifier used to determine whether an action exception is retryable. Defaults to [DefaultExceptionClassifier]. */
    var exceptionClassifier: ComponentFactory<out ExceptionClassifier> = ComponentFactory { DefaultExceptionClassifier() },
    /**
     * The factory for the [Metrics] implementation Skipper will emit through. Defaults to
     * [NoOpMetrics] so that `common/skipper` stays free of any environment-specific observability
     * dependencies. Host services can install a custom [Metrics] implementation backed by their
     * own metrics registry.
     */
    var metrics: ComponentFactory<out Metrics> = DEFAULT_METRICS_FACTORY,
    /**
     * Strategy used to apply the resource tag to spans created by Skipper. Defaults to
     * [SpanTagger.DEFAULT], which writes the OpenTracing-standard `"resource"` string tag.
     * Custom implementations can override this.
     */
    var spanTagger: SpanTagger = SpanTagger.DEFAULT,
) {
    companion object {
        @JvmField
        val DEFAULT_METRICS_FACTORY: ComponentFactory<out Metrics> = ComponentFactory { NoOpMetrics.INSTANCE }

        /**
         * The default [tablePrefix]. Reflects the rename from the legacy `tempo_` naming to
         * `skipper_`; the trailing underscore is part of the prefix.
         */
        const val DEFAULT_TABLE_PREFIX = "skipper_"

        /** Allowed shape for [tablePrefix]: identifier-safe characters only (may be empty). */
        private val TABLE_PREFIX_PATTERN = Regex("^[A-Za-z0-9_]*$")

        @JvmStatic
        fun forService(serviceName: String): SkipperConfig {
            return SkipperConfig(serviceName = serviceName)
        }
    }

    /**
     * If set to true, action checkpoints will be persisted immediately after execution. Otherwise, the action
     * checkpoints will be flushed to disk in batch after the workflow execution completes or reaches a state where
     * it needs to either retry or sleep.
     *
     * Setting this to true is not recommended for workflows with a high qps or a large number of actions, since it
     * would result in increased stress on the underlying persistence layer. On the other hand, setting this to true
     * will provide a stronger idempotency for actions
     **/
    var defaultCheckpointMode: CheckpointMode = CheckpointMode.EVENTUAL_CHECKPOINT
        set(value) {
            if (value == CheckpointMode.DEFAULT) {
                throw IllegalArgumentException("Cannot set defaultCheckpointMode to DEFAULT")
            }
            field = value
        }

    private val devModeSuffix: String by lazy { UUID.randomUUID().toString() }

    /**
     * Policy that decides, at read time, whether a stable per-config suffix is appended to
     * [tenant]. It receives the raw (unsuffixed) tenant value and returns true to isolate.
     *
     * Defaults to never isolating. Hosts that run several deployments against one backing store
     * (e.g. several developers running ephemeral instances) opt in by setting this — for example
     * `config.shouldIsolateTenant = { isDevEnvironment }`. Airbnb's `AirbnbSkipperConfig` installs
     * its own policy here.
     */
    var shouldIsolateTenant: (rawTenant: String) -> Boolean = { false }

    /**
     * The per-service namespace key for every workflow, scheduler task, and cluster-membership
     * row in the backing store.
     *
     * When [shouldIsolateTenant] returns true for the raw value, a stable per-config suffix is
     * appended so that concurrent deployments sharing one backing store (e.g. several developers
     * running ephemeral instances) don't collide. The decision is re-evaluated on every read — not
     * cached — so it always reflects the current [shouldIsolateTenant] policy and tenant value;
     * this matters because [tenant] is commonly assigned after the config is constructed. The
     * suffix itself is computed once (via `lazy`) and reused, so a given config instance yields a
     * stable value.
     */
    var tenant: String = "default"
        get() = if (shouldIsolateTenant(field)) "$field-$devModeSuffix" else field

    /**
     * The prefix applied to every Skipper table name — the single source of truth for table
     * naming, used identically by the Flyway migrations (DDL) and the runtime store/scheduler
     * queries. Table names are formed as `tablePrefix + "workflow_instances"`, so the default
     * yields `skipper_workflow_instances` (the trailing underscore is part of the prefix).
     *
     * Defaults to [DEFAULT_TABLE_PREFIX] (`"skipper_"`), reflecting the rename from the legacy
     * `tempo_` naming. Existing ("tempo") deployments MUST set this to `"tempo_"` so their
     * runtime queries keep resolving to the pre-existing `tempo_` tables.
     *
     * Because the value is trusted config interpolated (not bound) into SQL, it is restricted to
     * `[A-Za-z0-9_]*` to guard against typos and injection.
     */
    var tablePrefix: String = DEFAULT_TABLE_PREFIX
        set(value) {
            require(TABLE_PREFIX_PATTERN.matches(value)) {
                "tablePrefix must match ${TABLE_PREFIX_PATTERN.pattern} but was \"$value\""
            }
            field = value
        }
}
