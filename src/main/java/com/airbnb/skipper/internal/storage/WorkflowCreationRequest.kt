package com.airbnb.skipper.internal.storage

import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowCallbackHandler
import com.airbnb.skipper.util.ExtraRequestData
import io.vavr.control.Option
import java.time.Instant

/** Represents the request to create a new workflow instance. */
data class WorkflowCreationRequest(
    /** The identifier for the workflow instance. Must be unique. */
    val workflowId: String,
    /** The class of the workflow to create. */
    val workflowClass: Class<out Workflow>,
    /**
     * The method to execute on the workflow. The method must be annotated with
     * [com.airbnb.skipper.WorkflowMethod]
     */
    val workflowMethod: String,
    /** The input to the workflow method. */
    val input: Any?,
    /** The request context captured at the workflow invocation call site. */
    val requestContext: Any?,
    /** The Extra Request Data captured at the workflow invocation call site. */
    val extraRequestData: ExtraRequestData,
    /** The callback handler to use for the workflow. */
    @get:JvmName("callbackHandlerClass")
    val callbackHandler: Class<out WorkflowCallbackHandler>?,
    /** The time at which the workflow should be considered timed out. */
    val timeoutTime: Instant?,
    /** The porent workflow ID (if any) */
    val parentWorkflowId: String?,
) {
    fun getCallbackHandler(): Option<Class<out WorkflowCallbackHandler>> = Option.of(callbackHandler)

    fun toBuilder(): WorkflowCreationRequestBuilder =
        WorkflowCreationRequestBuilder()
            .workflowId(workflowId)
            .workflowClass(workflowClass)
            .workflowMethod(workflowMethod)
            .input(input)
            .requestContext(requestContext)
            .extraRequestData(extraRequestData)
            .callbackHandler(callbackHandler)
            .timeoutTime(timeoutTime)
            .parentWorkflowId(parentWorkflowId)

    class WorkflowCreationRequestBuilder internal constructor() {
        private var workflowId: String? = null
        private var workflowClass: Class<out Workflow>? = null
        private var workflowMethod: String? = null
        private var input: Any? = null
        private var requestContext: Any? = null
        private var extraRequestData: ExtraRequestData? = null
        private var callbackHandler: Class<out WorkflowCallbackHandler>? = null
        private var timeoutTime: Instant? = null
        private var parentWorkflowId: String? = null

        /**
         * The identifier for the workflow instance. Must be unique.
         *
         * @return `this`.
         */
        fun workflowId(workflowId: String): WorkflowCreationRequestBuilder {
            this.workflowId = workflowId
            return this
        }

        /**
         * The class of the workflow to create.
         *
         * @return `this`.
         */
        fun workflowClass(workflowClass: Class<out Workflow>): WorkflowCreationRequestBuilder {
            this.workflowClass = workflowClass
            return this
        }

        /**
         * The method to execute on the workflow. The method must be annotated with
         * [com.airbnb.skipper.WorkflowMethod]
         *
         * @return `this`.
         */
        fun workflowMethod(workflowMethod: String): WorkflowCreationRequestBuilder {
            this.workflowMethod = workflowMethod
            return this
        }

        /**
         * The input to the workflow method.
         *
         * @return `this`.
         */
        fun input(input: Any?): WorkflowCreationRequestBuilder {
            this.input = input
            return this
        }

        /**
         * The request context captured at the workflow invocation call site.
         *
         * @return `this`.
         */
        fun requestContext(requestContext: Any?): WorkflowCreationRequestBuilder {
            this.requestContext = requestContext
            return this
        }

        /**
         * The Extra Request Data captured at the workflow invocation call site.
         *
         * @return `this`.
         */
        fun extraRequestData(extraRequestData: ExtraRequestData): WorkflowCreationRequestBuilder {
            this.extraRequestData = extraRequestData
            return this
        }

        /**
         * The callback handler to use for the workflow.
         *
         * @return `this`.
         */
        fun callbackHandler(callbackHandler: Class<out WorkflowCallbackHandler>?,): WorkflowCreationRequestBuilder {
            this.callbackHandler = callbackHandler
            return this
        }

        /**
         * The time at which the workflow should be considered timed out.
         *
         * @return `this`.
         */
        fun timeoutTime(timeoutTime: Instant?): WorkflowCreationRequestBuilder {
            this.timeoutTime = timeoutTime
            return this
        }

        /**
         * The porent workflow ID (if any)
         *
         * @return `this`.
         */
        fun parentWorkflowId(parentWorkflowId: String?): WorkflowCreationRequestBuilder {
            this.parentWorkflowId = parentWorkflowId
            return this
        }

        fun build(): WorkflowCreationRequest =
            WorkflowCreationRequest(
                workflowId
                    ?: throw NullPointerException("workflowId is marked non-null but is null"),
                workflowClass
                    ?: throw NullPointerException("workflowClass is marked non-null but is null"),
                workflowMethod
                    ?: throw NullPointerException("workflowMethod is marked non-null but is null"),
                input,
                requestContext,
                extraRequestData
                    ?: throw NullPointerException(
                        "extraRequestData is marked non-null but is null",
                    ),
                callbackHandler,
                timeoutTime,
                parentWorkflowId,
            )

        override fun toString(): String =
            "WorkflowCreationRequest.WorkflowCreationRequestBuilder(workflowId=$workflowId" +
                ", workflowClass=$workflowClass, workflowMethod=$workflowMethod, input=$input" +
                ", requestContext=$requestContext, extraRequestData=$extraRequestData" +
                ", callbackHandler=$callbackHandler, timeoutTime=$timeoutTime" +
                ", parentWorkflowId=$parentWorkflowId)"
    }

    companion object {
        @JvmStatic
        fun builder(): WorkflowCreationRequestBuilder = WorkflowCreationRequestBuilder()
    }
}
