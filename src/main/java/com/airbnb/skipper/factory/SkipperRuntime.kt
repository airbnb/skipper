package com.airbnb.skipper.factory

import com.airbnb.skipper.ActionValidator
import com.airbnb.skipper.ExecutionMetricsCollector
import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.Knobs
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.RawRequestContextMiddleware
import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.SkipperInjector
import com.airbnb.skipper.WorkflowFactory
import com.airbnb.skipper.WorkflowValidator
import com.airbnb.skipper.WorkflowsService
import com.airbnb.skipper.admin.AdminResource
import com.airbnb.skipper.internal.ActionErrorMapper
import com.airbnb.skipper.internal.ActionExecutor
import com.airbnb.skipper.internal.CompensationExecutor
import com.airbnb.skipper.internal.NoOpEventPublisher
import com.airbnb.skipper.internal.SkipperEngine
import com.airbnb.skipper.internal.SkipperSchedulerManager
import com.airbnb.skipper.internal.WorkflowExecutor
import com.airbnb.skipper.internal.cluster.BucketPartitioner
import com.airbnb.skipper.internal.cluster.ClusterMembershipManager
import com.airbnb.skipper.internal.scheduler.CompensationFlowTaskHandler
import com.airbnb.skipper.internal.scheduler.ExecutionTimeoutHandler
import com.airbnb.skipper.internal.scheduler.LeaseRenewalManager
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.SchedulerExecutionQueue
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.scheduler.TaskHandler
import com.airbnb.skipper.internal.scheduler.TimerTaskHandler
import com.airbnb.skipper.internal.scheduler.WorkflowExecutionTaskHandler
import com.airbnb.skipper.internal.scheduler.mysql.MySqlScheduler
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler
import com.airbnb.skipper.internal.serde.Serde
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.internal.storage.mysql.MySqlWorkflowStore
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore
import com.airbnb.skipper.util.SkipperInternalDeps
import io.opentracing.util.GlobalTracer
import io.vavr.collection.HashMap
import java.time.Duration
import javax.inject.Provider

/**
 * Holds all resolved Skipper components for a given [SkipperConfig]. Each field is a
 * [javax.inject.Provider] so callers use `.get()` to obtain instances. Singleton providers
 * are backed by [lazy] and return the same instance on every call. Non-singleton providers
 * create a fresh instance each time.
 *
 * Test isolation is achieved by creating a new [SkipperRuntime] per test — no static state,
 * no reset methods.
 */
class SkipperRuntime
    @JvmOverloads
    constructor(
        val config: SkipperConfig,
        val injector: SkipperInjector = config.injector
    ) {
        init {
            validateStorageBackends(config)
        }

        // ---------------------------------------------------------------------------
        // Helpers
        // ---------------------------------------------------------------------------

        /**
         * The store and scheduler default to the embedded SQLite backend. A config written for the
         * MySQL-default era (`mySqlDataSource = ds` and nothing else) would otherwise start silently on
         * an in-memory database that ignores the DataSource, hiding every persisted workflow.
         */
        private fun validateStorageBackends(config: SkipperConfig) {
            val sqliteStore = config.workflowStore is SqliteWorkflowStore.Factory
            val sqliteScheduler = config.scheduler is SqliteScheduler.Factory
            val mysqlStore = config.workflowStore is MySqlWorkflowStore.Factory
            val mysqlScheduler = config.scheduler is MySqlScheduler.Factory
            check(config.mySqlDataSource == null || !(sqliteStore || sqliteScheduler)) {
                "mySqlDataSource is set but the workflow store and scheduler are the embedded SQLite defaults, " +
                    "which would ignore it. Set workflowStore = MySqlWorkflowStore.Factory() and " +
                    "scheduler = MySqlScheduler.Factory() to use MySQL, or remove mySqlDataSource."
            }
            check(config.sqliteDataSource == null || !(mysqlStore || mysqlScheduler)) {
                "sqliteDataSource is set but the workflow store and scheduler are the MySQL factories, which would " +
                    "ignore it. Use SqliteWorkflowStore.Factory() and SqliteScheduler.Factory() for SQLite, or remove " +
                    "sqliteDataSource."
            }
            check(!((sqliteStore && mysqlScheduler) || (mysqlStore && sqliteScheduler))) {
                "workflowStore and scheduler use different backends (SQLite vs MySQL); they must share one database."
            }
        }

        private fun <T> singleton(factory: () -> T): Provider<T> {
            val lazy = lazy(factory)
            return Provider { lazy.value }
        }

        private fun <T> provider(factory: () -> T): Provider<T> {
            return Provider { factory() }
        }

        // ---------------------------------------------------------------------------
        // Strategy-resolved components (from SkipperConfig extension points)
        // ---------------------------------------------------------------------------

        val workflowStore: Provider<WorkflowStore> = singleton {
            config.workflowStore.create(config)
        }

        val scheduler: Provider<Scheduler> = singleton {
            config.scheduler.create(config)
        }

        val serde: Provider<Serde> = singleton {
            config.serde.create(config)
        }

        val clusterMembershipManager: Provider<ClusterMembershipManager> = singleton {
            config.clusterMembershipManager.create(config)
        }

        // ---------------------------------------------------------------------------
        // Internal singleton components
        // ---------------------------------------------------------------------------

        val metrics: Provider<Metrics> = singleton {
            config.metrics.create(config)
        }

        val featureGate: Provider<FeatureGate> = singleton {
            config.featureGate.create(config)
        }

        val knobs: Provider<Knobs> = singleton {
            config.knobs.create(config)
        }

        val executionMetricsCollector: Provider<ExecutionMetricsCollector> = singleton {
            ExecutionMetricsCollector(GlobalTracer.get(), config.spanTagger)
        }

        val actionErrorMapper: Provider<ActionErrorMapper> = singleton {
            ActionErrorMapper(config.exceptionClassifier.create(config))
        }

        val middleware: Provider<RawRequestContextMiddleware> = singleton {
            config.requestContextMiddleware.create(config)
        }

        val actionExecutor: Provider<ActionExecutor> = singleton {
            ActionExecutor(
                workflowStore.get(),
                config.defaultCheckpointMode,
                metrics.get(),
                actionErrorMapper.get(),
                NoOpEventPublisher(),
                executionMetricsCollector.get(),
                GlobalTracer.get(),
                config.contextPropagator
            )
        }

        val actionValidator: Provider<ActionValidator> = provider {
            ActionValidator(serde.get())
        }

        val workflowValidator: Provider<WorkflowValidator> = provider {
            WorkflowValidator(serde.get())
        }

        val internalDeps: Provider<SkipperInternalDeps> = singleton {
            SkipperInternalDeps(
                actionExecutor = actionExecutor.get(),
                skipperEngineProvider = skipperEngine,
                contextPropagator = config.contextPropagator,
                defaultRetryStrategy = config.defaultRetryStrategy
            )
        }

        val workflowExecutor: Provider<WorkflowExecutor> = singleton {
            WorkflowExecutor(
                injector,
                internalDeps.get(),
                middleware.get(),
                config.contextPropagator,
                GlobalTracer.get(),
                metrics.get(),
                executionMetricsCollector.get()
            )
        }

        val compensationExecutor: Provider<CompensationExecutor> = singleton {
            CompensationExecutor(
                injector,
                internalDeps.get(),
                config.workflowOptions,
                middleware.get(),
                GlobalTracer.get(),
                metrics.get(),
                actionExecutor.get(),
                config.contextPropagator,
                config.compensationRetryStrategy,
                config.spanTagger
            )
        }

        val schedulerExecutionQueue: Provider<SchedulerExecutionQueue> = singleton {
            SchedulerExecutionQueue(
                scheduler.get(),
                config.utcClock,
                config.schedulerTaskLeaseDuration
            )
        }

        val leaseRenewalManager: Provider<LeaseRenewalManager> = singleton {
            LeaseRenewalManager(
                scheduler.get(),
                config.utcClock,
                metrics.get(),
                config.leaseRenewalGracePeriod
            )
        }

        val skipperEngine: Provider<SkipperEngine> = singleton {
            SkipperEngine(
                workflowStore.get(),
                schedulerExecutionQueue.get(),
                workflowExecutor.get(),
                scheduler.get(),
                config.mainThreadPool,
                config.utcClock,
                featureGate.get(),
                NoOpEventPublisher(),
                metrics.get(),
                injector
            )
        }

        val executionTimeoutHandler: Provider<ExecutionTimeoutHandler> = provider {
            ExecutionTimeoutHandler(
                scheduler.get(),
                skipperEngine.get(),
                config.utcClock,
                featureGate.get()
            )
        }

        val timerTaskHandler: Provider<TimerTaskHandler> = provider {
            TimerTaskHandler(
                workflowStore.get(),
                scheduler.get(),
                config.taskUnexpectedErrorRetryDelay,
                config.utcClock,
                metrics.get(),
                config.schedulerTaskLeaseDuration,
                featureGate.get()
            )
        }

        val workflowExecutionTaskHandler: Provider<WorkflowExecutionTaskHandler> = provider {
            WorkflowExecutionTaskHandler(
                workflowExecutor.get(),
                config.utcClock,
                workflowStore.get(),
                config.taskUnexpectedErrorRetryDelay,
                injector,
                metrics.get(),
                scheduler.get(),
                NoOpEventPublisher(),
                skipperEngine.get(),
                middleware.get()
            )
        }

        val compensationFlowTaskHandler: Provider<CompensationFlowTaskHandler> = provider {
            CompensationFlowTaskHandler(
                workflowStore.get(),
                compensationExecutor.get(),
                config.taskUnexpectedErrorRetryDelay,
                config.utcClock,
                metrics.get(),
                NoOpEventPublisher(),
                injector,
                middleware.get()
            )
        }

        val workflowsService: Provider<WorkflowsService> = provider {
            WorkflowsService(
                workflowStore.get(),
                skipperEngine.get(),
                scheduler.get()
            )
        }

        val adminResource: Provider<AdminResource> = provider {
            AdminResource(
                workflowStore.get(),
                scheduler.get(),
                skipperEngine.get(),
                workflowsService.get()
            )
        }

        val workflowFactory: Provider<WorkflowFactory> = singleton {
            WorkflowFactory(
                config.workflowOptions,
                config.utcClock,
                injector,
                skipperEngine.get(),
                middleware.get(),
                config.resultPollingTimeLimit,
                Duration.ofMillis(500),
                workflowValidator.get(),
                actionValidator.get(),
                GlobalTracer.get(),
                config.mainThreadPool,
                internalDeps.get()
            )
        }

        val skipperSchedulerManager: Provider<SkipperSchedulerManager> = singleton {
            val taskHandlers: io.vavr.collection.Map<Task.Type, TaskHandler> = HashMap.of(
                Task.Type.WORKFLOW,
                workflowExecutionTaskHandler.get(),
                Task.Type.TIMER,
                timerTaskHandler.get(),
                Task.Type.EXECUTION_TIMEOUT,
                executionTimeoutHandler.get(),
                Task.Type.COMPENSATION,
                compensationFlowTaskHandler.get()
            )
            SkipperSchedulerManager(
                schedulerExecutionQueue.get(),
                scheduler.get(),
                config.mainThreadPool,
                config.taskHandlerThreadPool,
                taskHandlers,
                metrics.get(),
                config.schedulerTaskMaxRetries,
                leaseRenewalManager.get(),
                featureGate.get(),
                knobs.get(),
                clusterMembershipManager.get(),
                BucketPartitioner(),
                config.gracefulShutdownTimeout
            )
        }
    }
