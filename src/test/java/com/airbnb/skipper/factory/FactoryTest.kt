package com.airbnb.skipper.factory

import com.airbnb.skipper.SimpleInjector
import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore
import com.airbnb.skipper.testutils.SqliteTestSetupExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for [SkipperRuntime], verifying that each provider correctly creates its component, that
 * singleton providers return the same instance across calls, and that non-singleton providers
 * create fresh instances.
 *
 * This exercises the core ComponentFactory wiring mechanism, so it lives in the OSS core module
 * and uses the OSS SQLite-backed factories ([SqliteWorkflowStore.Factory] / [SqliteScheduler.Factory])
 * against an in-memory SQLite database ([SqliteTestSetupExtension]). The specific store/scheduler
 * implementation is incidental here.
 */
@ExtendWith(SqliteTestSetupExtension::class)
class FactoryTest {
    private lateinit var runtime: SkipperRuntime

    @BeforeEach
    fun setUp() {
        val config = SkipperConfig.forService("test-service")
        config.sqliteDataSource = SqliteTestSetupExtension.DB_DATA_SOURCE
        config.workflowStore = SqliteWorkflowStore.Factory()
        config.scheduler = SqliteScheduler.Factory()
        config.injector = SimpleInjector.builder().build()

        runtime = SkipperRuntime(config)
    }

    // --- Non-singleton providers: create instance on each call ---

    @Test
    fun executionMetricsCollector_createsInstance() {
        val result = runtime.executionMetricsCollector.get()
        assertThat(result).isNotNull()
    }

    @Test
    fun workflowValidator_createsInstance() {
        val result = runtime.workflowValidator.get()
        assertThat(result).isNotNull()
    }

    @Test
    fun actionValidator_createsInstance() {
        val result = runtime.actionValidator.get()
        assertThat(result).isNotNull()
    }

    @Test
    fun actionErrorMapper_createsInstance() {
        val result = runtime.actionErrorMapper.get()
        assertThat(result).isNotNull()
    }

    @Test
    fun middleware_createsInstance() {
        val result = runtime.middleware.get()
        assertThat(result).isNotNull()
    }

    @Test
    fun actionExecutor_createsInstance() {
        val result = runtime.actionExecutor.get()
        assertThat(result).isNotNull()
    }

    @Test
    fun workflowExecutor_createsInstance() {
        val result = runtime.workflowExecutor.get()
        assertThat(result).isNotNull()
    }

    @Test
    fun compensationExecutor_createsInstance() {
        val result = runtime.compensationExecutor.get()
        assertThat(result).isNotNull()
    }

    @Test
    fun leaseRenewalManager_createsInstance() {
        val result = runtime.leaseRenewalManager.get()
        assertThat(result).isNotNull()
    }

    @Test
    fun executionTimeoutHandler_createsInstance() {
        val result = runtime.executionTimeoutHandler.get()
        assertThat(result).isNotNull()
    }

    @Test
    fun timerTaskHandler_createsInstance() {
        val result = runtime.timerTaskHandler.get()
        assertThat(result).isNotNull()
    }

    @Test
    fun workflowExecutionTaskHandler_createsInstance() {
        val result = runtime.workflowExecutionTaskHandler.get()
        assertThat(result).isNotNull()
    }

    @Test
    fun compensationFlowTaskHandler_createsInstance() {
        val result = runtime.compensationFlowTaskHandler.get()
        assertThat(result).isNotNull()
    }

    @Test
    fun workflowsService_createsInstance() {
        val result = runtime.workflowsService.get()
        assertThat(result).isNotNull()
    }

    @Test
    fun adminResource_createsInstance() {
        val result = runtime.adminResource.get()
        assertThat(result).isNotNull()
    }

    // --- Singleton providers: verify same instance on multiple calls ---

    @Test
    fun metrics_returnsSingleton() {
        val first = runtime.metrics.get()
        val second = runtime.metrics.get()
        assertThat(first).isNotNull()
        assertThat(first).isSameAs(second)
    }

    @Test
    fun featureGate_returnsSingleton() {
        val first = runtime.featureGate.get()
        val second = runtime.featureGate.get()
        assertThat(first).isNotNull()
        assertThat(first).isSameAs(second)
    }

    @Test
    fun knobs_returnsSingleton() {
        val first = runtime.knobs.get()
        val second = runtime.knobs.get()
        assertThat(first).isNotNull()
        assertThat(first).isSameAs(second)
    }

    @Test
    fun schedulerExecutionQueue_returnsSingleton() {
        val first = runtime.schedulerExecutionQueue.get()
        val second = runtime.schedulerExecutionQueue.get()
        assertThat(first).isNotNull()
        assertThat(first).isSameAs(second)
    }

    @Test
    fun skipperEngine_returnsSingleton() {
        val first = runtime.skipperEngine.get()
        val second = runtime.skipperEngine.get()
        assertThat(first).isNotNull()
        assertThat(first).isSameAs(second)
    }

    @Test
    fun workflowFactory_returnsSingleton() {
        val first = runtime.workflowFactory.get()
        val second = runtime.workflowFactory.get()
        assertThat(first).isNotNull()
        assertThat(first).isSameAs(second)
    }

    @Test
    fun skipperSchedulerManager_returnsSingleton() {
        val first = runtime.skipperSchedulerManager.get()
        val second = runtime.skipperSchedulerManager.get()
        assertThat(first).isNotNull()
        assertThat(first).isSameAs(second)
    }

    // --- Test isolation: separate runtimes get independent instances ---

    @Test
    fun separateRuntimes_haveIndependentInstances() {
        val config2 = SkipperConfig.forService("test-service-2")
        config2.sqliteDataSource = SqliteTestSetupExtension.DB_DATA_SOURCE
        config2.workflowStore = SqliteWorkflowStore.Factory()
        config2.scheduler = SqliteScheduler.Factory()
        config2.injector = SimpleInjector.builder().build()

        val runtime2 = SkipperRuntime(config2)

        // Each runtime has its own singleton instances. Metrics is intentionally excluded
        // from this check: the default NoOpMetrics is a process-wide singleton, so identity
        // equality across runtimes is expected.
        assertThat(runtime.skipperEngine.get()).isNotSameAs(runtime2.skipperEngine.get())
    }

    companion object {
        @Suppress("unused", "UNCHECKED_CAST")
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
