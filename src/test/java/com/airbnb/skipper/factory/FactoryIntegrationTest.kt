package com.airbnb.skipper.factory

import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.Knobs
import com.airbnb.skipper.SimpleInjector
import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.WorkflowFactory
import com.airbnb.skipper.internal.SkipperSchedulerManager
import com.airbnb.skipper.internal.scheduler.Task
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore
import com.airbnb.skipper.testutils.SqliteTestSetupExtension
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.mock

/**
 * Integration-style tests that construct [SkipperSchedulerManager] and [WorkflowFactory] entirely
 * through [SkipperRuntime], mirroring the production wiring path. This verifies the full dependency
 * chain works end-to-end without Guice DI.
 *
 * This exercises the core [SkipperRuntime] wiring mechanism, so it lives in the OSS core module and
 * uses the OSS SQLite-backed factories ([SqliteWorkflowStore.Factory] / [SqliteScheduler.Factory])
 * against an in-memory SQLite database ([SqliteTestSetupExtension]).
 */
@ExtendWith(SqliteTestSetupExtension::class)
class FactoryIntegrationTest {
    // --- SkipperSchedulerManager creation ---

    @Test
    fun schedulerManager_isCreatedSuccessfully() {
        assertThat(schedulerManager).isNotNull()
        assertThat(schedulerManager).isInstanceOf(SkipperSchedulerManager::class.java)
    }

    @Test
    fun schedulerManager_hasAllFourTaskHandlerTypes() {
        val taskHandlers = getField<Any>(schedulerManager, "taskHandlers")
        val taskHandlersStr = taskHandlers.toString()
        for (type in Task.Type.values()) {
            assertThat(taskHandlersStr).contains(type.name)
        }
    }

    @Test
    fun schedulerManager_hasCorrectMaxRetries() {
        val maxRetries = getField<Int>(schedulerManager, "maxRetries")
        assertThat(maxRetries).isEqualTo(config.schedulerTaskMaxRetries)
    }

    @Test
    fun schedulerManager_hasCorrectGracefulShutdownTimeout() {
        val timeout = getField<Duration>(schedulerManager, "gracefulShutdownTimeout")
        assertThat(timeout).isEqualTo(config.gracefulShutdownTimeout)
    }

    @Test
    fun schedulerManager_allDependenciesAreNonNull() {
        assertThat(getField<Any?>(schedulerManager, "schedulerQueue")).isNotNull()
        assertThat(getField<Any?>(schedulerManager, "scheduler")).isNotNull()
        assertThat(getField<Any?>(schedulerManager, "executor")).isNotNull()
        assertThat(getField<Any?>(schedulerManager, "taskHandlerExecutor")).isNotNull()
        assertThat(getField<Any?>(schedulerManager, "taskHandlers")).isNotNull()
        assertThat(getField<Any?>(schedulerManager, "metrics")).isNotNull()
        assertThat(getField<Any?>(schedulerManager, "leaseManager")).isNotNull()
        assertThat(getField<Any?>(schedulerManager, "featureGate")).isNotNull()
        assertThat(getField<Any?>(schedulerManager, "knobs")).isNotNull()
        assertThat(getField<Any?>(schedulerManager, "clusterMembershipManager")).isNotNull()
        assertThat(getField<Any?>(schedulerManager, "partitioner")).isNotNull()
    }

    @Test
    fun schedulerManager_threadPoolsMatchConfig() {
        assertThat(getField<Any?>(schedulerManager, "executor"))
            .isSameAs(config.mainThreadPool)
        assertThat(getField<Any?>(schedulerManager, "taskHandlerExecutor"))
            .isSameAs(config.taskHandlerThreadPool)
    }

    // --- WorkflowFactory creation ---

    @Test
    fun workflowFactory_isCreatedSuccessfully() {
        assertThat(workflowFactory).isNotNull()
        assertThat(workflowFactory).isInstanceOf(WorkflowFactory::class.java)
    }

    @Test
    fun workflowFactory_hasCorrectClock() {
        assertThat(workflowFactory.clock).isSameAs(config.utcClock)
    }

    @Test
    fun workflowFactory_hasCorrectInjector() {
        assertThat(workflowFactory.injector).isSameAs(config.injector)
    }

    @Test
    fun workflowFactory_hasCorrectResultPollingTimeLimit() {
        assertThat(workflowFactory.resultPollingTimeLimit)
            .isEqualTo(config.resultPollingTimeLimit)
    }

    @Test
    fun workflowFactory_hasCorrectResultPollingSleepDuration() {
        assertThat(workflowFactory.resultPollingSleepDuration).isEqualTo(Duration.ofMillis(500))
    }

    @Test
    fun workflowFactory_hasNonNullWorkflowOptions() {
        assertThat(workflowFactory.workflowOptions).isNotNull()
    }

    @Test
    fun workflowFactory_hasNonNullSkipperEngine() {
        assertThat(getField<Any?>(workflowFactory, "skipperEngine")).isNotNull()
    }

    // --- Singleton behavior ---

    @Test
    fun runtime_returnsSameInstanceOnRepeatCalls() {
        assertThat(runtime.skipperSchedulerManager.get()).isSameAs(schedulerManager)
        assertThat(runtime.workflowFactory.get()).isSameAs(workflowFactory)
    }

    companion object {
        private lateinit var config: SkipperConfig
        private lateinit var runtime: SkipperRuntime
        private lateinit var schedulerManager: SkipperSchedulerManager
        private lateinit var workflowFactory: WorkflowFactory

        @BeforeAll
        @JvmStatic
        fun setUp() {
            config = SkipperConfig.forService("factory-integration-test")
            config.sqliteDataSource = SqliteTestSetupExtension.DB_DATA_SOURCE
            config.workflowStore = SqliteWorkflowStore.Factory()
            config.scheduler = SqliteScheduler.Factory()
            config.injector =
                SimpleInjector.builder()
                    .bind(Knobs::class.java, mock<Knobs>())
                    .bind(FeatureGate::class.java, mock<FeatureGate>())
                    .build()

            runtime = SkipperRuntime(config)
            schedulerManager = runtime.skipperSchedulerManager.get()
            workflowFactory = runtime.workflowFactory.get()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            if (::schedulerManager.isInitialized) {
                schedulerManager.forceStop()
            }
        }

        // --- Helpers ---

        @Suppress("UNCHECKED_CAST")
        private fun <T> getField(
            target: Any,
            fieldName: String
        ): T {
            val field = target.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            return field.get(target) as T
        }
    }
}
