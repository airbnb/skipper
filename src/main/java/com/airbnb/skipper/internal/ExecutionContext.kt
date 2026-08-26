package com.airbnb.skipper.internal

import com.airbnb.skipper.Actions
import com.airbnb.skipper.Timer
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.api.ActionCheckpoint
import io.vavr.Tuple2
import io.vavr.collection.List
import io.vavr.control.Option
import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** ExecutionContext is a container for the state of a workflow execution. */
// Lombok @Builder value type. Ported as a regular class (not a data class) to reproduce the
// exact public all-args constructor + hand-written getters + builder()/toBuilder() + the
// @Builder.Default semantics, and equals/hashCode/toString over the same field set. The vavr types
// in public signatures (List/Option/Tuple2) are frozen. The all-args ctor is
// public and keeps its argument order/types byte-identical to the original Java; it does NOT
// null-check timerCounter or shouldReloadWorkflowInstance (matching the baseline).
// `open` so this (non-final in the Java baseline) class stays Mockito-mockable; downstream consumers
// mock it. Public members are `open` to keep their getters/methods overridable
// (stubbable), matching the Java baseline's non-final defaults. ABI-faithful; no behavior change.
open class ExecutionContext(
    /** The ID of the workflow currently being executed. This is immutable. */
    // Public val (Java getter getWorkflow()); also read as a property by Workflow.kt.
    @get:JvmName("getWorkflow") open val workflow: WorkflowInstance,
    /**
     * A list of the persisted checkpoints for the current workflow instance. This list is immutable
     * throughout the execution of the workflow.
     */
    @get:JvmName("getActionCheckpoints") open val actionCheckpoints: List<ActionCheckpoint>,
    /**
     * A map of the number of times each action has been executed in the current workflow instance.
     * This map evolves throughout the execution of the workflow. Every time an action is executed,
     * the executor is responsible for updating the count in this map. This counter is particularly
     * useful for workflows that execute the same action multiple times.
     */
    // No public getter in the Java baseline -> keep private.
    private val actionIterations: MutableMap<Tuple2<String, String>, Int>,
    /**
     * A counter that is incremented every time a timer is evaluated in the current workflow
     * execution. This helps to assign a unique ID to each timer that is used in the workflow.
     */
    @get:JvmName("getTimerCounter") open val timerCounter: AtomicInteger,
    /** A list of timers that have been created by the current workflow execution. */
    @get:JvmName("getTimers") open val timers: List<Timer>,
    /**
     * A list of timers that has been modified by the current workflow execution and have not yet
     * been persisted.
     */
    @get:JvmName("getDirtyTimers") open val dirtyTimers: MutableList<Timer>,
    /**
     * A list of checkpoints that have been created by the current workflow execution and have not
     * yet been persisted.
     */
    // Private mutable backing list; the public accessor `dirtyCheckpoints` (getDirtyCheckpoints())
    // is COMPUTED as List.ofAll(...), matching the original Java getter (returns an immutable
    // vavr List copy). Exposed as a property so callers can use either `.dirtyCheckpoints` (Kotlin)
    // or getDirtyCheckpoints() (Java).
    private val mutableDirtyCheckpoints: MutableList<ActionCheckpoint>,
    @get:JvmName("getClock") open val clock: Clock,
    /**
     * The executor service used for dispatching coroutine continuations when resuming suspend
     * workflow/action methods. This is Skipper's main thread pool, passed through from
     * [WorkflowExecutor].
     */
    // Backing field private; exposed via the explicit getExecutorService() method below so callers
    // (incl. Workflow.kt) use the getExecutorService() form, matching the original Java getter.
    private val executorService: ExecutorService,
    /**
     * Tracks named checkpoints that have been consumed during replay, preventing
     * double-consumption.
     */
    // No public getter in the Java baseline -> keep private.
    private val consumedNamedCheckpoints: MutableSet<String>,
    @get:JvmName("getShouldReloadWorkflowInstance") open val shouldReloadWorkflowInstance: AtomicBoolean,
) {
    open fun getExecutorService(): ExecutorService = executorService

    /**
     * Increments the number of times an action has been executed in the current workflow instance.
     */
    open fun incrementActionIteration(
        actionClass: Class<out Actions>,
        actionMethod: String
    ) {
        val tag = Tuple2(actionClass.name, actionMethod)
        actionIterations[tag] = actionIterations.getOrDefault(tag, 0) + 1
    }

    /**
     * Returns the number of times an action has been executed in the current workflow execution
     * context.
     *
     * @return The number of times the action has been executed.
     */
    open fun getActionIteration(
        actionClass: Class<out Actions>,
        actionMethod: String
    ): Int {
        val tag = Tuple2(actionClass.name, actionMethod)
        return actionIterations.getOrDefault(tag, 0)
    }

    open fun addDirtyCheckpoint(checkpoint: ActionCheckpoint) {
        mutableDirtyCheckpoints.add(checkpoint)
    }

    // Computed property -> JVM getter getDirtyCheckpoints(); Kotlin callers use .dirtyCheckpoints.
    // Default Kotlin getter is already `getDirtyCheckpoints` (so the JVM name is unchanged); no
    // explicit @get:JvmName because that annotation is illegal on an `open` custom-getter property.
    open val dirtyCheckpoints: List<ActionCheckpoint>
        get() = List.ofAll(mutableDirtyCheckpoints)

    open fun getActionCheckpoint(checkpointTag: CheckpointTag): Option<ActionCheckpoint> {
        // Only return non-transient checkpoints
        return actionCheckpoints
            .filter { checkpoint -> checkpoint.checkpointTag.matches(checkpointTag) }
            .filter { checkpoint -> !checkpoint.isTransient }
            .headOption()
    }

    open fun getActionRetryCount(checkpointTag: CheckpointTag): Int {
        // Retryable failures are all transient
        return actionCheckpoints
            .filter { checkpoint -> checkpoint.checkpointTag.matches(checkpointTag) }
            .filter { checkpoint -> checkpoint.isTransient }
            .size()
    }

    open fun addConsumedNamedCheckpoint(checkpointName: String) {
        consumedNamedCheckpoints.add(checkpointName)
    }

    open fun isNamedCheckpointConsumed(checkpointName: String): Boolean {
        return consumedNamedCheckpoints.contains(checkpointName)
    }

    open fun addDirtyTimer(timer: Timer) {
        dirtyTimers.add(timer)
    }

    /**
     * Checks if the workflow execution has any successful, non-transient action checkpoints from
     * actions that have associated @Compensate methods, indicating the need for compensation flow.
     *
     * This method examines both persisted action checkpoints and dirty (unpersisted) checkpoints to
     * determine if any compensable actions have been successfully executed.
     *
     * @return true if there are any successful, non-transient checkpoints for actions
     *   with @Compensate methods
     */
    open fun hasCompensationFlow(): Boolean {
        // Check persisted action checkpoints
        return List.ofAll(actionCheckpoints)
            .appendAll(mutableDirtyCheckpoints)
            .filter { checkpoint -> !checkpoint.isTransient }
            .filter { checkpoint -> checkpoint.isSuccessful }
            .exists { checkpoint ->
                @Suppress("UNCHECKED_CAST")
                val actionClass =
                    checkpoint.checkpointTag.actionClass as Class<out Actions>
                val inspector = ActionInspector(actionClass)
                inspector.hasCompensationMethod(checkpoint.checkpointTag.actionMethod)
            }
    }

    open fun toBuilder(): ExecutionContextBuilder =
        ExecutionContextBuilder()
            .workflow(this.workflow)
            .actionCheckpoints(this.actionCheckpoints)
            .actionIterations(this.actionIterations)
            .timerCounter(this.timerCounter)
            .timers(this.timers)
            .dirtyTimers(this.dirtyTimers)
            .dirtyCheckpoints(this.mutableDirtyCheckpoints)
            .clock(this.clock)
            .executorService(this.executorService)
            .consumedNamedCheckpoints(this.consumedNamedCheckpoints)
            .shouldReloadWorkflowInstance(this.shouldReloadWorkflowInstance)

    class ExecutionContextBuilder internal constructor() {
        private var workflow: WorkflowInstance? = null
        private var actionCheckpoints: List<ActionCheckpoint>? = null
        private var actionCheckpointsSet = false
        private var actionIterations: MutableMap<Tuple2<String, String>, Int>? = null
        private var actionIterationsSet = false
        private var timerCounter: AtomicInteger? = null
        private var timerCounterSet = false
        private var timers: List<Timer>? = null
        private var timersSet = false
        private var dirtyTimers: MutableList<Timer>? = null
        private var dirtyTimersSet = false
        private var dirtyCheckpoints: MutableList<ActionCheckpoint>? = null
        private var dirtyCheckpointsSet = false
        private var clock: Clock? = null
        private var executorService: ExecutorService? = null
        private var consumedNamedCheckpoints: MutableSet<String>? = null
        private var consumedNamedCheckpointsSet = false
        private var shouldReloadWorkflowInstance: AtomicBoolean? = null
        private var shouldReloadWorkflowInstanceSet = false

        fun workflow(workflow: WorkflowInstance): ExecutionContextBuilder {
            this.workflow = workflow
            return this
        }

        fun actionCheckpoints(actionCheckpoints: List<ActionCheckpoint>): ExecutionContextBuilder {
            this.actionCheckpoints = actionCheckpoints
            actionCheckpointsSet = true
            return this
        }

        fun actionIterations(actionIterations: MutableMap<Tuple2<String, String>, Int>): ExecutionContextBuilder {
            this.actionIterations = actionIterations
            actionIterationsSet = true
            return this
        }

        fun timerCounter(timerCounter: AtomicInteger): ExecutionContextBuilder {
            this.timerCounter = timerCounter
            timerCounterSet = true
            return this
        }

        fun timers(timers: List<Timer>): ExecutionContextBuilder {
            this.timers = timers
            timersSet = true
            return this
        }

        fun dirtyTimers(dirtyTimers: MutableList<Timer>): ExecutionContextBuilder {
            this.dirtyTimers = dirtyTimers
            dirtyTimersSet = true
            return this
        }

        fun dirtyCheckpoints(dirtyCheckpoints: MutableList<ActionCheckpoint>): ExecutionContextBuilder {
            this.dirtyCheckpoints = dirtyCheckpoints
            dirtyCheckpointsSet = true
            return this
        }

        fun clock(clock: Clock): ExecutionContextBuilder {
            this.clock = clock
            return this
        }

        fun executorService(executorService: ExecutorService): ExecutionContextBuilder {
            this.executorService = executorService
            return this
        }

        fun consumedNamedCheckpoints(consumedNamedCheckpoints: MutableSet<String>): ExecutionContextBuilder {
            this.consumedNamedCheckpoints = consumedNamedCheckpoints
            consumedNamedCheckpointsSet = true
            return this
        }

        fun shouldReloadWorkflowInstance(shouldReloadWorkflowInstance: AtomicBoolean): ExecutionContextBuilder {
            this.shouldReloadWorkflowInstance = shouldReloadWorkflowInstance
            shouldReloadWorkflowInstanceSet = true
            return this
        }

        fun build(): ExecutionContext {
            val actionCheckpointsValue =
                if (actionCheckpointsSet) actionCheckpoints!! else defaultActionCheckpoints()
            val actionIterationsValue =
                if (actionIterationsSet) actionIterations!! else defaultActionIterations()
            val timerCounterValue =
                if (timerCounterSet) timerCounter else defaultTimerCounter()
            val timersValue = if (timersSet) timers!! else defaultTimers()
            val dirtyTimersValue = if (dirtyTimersSet) dirtyTimers!! else defaultDirtyTimers()
            val dirtyCheckpointsValue =
                if (dirtyCheckpointsSet) dirtyCheckpoints!! else defaultDirtyCheckpoints()
            val consumedNamedCheckpointsValue =
                if (consumedNamedCheckpointsSet) {
                    consumedNamedCheckpoints!!
                } else {
                    defaultConsumedNamedCheckpoints()
                }
            val shouldReloadWorkflowInstanceValue =
                if (shouldReloadWorkflowInstanceSet) {
                    shouldReloadWorkflowInstance
                } else {
                    defaultShouldReloadWorkflowInstance()
                }
            return ExecutionContext(
                workflow
                    ?: throw NullPointerException(
                        "workflow is marked non-null but is null",
                    ),
                actionCheckpointsValue,
                actionIterationsValue,
                // timerCounter / shouldReloadWorkflowInstance may be null (no NPE guard in the
                // original Java all-args ctor); the platform-typed pass-through preserves that.
                timerCounterValue!!,
                timersValue,
                dirtyTimersValue,
                dirtyCheckpointsValue,
                clock
                    ?: throw NullPointerException(
                        "clock is marked non-null but is null",
                    ),
                executorService
                    ?: throw NullPointerException(
                        "executorService is marked non-null but is null",
                    ),
                consumedNamedCheckpointsValue,
                shouldReloadWorkflowInstanceValue!!,
            )
        }

        override fun toString(): String =
            "ExecutionContext.ExecutionContextBuilder(workflow=$workflow" +
                ", actionCheckpoints\$value=$actionCheckpoints" +
                ", actionIterations\$value=$actionIterations" +
                ", timerCounter\$value=$timerCounter" +
                ", timers\$value=$timers" +
                ", dirtyTimers\$value=$dirtyTimers" +
                ", dirtyCheckpoints\$value=$dirtyCheckpoints" +
                ", clock=$clock" +
                ", executorService=$executorService" +
                ", consumedNamedCheckpoints\$value=$consumedNamedCheckpoints" +
                ", shouldReloadWorkflowInstance\$value=$shouldReloadWorkflowInstance)"
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is ExecutionContext) return false
        return workflow == other.workflow &&
            actionCheckpoints == other.actionCheckpoints &&
            actionIterations == other.actionIterations &&
            timerCounter == other.timerCounter &&
            timers == other.timers &&
            dirtyTimers == other.dirtyTimers &&
            dirtyCheckpoints == other.dirtyCheckpoints &&
            clock == other.clock &&
            executorService == other.executorService &&
            consumedNamedCheckpoints == other.consumedNamedCheckpoints &&
            shouldReloadWorkflowInstance == other.shouldReloadWorkflowInstance
    }

    override fun hashCode(): Int {
        val prime = 59
        var result = 1
        result = result * prime + workflow.hashCode()
        result = result * prime + actionCheckpoints.hashCode()
        result = result * prime + actionIterations.hashCode()
        result = result * prime + timerCounter.hashCode()
        result = result * prime + timers.hashCode()
        result = result * prime + dirtyTimers.hashCode()
        result = result * prime + dirtyCheckpoints.hashCode()
        result = result * prime + clock.hashCode()
        result = result * prime + executorService.hashCode()
        result = result * prime + consumedNamedCheckpoints.hashCode()
        result = result * prime + shouldReloadWorkflowInstance.hashCode()
        return result
    }

    override fun toString(): String =
        "ExecutionContext(workflow=$workflow" +
            ", actionCheckpoints=$actionCheckpoints" +
            ", actionIterations=$actionIterations" +
            ", timerCounter=$timerCounter" +
            ", timers=$timers" +
            ", dirtyTimers=$dirtyTimers" +
            ", dirtyCheckpoints=$dirtyCheckpoints" +
            ", clock=$clock" +
            ", executorService=$executorService" +
            ", consumedNamedCheckpoints=$consumedNamedCheckpoints" +
            ", shouldReloadWorkflowInstance=$shouldReloadWorkflowInstance)"

    companion object {
        @JvmStatic
        fun builder(): ExecutionContextBuilder = ExecutionContextBuilder()

        private fun defaultActionCheckpoints(): List<ActionCheckpoint> = List.empty()

        private fun defaultActionIterations(): MutableMap<Tuple2<String, String>, Int> = java.util.HashMap()

        private fun defaultTimerCounter(): AtomicInteger = AtomicInteger(0)

        private fun defaultTimers(): List<Timer> = List.empty()

        private fun defaultDirtyTimers(): MutableList<Timer> = java.util.ArrayList()

        private fun defaultDirtyCheckpoints(): MutableList<ActionCheckpoint> = java.util.ArrayList()

        private fun defaultConsumedNamedCheckpoints(): MutableSet<String> = java.util.HashSet()

        private fun defaultShouldReloadWorkflowInstance(): AtomicBoolean = AtomicBoolean(false)
    }
}
