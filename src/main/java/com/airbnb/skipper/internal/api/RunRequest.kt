package com.airbnb.skipper.internal.api

import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowCallbackHandler
import com.airbnb.skipper.util.ExtraRequestData
import java.time.Duration
import java.util.Objects

/** Represents the request to run a workflow instance. */
class RunRequest(
    val workflowId: String,
    val workflowClass: Class<out Workflow>,
    val workflowMethod: String,
    val input: Any?,
    val requestContext: Any?,
    val extraRequestData: ExtraRequestData,
    val callbackHandler: Class<out WorkflowCallbackHandler>?,
    val executionTimeout: Duration?,
    @get:JvmName("isFailOnDuplicate") val failOnDuplicate: Boolean,
    @get:JvmName("isAllowQueryOnNonExistentWorkflow") val allowQueryOnNonExistentWorkflow: Boolean,
    // Named `isRunAsync` (not `runAsync` + @JvmName) so Kotlin consumers keep property access
    // `request.isRunAsync` (the synthetic name they used against the Java getter). JVM getter is
    // still `isRunAsync()` (Kotlin `is`-prefix convention), so Java callers + ABI are unchanged.
    val isRunAsync: Boolean,
    val parentWorkflowId: String?,
) {
    constructor(
        workflowId: String,
        workflowClass: Class<out Workflow>,
        workflowMethod: String,
        input: Any?,
        requestContext: Any?,
        extraRequestData: ExtraRequestData,
        callbackHandler: Class<out WorkflowCallbackHandler>?,
        executionTimeout: Duration?,
        failOnDuplicate: Boolean,
        allowQueryOnNonExistentWorkflow: Boolean,
        @Suppress("UNUSED_PARAMETER") runAsync: Boolean,
    ) : this(
        workflowId,
        workflowClass,
        workflowMethod,
        input,
        requestContext,
        extraRequestData,
        callbackHandler,
        executionTimeout,
        failOnDuplicate,
        allowQueryOnNonExistentWorkflow,
        // Faithful to the Java baseline: this 11-arg ctor accepts `runAsync` but
        // hardcodes `false` in its delegation, silently discarding the caller's value. Do NOT
        // "fix" this to forward the parameter — the behavior is intentionally preserved 1:1.
        false, // runAsync
        null, // parentWorkflowId
    )

    constructor(
        workflowId: String,
        workflowClass: Class<out Workflow>,
        workflowMethod: String,
        input: Any?,
        requestContext: Any?,
        extraRequestData: ExtraRequestData,
        callbackHandler: Class<out WorkflowCallbackHandler>?,
        executionTimeout: Duration?,
        failOnDuplicate: Boolean,
        allowQueryOnNonExistentWorkflow: Boolean,
    ) : this(
        workflowId,
        workflowClass,
        workflowMethod,
        input,
        requestContext,
        extraRequestData,
        callbackHandler,
        executionTimeout,
        failOnDuplicate,
        allowQueryOnNonExistentWorkflow,
        false, // runAsync
    )

    constructor(
        workflowId: String,
        workflowClass: Class<out Workflow>,
        workflowMethod: String,
        input: Any?,
        requestContext: Any?,
        extraRequestData: ExtraRequestData,
        callbackHandler: Class<out WorkflowCallbackHandler>?,
        executionTimeout: Duration?,
        allowQueryOnNonExistentWorkflow: Boolean,
    ) : this(
        workflowId,
        workflowClass,
        workflowMethod,
        input,
        requestContext,
        extraRequestData,
        callbackHandler,
        executionTimeout,
        false, // failOnDuplicate
        allowQueryOnNonExistentWorkflow,
    )

    class RunRequestBuilder internal constructor() {
        private var workflowId: String? = null
        private var workflowClass: Class<out Workflow>? = null
        private var workflowMethod: String? = null
        private var input: Any? = null
        private var requestContext: Any? = null
        private var extraRequestData: ExtraRequestData? = null
        private var callbackHandler: Class<out WorkflowCallbackHandler>? = null
        private var executionTimeout: Duration? = null
        private var failOnDuplicate = false
        private var allowQueryOnNonExistentWorkflow = false
        private var runAsync = false
        private var parentWorkflowId: String? = null

        fun workflowId(workflowId: String): RunRequestBuilder {
            this.workflowId = workflowId
            return this
        }

        fun workflowClass(workflowClass: Class<out Workflow>): RunRequestBuilder {
            this.workflowClass = workflowClass
            return this
        }

        fun workflowMethod(workflowMethod: String): RunRequestBuilder {
            this.workflowMethod = workflowMethod
            return this
        }

        fun input(input: Any?): RunRequestBuilder {
            this.input = input
            return this
        }

        fun requestContext(requestContext: Any?): RunRequestBuilder {
            this.requestContext = requestContext
            return this
        }

        fun extraRequestData(extraRequestData: ExtraRequestData): RunRequestBuilder {
            this.extraRequestData = extraRequestData
            return this
        }

        fun callbackHandler(callbackHandler: Class<out WorkflowCallbackHandler>?,): RunRequestBuilder {
            this.callbackHandler = callbackHandler
            return this
        }

        fun executionTimeout(executionTimeout: Duration?): RunRequestBuilder {
            this.executionTimeout = executionTimeout
            return this
        }

        fun failOnDuplicate(failOnDuplicate: Boolean): RunRequestBuilder {
            this.failOnDuplicate = failOnDuplicate
            return this
        }

        fun allowQueryOnNonExistentWorkflow(allowQueryOnNonExistentWorkflow: Boolean,): RunRequestBuilder {
            this.allowQueryOnNonExistentWorkflow = allowQueryOnNonExistentWorkflow
            return this
        }

        fun runAsync(runAsync: Boolean): RunRequestBuilder {
            this.runAsync = runAsync
            return this
        }

        fun parentWorkflowId(parentWorkflowId: String?): RunRequestBuilder {
            this.parentWorkflowId = parentWorkflowId
            return this
        }

        fun build(): RunRequest =
            RunRequest(
                workflowId
                    ?: throw NullPointerException(
                        "workflowId is marked non-null but is null",
                    ),
                workflowClass
                    ?: throw NullPointerException(
                        "workflowClass is marked non-null but is null",
                    ),
                workflowMethod
                    ?: throw NullPointerException(
                        "workflowMethod is marked non-null but is null",
                    ),
                input,
                requestContext,
                extraRequestData
                    ?: throw NullPointerException(
                        "extraRequestData is marked non-null but is null",
                    ),
                callbackHandler,
                executionTimeout,
                failOnDuplicate,
                allowQueryOnNonExistentWorkflow,
                runAsync,
                parentWorkflowId,
            )

        override fun toString(): String =
            "RunRequest.RunRequestBuilder(workflowId=$workflowId" +
                ", workflowClass=$workflowClass" +
                ", workflowMethod=$workflowMethod" +
                ", input=$input" +
                ", requestContext=$requestContext" +
                ", extraRequestData=$extraRequestData" +
                ", callbackHandler=$callbackHandler" +
                ", executionTimeout=$executionTimeout" +
                ", failOnDuplicate=$failOnDuplicate" +
                ", allowQueryOnNonExistentWorkflow=$allowQueryOnNonExistentWorkflow" +
                ", runAsync=$runAsync" +
                ", parentWorkflowId=$parentWorkflowId)"
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is RunRequest) return false
        return failOnDuplicate == other.failOnDuplicate &&
            allowQueryOnNonExistentWorkflow == other.allowQueryOnNonExistentWorkflow &&
            isRunAsync == other.isRunAsync &&
            workflowId == other.workflowId &&
            workflowClass == other.workflowClass &&
            workflowMethod == other.workflowMethod &&
            input == other.input &&
            requestContext == other.requestContext &&
            extraRequestData == other.extraRequestData &&
            callbackHandler == other.callbackHandler &&
            executionTimeout == other.executionTimeout &&
            parentWorkflowId == other.parentWorkflowId
    }

    override fun hashCode(): Int =
        Objects.hash(
            failOnDuplicate,
            allowQueryOnNonExistentWorkflow,
            isRunAsync,
            workflowId,
            workflowClass,
            workflowMethod,
            input,
            requestContext,
            extraRequestData,
            callbackHandler,
            executionTimeout,
            parentWorkflowId,
        )

    override fun toString(): String =
        "RunRequest(workflowId=$workflowId" +
            ", workflowClass=$workflowClass" +
            ", workflowMethod=$workflowMethod" +
            ", input=$input" +
            ", requestContext=$requestContext" +
            ", extraRequestData=$extraRequestData" +
            ", callbackHandler=$callbackHandler" +
            ", executionTimeout=$executionTimeout" +
            ", failOnDuplicate=$failOnDuplicate" +
            ", allowQueryOnNonExistentWorkflow=$allowQueryOnNonExistentWorkflow" +
            ", runAsync=$isRunAsync" +
            ", parentWorkflowId=$parentWorkflowId)"

    companion object {
        @JvmStatic
        fun builder(): RunRequestBuilder = RunRequestBuilder()
    }
}
