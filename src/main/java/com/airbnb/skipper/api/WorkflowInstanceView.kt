package com.airbnb.skipper.api

import io.vavr.collection.Map
import java.time.Instant
import java.util.Collections
import java.util.List
import java.util.Optional
import java.util.function.Supplier

/**
 * A simplified view of a workflow instance that contains only the relevant fields needed
 * for clients to interact with the workflow.
 */
data class WorkflowInstanceView(
    /** The workflow instance ID */
    val id: String,
    /** The name of the workflow class */
    val workflowClass: String,
    /** The workflow method */
    val workflowMethod: String,
    /** The workflow status */
    val status: WorkflowInstanceStatusView,
    val createdAt: Instant,
    /** The supplier that will be used to lazily construct the action checkpoints */
    val actionCheckpointsSupplier: Supplier<io.vavr.collection.List<ActionCheckpointView>>,
    val workflowInput: Any?,
    val state: Map<String, Any?>,
    val parentWorkflowId: String? = null,
) {
    constructor(
        id: String,
        workflowClass: String,
        workflowMethod: String,
        status: WorkflowInstanceStatusView,
        createdAt: Instant,
        actionCheckoutSupplier: Supplier<io.vavr.collection.List<ActionCheckpointView>>,
        workflowInput: Any?,
        state: Map<String, Any?>
    ) :
        this(
            id = id,
            workflowClass = workflowClass,
            workflowMethod = workflowMethod,
            status = status,
            createdAt = createdAt,
            actionCheckpointsSupplier = actionCheckoutSupplier,
            workflowInput = workflowInput,
            state = state,
            parentWorkflowId = null
        )

    /**
     * A lazily loaded list of action checkpoints for this workflow instance.
     * Reason this is lazily created is to avoid the extra db call when the actions are not needed.
     */
    val actionCheckpoints: List<ActionCheckpointView> by lazy<List<ActionCheckpointView>> {
        Collections.unmodifiableList(actionCheckpointsSupplier.get().toJavaList()) as List<ActionCheckpointView>
    }

    /**
     * Get the workflow input as a specific type.
     *
     * @param clazz The class of the workflow input
     * @return The workflow input as the specified type, or null if the input is null
     */
    fun <T> getInput(clazz: Class<T>): T? {
        if (workflowInput == null) {
            return null
        }
        return if (clazz.isInstance(workflowInput)) {
            clazz.cast(workflowInput)
        } else {
            throw IllegalArgumentException("Workflow input is not of type ${clazz.name}")
        }
    }

    inline fun <reified T> getInput(): T? {
        return getInput(T::class.java)
    }

    /**
     * Get a workflow state param as a specific type.
     *
     * @param key The key of the state param
     * @param clazz The class of the state param
     * @return The state param as the specified type, or null if the param is null
     */
    fun <T> getStateParam(
        key: String,
        clazz: Class<T>
    ): T? {
        if (!state.containsKey(key)) {
            throw IllegalArgumentException("Workflow state does not contain key $key")
        }
        val value = state.get(key).get() ?: return null
        return if (clazz.isInstance(value)) {
            clazz.cast(value)
        } else {
            throw IllegalArgumentException("Workflow state key is not of type ${clazz.name}. It is of type ${value!!::class.java}")
        }
    }

    inline fun <reified T> getStateParam(key: String): T? {
        return getStateParam(key, T::class.java)
    }

    fun getLastTransientError(): Optional<Throwable> {
        return Optional.ofNullable(actionCheckpoints.lastOrNull { it.isTransient })
            .flatMap { Optional.ofNullable(it.result.errorOrNull()) }
    }
}

public enum class WorkflowInstanceStatusView {
    CREATED,
    RUNNING,
    COMPLETED,
    ERROR,
    TRANSIENT_ERROR,
    WAITING,
    RETRIES_EXHAUSTED,
    TIMEOUT,
    COMPENSATION_IN_PROGRESS,
    COMPENSATION_ERROR,
    COMPENSATION_COMPLETED,
    CANCELLED
}
