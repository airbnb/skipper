package com.airbnb.skipper.testutils.trace

import com.airbnb.skipper.ComponentFactory
import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.scheduler.SchedulerInstrumentation
import com.airbnb.skipper.internal.scheduler.mysql.MySqlScheduler
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.internal.storage.mysql.MySqlWorkflowStore
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore
import java.nio.file.Paths
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

/**
 * Turns on trace recording for a runtime when the `skipper.trace.dir` system property names a
 * directory: the configured store and scheduler are wrapped so every write lands in a new
 * `<dir>/trace-<uuid>.ndjson`, and what the scheduler does in memory (its queue and its handlers)
 * lands in a new `<dir>/sched-<uuid>.ndjson`. Without the property this does nothing, so ordinary
 * test runs are unaffected. The test harness calls it just before it builds each runtime.
 *
 * Only the stock JDBC backends are traced. A test that plugs in its own store or scheduler is
 * usually steering an interleaving by blocking inside a call, and the recorder's lock, held across
 * each call, would stall the rest of the engine behind it and change what the test exercises. A
 * test that only wraps them, for example to make a call fail, can call this on the stock factories
 * itself and wrap the traced ones: a call its wrapper fails never reaches the trace.
 */
object Tracing {
    const val DIR_PROPERTY = "skipper.trace.dir"

    /** The scheduler recorder of each config tracing was installed on, so a second call returns the same one. */
    private val installed = Collections.synchronizedMap(WeakHashMap<SkipperConfig, SchedulerInstrumentation>())

    /**
     * Wraps the config's store and scheduler, and returns what the runtime built from it must be
     * given to have its scheduler recorded as well. Calling it again on the same config changes
     * nothing and returns the same thing.
     */
    @JvmStatic
    fun installIfRequested(config: SkipperConfig): SchedulerInstrumentation {
        installed[config]?.let { return it }
        val dir = System.getProperty(DIR_PROPERTY)?.takeIf { it.isNotBlank() } ?: return SchedulerInstrumentation.None
        if (!isStockBackend(config)) return SchedulerInstrumentation.None
        val recorder = TraceRecorder(Paths.get(dir).resolve("trace-${UUID.randomUUID()}.ndjson"), config)
        config.workflowStore = StoreFactory(config.workflowStore, recorder)
        config.scheduler = SchedulerFactory(config.scheduler, recorder)
        // The recorder reads the knobs the scheduler manager reads, so both must get the same ones.
        val knobsFactory = config.knobs
        val knobs = lazy { knobsFactory.create(config) }
        config.knobs = ComponentFactory { knobs.value }
        val schedulerRecorder =
            SchedulerTraceRecorder(Paths.get(dir).resolve("sched-${UUID.randomUUID()}.ndjson"), config) { knobs.value }
        val traced = config.scheduler
        config.scheduler = ComponentFactory { FetchTracingScheduler(traced.create(it), schedulerRecorder) }
        installed[config] = schedulerRecorder
        return schedulerRecorder
    }

    private fun isStockBackend(config: SkipperConfig): Boolean =
        (config.workflowStore is SqliteWorkflowStore.Factory && config.scheduler is SqliteScheduler.Factory) ||
            (config.workflowStore is MySqlWorkflowStore.Factory && config.scheduler is MySqlScheduler.Factory)

    private class StoreFactory(
        private val inner: ComponentFactory<out WorkflowStore>,
        private val recorder: TraceRecorder,
    ) : ComponentFactory<WorkflowStore> {
        override fun create(config: SkipperConfig): WorkflowStore {
            val store = inner.create(config)
            recorder.store = store
            return TracingWorkflowStore(store, recorder)
        }
    }

    private class SchedulerFactory(
        private val inner: ComponentFactory<out Scheduler>,
        private val recorder: TraceRecorder,
    ) : ComponentFactory<Scheduler> {
        override fun create(config: SkipperConfig): Scheduler {
            val scheduler = inner.create(config)
            recorder.scheduler = scheduler
            return TracingScheduler(scheduler, recorder)
        }
    }
}
