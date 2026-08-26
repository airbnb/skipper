package com.airbnb.skipper.internal.storage

import com.airbnb.skipper.SkipperError
import com.airbnb.skipper.WorkflowInstance
import io.vavr.collection.Map
import io.vavr.control.Either
import java.util.concurrent.CompletableFuture

/** Represents a request to update a workflow instance. */
data class WorkflowUpdateRequest private constructor(
    /**
     * The current workflow instance. This is the workflow instance as it is currently persisted on
     * the datastore and NOT a non-persisted in-memory version
     */
    val workflowInstance: WorkflowInstance,
    /**
     * The new state of the workflow instance. The current state will be completely replaced by this
     * new state. This is a complete replacement rather than a per-key update.
     */
    val newState: Map<String, Any?>?,
    /**
     * The result of the workflow instance if present. This should only be present for terminal
     * statuses (COMPLETED, ERROR). Either.left means the workflow was completed with error,
     * Either.right means the workflow was completed successfully.
     */
    val result: Either<SkipperError, Any?>?,
    /** The new status of the workflow instance. */
    val newStatus: WorkflowInstance.Status,
    /** Whether to clear the result of the workflow instance. */
    @get:JvmName("isClearResult")
    val clearResult: Boolean,
    /** Whether the underlying workflow instance result is a CompletableFuture. */
    val resultIsAsync: Boolean?,
) {
    init {
        require(
            newStatus == WorkflowInstance.Status.COMPLETED ||
                newStatus == WorkflowInstance.Status.ERROR ||
                newStatus == WorkflowInstance.Status.WAITING ||
                newStatus == WorkflowInstance.Status.CANCELLED ||
                result == null,
        ) {
            "result must only be present for terminal statuses (COMPLETED, ERROR, CANCELLED)"
        }
        require(result == null || resultIsAsync != null) {
            "resultIsAsync must be set if result is present"
        }
    }

    fun computeWorkflowInstanceAfterUpdate(): WorkflowInstance {
        val builder: WorkflowInstance.Builder = workflowInstance.toBuilder()
        if (newState != null) {
            builder.state(newState)
        }
        if (result != null) {
            var newResult: CompletableFuture<Any?> = CompletableFuture()
            if (result.isLeft) {
                newResult.completeExceptionally(result.left)
            } else {
                newResult.complete(result.get())
            }
            if (resultIsAsync!!) {
                newResult = CompletableFuture.completedFuture(newResult)
            }
            builder.result(newResult)
        } else if (clearResult) {
            builder.result(CompletableFuture<Any?>())
        }
        builder.status(newStatus)
        return builder.build()
    }

    fun toBuilder(): WorkflowUpdateRequestBuilder =
        WorkflowUpdateRequestBuilder()
            .workflowInstance(workflowInstance)
            .newState(newState)
            .result(result)
            .newStatus(newStatus)
            .clearResult(clearResult)
            .resultIsAsync(resultIsAsync)

    class WorkflowUpdateRequestBuilder internal constructor() {
        private var workflowInstance: WorkflowInstance? = null
        private var newState: Map<String, Any?>? = null
        private var result: Either<SkipperError, Any?>? = null
        private var newStatus: WorkflowInstance.Status? = null
        private var clearResult: Boolean = false
        private var resultIsAsync: Boolean? = null

        /**
         * The current workflow instance. This is the workflow instance as it is currently persisted
         * on the datastore and NOT a non-persisted in-memory version
         *
         * @return `this`.
         */
        fun workflowInstance(workflowInstance: WorkflowInstance): WorkflowUpdateRequestBuilder {
            this.workflowInstance = workflowInstance
            return this
        }

        /**
         * The new state of the workflow instance. The current state will be completely replaced by
         * this new state. This is a complete replacement rather than a per-key update.
         *
         * @return `this`.
         */
        fun newState(newState: Map<String, Any?>?): WorkflowUpdateRequestBuilder {
            this.newState = newState
            return this
        }

        /**
         * The result of the workflow instance if present. This should only be present for terminal
         * statuses (COMPLETED, ERROR). Either.left means the workflow was completed with error,
         * Either.right means the workflow was completed successfully.
         *
         * @return `this`.
         */
        fun result(result: Either<SkipperError, Any?>?): WorkflowUpdateRequestBuilder {
            this.result = result
            return this
        }

        /**
         * The new status of the workflow instance.
         *
         * @return `this`.
         */
        fun newStatus(newStatus: WorkflowInstance.Status): WorkflowUpdateRequestBuilder {
            this.newStatus = newStatus
            return this
        }

        /**
         * Whether to clear the result of the workflow instance.
         *
         * @return `this`.
         */
        fun clearResult(clearResult: Boolean): WorkflowUpdateRequestBuilder {
            this.clearResult = clearResult
            return this
        }

        /**
         * Whether the underlying workflow instance result is a CompletableFuture.
         *
         * @return `this`.
         */
        fun resultIsAsync(resultIsAsync: Boolean?): WorkflowUpdateRequestBuilder {
            this.resultIsAsync = resultIsAsync
            return this
        }

        fun build(): WorkflowUpdateRequest =
            WorkflowUpdateRequest(
                workflowInstance
                    ?: throw NullPointerException(
                        "workflowInstance is marked non-null but is null",
                    ),
                newState,
                result,
                newStatus
                    ?: throw NullPointerException("newStatus is marked non-null but is null"),
                clearResult,
                resultIsAsync,
            )

        override fun toString(): String =
            "WorkflowUpdateRequest.WorkflowUpdateRequestBuilder(workflowInstance=$workflowInstance" +
                ", newState=$newState, result=$result, newStatus=$newStatus" +
                ", clearResult=$clearResult, resultIsAsync=$resultIsAsync)"
    }

    companion object {
        @JvmStatic
        fun builder(): WorkflowUpdateRequestBuilder = WorkflowUpdateRequestBuilder()
    }
}
