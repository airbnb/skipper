package com.airbnb.skipper.testutils.trace

import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.internal.scheduler.Scheduler
import com.airbnb.skipper.internal.storage.WorkflowStore
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Writes the store and scheduler calls of one runtime as JSON lines, in commit order, for trace
 * validation against the TLA+ model in formal/tla (see formal/tla/trace/README.md).
 *
 * Every traced call runs under one lock shared by [TracingWorkflowStore] and [TracingScheduler], so
 * the order of the lines is the order the calls reached the database. After each call the recorder
 * reads back, through the untraced delegates, everything the model tracks for each workflow the
 * call touched: the workflow row, its timers and its task rows. The checker compares the model's
 * state with that snapshot after every step, so only calls, never intermediate state, need logging.
 */
class TraceRecorder internal constructor(
    private val file: Path,
    private val config: SkipperConfig,
) {
    /** Where the harness installed the recorder: the test's own setup, whatever it is named. */
    private val installedBy: List<String> =
        Thread.currentThread().stackTrace
            .map { f -> f.className to f.methodName }
            .dropWhile { (cls, _) -> !cls.startsWith("com.airbnb.skipper.testutils.") }
            .dropWhile { (cls, _) -> cls.startsWith("com.airbnb.skipper.testutils.") }
            .take(3)
            .map { (cls, method) -> cls.substringAfterLast('.') + "." + method }

    private val lock = ReentrantLock()
    private var seq = 0L
    private var writer: BufferedWriter? = null

    /** The untraced store and scheduler, set when the runtime creates them; used for snapshots only. */
    internal var store: WorkflowStore? = null
    internal var scheduler: Scheduler? = null

    /**
     * Runs [call] and records one line per workflow it touched. [touched] maps the call's outcome
     * (null when it threw) to those workflows, each with the task the call was about, if any.
     */
    internal fun <T> record(
        op: String,
        args: Map<String, Any?>,
        touched: (T?) -> List<Touched>,
        call: () -> T,
    ): T =
        lock.withLock {
            val site = callSite()
            val outcome = runCatching(call)
            val now = config.utcClock.millis()
            for (target in touched(outcome.getOrNull())) {
                write(
                    linkedMapOf(
                        "seq" to seq++,
                        "t" to now,
                        "thread" to Thread.currentThread().name,
                        "op" to op,
                        "wf" to target.workflowId,
                        "task" to target.taskId,
                        "ok" to outcome.isSuccess,
                        "error" to outcome.exceptionOrNull()?.javaClass?.simpleName,
                        "args" to args,
                        "site" to site,
                        "state" to snapshot(target.workflowId),
                    ),
                )
            }
            outcome.getOrThrow()
        }

    internal data class Touched(
        val workflowId: String,
        val taskId: String? = null,
    )

    private fun snapshot(workflowId: String): Map<String, Any?> {
        val store = checkNotNull(store) { "trace snapshot before the workflow store was created" }
        val scheduler = checkNotNull(scheduler) { "trace snapshot before the scheduler was created" }
        val workflow = store.getWorkflow(workflowId).orNull
        val timers = if (workflow == null) emptyList() else store.getTimers(workflowId).toJavaList()
        val taskIds =
            listOf(workflowId, "$workflowId-compensation", "$workflowId:timeout") +
                timers.map { it.getUniqueId() }
        val tasks = linkedMapOf<String, Any?>()
        for (id in taskIds) {
            val task = scheduler.getTask<Any>(id).orNull ?: continue
            tasks[id] =
                linkedMapOf(
                    "type" to task.type.name,
                    "st" to task.status.name,
                    "ver" to task.version,
                    "rc" to task.retryCount,
                    "ra" to task.runAfter.toEpochMilli(),
                )
        }
        return linkedMapOf(
            "wf" to workflow?.let { linkedMapOf("st" to it.status.name, "ver" to it.version) },
            "timers" to timers.associate { it.id to it.status.name },
            "tasks" to tasks,
        )
    }

    private fun write(line: Map<String, Any?>) {
        val out = writer ?: open()
        out.write(Json.encode(line))
        out.newLine()
        out.flush()
    }

    /** Opens the file and writes the header: the settings the model's constants come from. */
    private fun open(): BufferedWriter {
        Files.createDirectories(file.parent)
        val out = Files.newBufferedWriter(file)
        writer = out
        // Read the gates at the first event, not at install time: tests stub a mocked FeatureGate
        // after building the config and before starting the scheduler.
        val gate = config.featureGate.create(config)
        out.write(
            Json.encode(
                linkedMapOf(
                    "op" to "header",
                    "origin" to origin(),
                    "installedBy" to installedBy,
                    "leaseMs" to config.schedulerTaskLeaseDuration.toMillis(),
                    "retryDelayMs" to config.taskUnexpectedErrorRetryDelay.toMillis(),
                    "maxRetries" to config.schedulerTaskMaxRetries,
                    "gates" to FeatureGate.Keys.values().associate { it.name to gate.isEnabled(it) },
                ),
            ),
        )
        out.newLine()
        return out
    }

    companion object {
        private val EXCLUDED_PREFIXES =
            listOf(
                "com.airbnb.skipper.testutils.trace.",
                "com.airbnb.skipper.internal.storage.",
                "com.airbnb.skipper.internal.scheduler.sqlite.",
                "com.airbnb.skipper.internal.scheduler.mysql.",
            )

        /**
         * Whether the engine method calling the store directly is one of [methods] ("Class.method").
         * Only the innermost frame counts: a continuation that runs synchronously inside the caller's
         * frame, such as persisting a result, must not pass for the caller's own read.
         */
        internal fun calledFrom(methods: List<String>): Boolean = callSite().firstOrNull() in methods

        /** The engine methods that made the call, innermost first: what the checker maps to a model action. */
        private fun callSite(): List<String> =
            Thread.currentThread().stackTrace
                .filter { f -> f.className.startsWith("com.airbnb.skipper.") && EXCLUDED_PREFIXES.none { f.className.startsWith(it) } }
                .map { f -> f.className.substringAfterLast('.') + "." + f.methodName }
                .take(8)

        /** The frames of the test that started the runtime, for finding the test a trace came from. */
        private fun origin(): List<String> =
            Thread.currentThread().stackTrace
                .map { f -> f.className.substringAfterLast('.') + "." + f.methodName }
                .filter { it.contains("Test") || it.contains("Example") }
                .take(4)
    }
}
