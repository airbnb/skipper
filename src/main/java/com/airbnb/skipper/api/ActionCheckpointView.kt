package com.airbnb.skipper.api

import java.time.Instant

/**
 * A simplified view of an action checkpoint that contains only the relevant fields needed
 * for clients to interact with the action checkpoint.
 */
data class ActionCheckpointView(
    /** The ID of the workflow that this action belongs to. */
    val workflowId: String,
    /** The name of the class of the action. */
    val actionClass: String,
    /** The name of the method of the action. */
    val actionMethod: String,
    /**
     * The iteration of the action. Since there can be multiple invocations for the same action class and action method
     * in the same workflow, the interation is used to distinguish between them.
     * */
    val iteration: Long,
    /** The start time of the action execution. */
    val executionStartTime: Instant,
    /** The end time of the action execution. */
    val executionEndTime: Instant?,
    /**
     * The result of the action execution. In case the action execution is successful, the result will be ActionResult.Success.
     * containing the result of the action. In case the action execution is unsuccessful, the result will be ActionResult.Error.
     */
    val result: ActionResult,
    val isTransient: Boolean
) {
    constructor(
        workflowId: String,
        actionClass: String,
        actionMethod: String,
        iteration: Long,
        executionStartTime: Instant,
        executionEndTime: Instant?,
        result: ActionResult
    ) : this(
        workflowId,
        actionClass,
        actionMethod,
        iteration,
        executionStartTime,
        executionEndTime,
        result,
        false
    )

    /**
     * Represents the result of an action execution.
     * This is a sealed class to avoid exposing io.vavr types in the public API.
     */
    sealed class ActionResult {
        data class Success(val value: Any?) : ActionResult()

        data class Error(val error: Throwable) : ActionResult()

        val isSuccess: Boolean
            get() = this is Success

        val isError: Boolean
            get() = this is Error

        fun getOrNull(): Any? = (this as? Success)?.value

        fun errorOrNull(): Throwable? = (this as? Error)?.error

        companion object {
            @JvmStatic
            fun success(value: Any?): ActionResult = Success(value)

            @JvmStatic
            fun error(error: Throwable): ActionResult = Error(error)
        }
    }
}
