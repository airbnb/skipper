package com.airbnb.skipper

import com.airbnb.skipper.api.ActionCheckpointView
import com.airbnb.skipper.api.WorkflowInstanceStatusView
import com.airbnb.skipper.api.WorkflowInstanceView
import com.airbnb.skipper.util.ExtraRequestData
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.vavr.collection.List
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.function.Supplier

/** Represents a single execution of a workflow type. */
data class WorkflowInstance(
    /** The unique identifier for the workflow instance. */
    val workflowId: String,
    /** The class of the workflow being executed. */
    val workflowClass: Class<out Workflow>,
    /** The method on the workflow class being executed. */
    val workflowMethod: String,
    /** The initial input to the workflow instance. */
    @JsonIgnore
    val input: Any?,
    /** The request context captured at the workflow invocation call site. */
    var requestContext: Any?,
    /** The Extra Request Data captured at the workflow invocation call site.  */
    var extraRequestData: ExtraRequestData,
    /**
     * The current state of the workflow instance. The state is a map of string keys to arbitrary
     * values where the key is the name of the field in the workflow class and the value is the
     * current value of that field.
     */
    @JsonIgnore
    val state: io.vavr.collection.Map<String, Any?>,
    /** The result of the workflow instance if present. */
    @JsonIgnore
    val result: CompletableFuture<Any?>,
    /** If present, this will be used to handle callbacks from the workflow. */
    val callbackHandler: Class<out WorkflowCallbackHandler>?,
    /** To be used for optimistic locking. */
    val version: Int,
    /** The current status of the workflow instance. */
    val status: Status,
    /**
     * If present, this represents the time at which the workflow instance should timeout if not in a terminal state.
     * This field itself does not represent the timeout state of the workflow instance, but rather the time at which the internal mechanism
     * should consider the workflow instance to have timed out and then proceed to handle it accordingly. If absent, the workflow instance
     * will not be considered for timeout handling.
     */
    val timeoutTime: Instant? = null,
    /** The time at which the workflow instance was persisted. Null value means that the instance has not been persisted yet. */
    val createdAt: Instant? = null,
    /** The time at which the workflow instance was last updated. Null value means that the instance has not been persisted yet. */
    val updatedAt: Instant? = null,
    /** The parent workflow ID. If this is not null, it means the workflow instance is a child workflow. */
    val parentWorkflowId: String? = null
) {
    companion object {
        @JvmStatic
        fun builder() = Builder()
    }

    @JsonProperty("state")
    fun getStateAsJavaMapOfTypedObject(): Map<String, TypedObject?> = state.toJavaMap().mapValues { TypedObject(it.value?.javaClass, it.value) }

    @JsonProperty("input")
    fun getInputAsTypedObject(): TypedObject = TypedObject(input?.javaClass, input)

    fun toBuilder() =
        Builder()
            .workflowId(workflowId)
            .workflowClass(workflowClass)
            .workflowMethod(workflowMethod)
            .input(input)
            .requestContext(requestContext)
            .extraRequestData(extraRequestData)
            .state(state)
            .result(result)
            .callbackHandler(callbackHandler)
            .version(version)
            .status(status)
            .timeoutTime(timeoutTime)
            .createdAt(createdAt)
            .updatedAt(updatedAt)
            .parentWorkflowId(parentWorkflowId)

    /**
     * Determines whether the workflow instance is eligible for timeout handling at the given time.
     */
    fun isTimeoutEligible(now: Instant) = (timeoutTime != null && timeoutTime.isBefore(now) && !status.isTerminal())

    /** The possible status of a workflow instance. */
    enum class Status {
        CREATED,
        RUNNING,
        COMPLETED,
        ERROR,
        TRANSIENT_ERROR,
        RETRIES_EXHAUSTED,
        WAITING,
        TIMEOUT,
        COMPENSATION_IN_PROGRESS,
        COMPENSATION_ERROR,
        COMPENSATION_COMPLETED,
        CANCELLED;

        fun isTerminal() = this in setOf(COMPLETED, ERROR, TIMEOUT, COMPENSATION_COMPLETED, CANCELLED)

        fun isCompensationInProgress() = this in setOf(COMPENSATION_IN_PROGRESS, COMPENSATION_ERROR)

        fun toView() =
            when (this) {
                CREATED -> WorkflowInstanceStatusView.CREATED
                RUNNING -> WorkflowInstanceStatusView.RUNNING
                COMPLETED -> WorkflowInstanceStatusView.COMPLETED
                ERROR -> WorkflowInstanceStatusView.ERROR
                TRANSIENT_ERROR -> WorkflowInstanceStatusView.TRANSIENT_ERROR
                WAITING -> WorkflowInstanceStatusView.WAITING
                TIMEOUT -> WorkflowInstanceStatusView.TIMEOUT
                RETRIES_EXHAUSTED -> WorkflowInstanceStatusView.RETRIES_EXHAUSTED
                COMPENSATION_IN_PROGRESS -> WorkflowInstanceStatusView.COMPENSATION_IN_PROGRESS
                COMPENSATION_ERROR -> WorkflowInstanceStatusView.COMPENSATION_ERROR
                COMPENSATION_COMPLETED -> WorkflowInstanceStatusView.COMPENSATION_COMPLETED
                CANCELLED -> WorkflowInstanceStatusView.CANCELLED
            }

        companion object {
            @JvmStatic
            fun fromView(view: WorkflowInstanceStatusView): Status =
                when (view) {
                    WorkflowInstanceStatusView.CREATED -> CREATED
                    WorkflowInstanceStatusView.RUNNING -> RUNNING
                    WorkflowInstanceStatusView.COMPLETED -> COMPLETED
                    WorkflowInstanceStatusView.ERROR -> ERROR
                    WorkflowInstanceStatusView.TRANSIENT_ERROR -> TRANSIENT_ERROR
                    WorkflowInstanceStatusView.WAITING -> WAITING
                    WorkflowInstanceStatusView.TIMEOUT -> TIMEOUT
                    WorkflowInstanceStatusView.RETRIES_EXHAUSTED -> RETRIES_EXHAUSTED
                    WorkflowInstanceStatusView.COMPENSATION_IN_PROGRESS -> COMPENSATION_IN_PROGRESS
                    WorkflowInstanceStatusView.COMPENSATION_ERROR -> COMPENSATION_ERROR
                    WorkflowInstanceStatusView.COMPENSATION_COMPLETED -> COMPENSATION_COMPLETED
                    WorkflowInstanceStatusView.CANCELLED -> CANCELLED
                }
        }
    }

    fun isResultAsync() = result.isDone && !result.isCompletedExceptionally && result.join() is CompletableFuture<*>

    /**
     * Flattens the result of the workflow instance into a single [Result] value.
     *
     * This is particularly useful when having to deal with results which [CompletableFuture], in which case
     * this method will "flatten" them down to the underlying value or exception.
     *
     * If the result is a [CompletableFuture], it is expected it will always be completed when calling this method.
     *
     * @return If the result is not yet available, this method will return `null`.
     * If the result is an error, it will be wrapped in a [Result.error].
     * If the result is a success, it will be wrapped in a [Result.ok] value.
     * If the result is a [CompletableFuture], it will be unwrapped and the above rules will apply.
     */
    fun flattenResult(): Result<out Any?, out SkipperError>? {
        if (!result.isDone) {
            return null
        }
        var res = result
        if (!res.isCompletedExceptionally && res.join() is CompletableFuture<*>) {
            res = res.join() as CompletableFuture<Any?>
            if (!res.isDone) {
                throw IllegalStateException("workflow result must be completed when flattened")
            }
        }
        try {
            return Result.ok(res.join())
        } catch (e: CompletionException) {
            if (e.cause !is SkipperError) {
                throw IllegalStateException("workflow result error must be of type SkipperError", e)
            }
            return Result.error(e.cause as SkipperError)
        }
    }

    @JsonProperty("result")
    fun resultAsTypedResult() =
        flattenResult()?.let { result ->
            if (result.isError()) {
                Result.error(TypedObject(result.error?.javaClass, result.error))
            } else {
                Result.ok(TypedObject(result.ok?.javaClass, result.ok))
            }
        }

    /**
     * Converts the workflow instance to a view object.
     */
    fun toView(actionCheckpoints: Supplier<List<ActionCheckpointView>>) =
        WorkflowInstanceView(
            id = workflowId,
            workflowClass = workflowClass.name,
            workflowMethod = workflowMethod,
            status = status.toView(),
            createdAt = createdAt ?: Instant.MIN,
            actionCheckpointsSupplier = actionCheckpoints,
            workflowInput = input,
            state = state,
            parentWorkflowId = parentWorkflowId,
        )

    /**
     * Compares the fields of the workflow instance that are mutated as part of the workflow execution
     * and returns true if they are the same, which means that there has been no significant change in the
     * execution status of the workflow instance.
     *
     * Notably, we avoid comparing version and updatedAt fields, since they might have been bumped by irrelevant
     * changes to the workflow instance that do not affect the execution status.
     */
    fun executionStatusIsTheSame(other: WorkflowInstance): Boolean {
        if (this === other) return true

        return workflowId == other.workflowId &&
            state == other.state &&
            flattenResult()?.isSuccess == other.flattenResult()?.isSuccess &&
            result.isDone == other.result.isDone &&
            result.isCompletedExceptionally == other.result.isCompletedExceptionally &&
            status == other.status
    }

    /**
     * Builder class for constructing [WorkflowInstance] instances.
     * To be used by Java code.
     */
    class Builder {
        private var workflowId: String? = null
        private var workflowClass: Class<out Workflow>? = null
        private var workflowMethod: String? = null
        private var input: Any? = null
        private var requestContext: Any? = null
        private var extraRequestData: ExtraRequestData? = null
        private var state: io.vavr.collection.Map<String, Any?>? = null
        private var result: CompletableFuture<Any?>? = null
        private var callbackHandler: Class<out WorkflowCallbackHandler>? = null
        private var version: Int? = null
        private var status: Status? = Status.CREATED
        private var timeoutTime: Instant? = null
        private var createdAt: Instant? = null
        private var updatedAt: Instant? = null
        private var parentWorkflowId: String? = null

        fun workflowId(workflowId: String) = apply { this.workflowId = workflowId }

        fun workflowClass(workflowClass: Class<out Workflow>) = apply { this.workflowClass = workflowClass }

        fun workflowMethod(workflowMethod: String) = apply { this.workflowMethod = workflowMethod }

        fun input(input: Any?) = apply { this.input = input }

        fun requestContext(requestContext: Any?) = apply { this.requestContext = requestContext }

        fun extraRequestData(extraRequestData: ExtraRequestData?) = apply { this.extraRequestData = extraRequestData }

        fun state(state: io.vavr.collection.Map<String, Any?>) = apply { this.state = state }

        fun result(result: CompletableFuture<Any?>) = apply { this.result = result }

        fun callbackHandler(callbackHandler: Class<out WorkflowCallbackHandler>?) = apply { this.callbackHandler = callbackHandler }

        fun version(version: Int) = apply { this.version = version }

        fun status(status: Status) = apply { this.status = status }

        fun timeoutTime(timeoutTime: Instant?) = apply { this.timeoutTime = timeoutTime }

        fun createdAt(createdAt: Instant?) = apply { this.createdAt = createdAt }

        fun updatedAt(updatedAt: Instant?) = apply { this.updatedAt = updatedAt }

        fun parentWorkflowId(parentWorkflowId: String?) = apply { this.parentWorkflowId = parentWorkflowId }

        fun build() =
            WorkflowInstance(
                workflowId = workflowId!!,
                workflowClass = workflowClass!!,
                workflowMethod = workflowMethod!!,
                input = input,
                requestContext = requestContext,
                extraRequestData = extraRequestData!!,
                state = state!!,
                result = result!!,
                callbackHandler = callbackHandler,
                version = version!!,
                status = status!!,
                timeoutTime = timeoutTime,
                createdAt = createdAt,
                updatedAt = updatedAt,
                parentWorkflowId = parentWorkflowId
            )
    }

    /**
     * Represents the result of a workflow instance execution.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Result<R, E>(
        val ok: R?,
        val error: E?,
        val isSuccess: Boolean
    ) {
        fun isError() = !isSuccess

        companion object {
            @JvmStatic
            inline fun <reified R> ok(value: R) = Result(value, null, true)

            @JvmStatic
            inline fun <reified E> error(error: E) = Result(null, error, false)
        }
    }

    data class TypedObject(
        val type: Class<*>?,
        val value: Any?
    )
}
