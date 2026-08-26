package com.airbnb.skipper

import com.airbnb.skipper.internal.ActionExecutor.ExecuteActionRequest
import com.airbnb.skipper.util.SpanTagger
import com.google.common.collect.ImmutableMap
import io.opentracing.Span
import io.opentracing.Tracer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ExecutionMetricsCollector is responsible for creating distributed tracing spans and metrics for
 * workflow and action execution.
 *
 * This component provides centralized span creation for tracking execution flow through workflows
 * and their associated actions, including proper parent-child span relationships and consistent
 * tagging for observability.
 */
@Singleton
open class ExecutionMetricsCollector
    @Inject
    constructor(
        private val tracer: Tracer,
        private val spanTagger: SpanTagger
    ) {
        /**
         * Convenience constructor that uses [SpanTagger.DEFAULT]. Intended for callers that do not
         * need a custom tagging strategy (e.g. tests, open-source builds).
         */
        constructor(tracer: Tracer) : this(tracer, SpanTagger.DEFAULT)

        // `request` is nullable to preserve the Java signature's platform-type semantics: the
        // ported-from Java method performed no parameter null-check, and existing Kotlin tests
        // stub this method with Mockito `any()` (which supplies null). A strictly non-null Kotlin
        // parameter would make the compiler insert a call-site null-check that breaks those tests.
        // The JVM descriptor is unchanged, so external ABI is preserved.
        open fun createActionSpan(request: ExecuteActionRequest?): Span {
            val actionClass = request!!.baseActionClass.simpleName
            val actionMethod = request.actionMethodName
            val workflowClass = request.executionContext.workflow.workflowClass.simpleName
            val workflowMethod = request.executionContext.workflow.workflowMethod
            val extraRequestData = request.executionContext.workflow.extraRequestData

            // Action spans should be children of the active workflow span, not the request span.
            // Use useActiveSpanAsParent=true to make the action span a child of the active workflow
            // span.
            return extraRequestData.startNewSpan(
                tracer,
                String.format("action.%s.%s", actionClass, actionMethod),
                RESOURCE_TEMPO_ACTION,
                ImmutableMap.of(
                    TAG_COMPONENT,
                    RESOURCE_TEMPO_ACTION,
                    TAG_ACTION_CLASS,
                    actionClass,
                    TAG_ACTION_METHOD,
                    actionMethod,
                    TAG_WORKFLOW_CLASS,
                    workflowClass,
                    TAG_WORKFLOW_METHOD,
                    workflowMethod
                ),
                true, // useActiveSpanAsParent = true
                spanTagger
            )
        }

        // `workflowInstance` is nullable for the same reason as [createActionSpan]'s parameter:
        // to faithfully preserve the Java platform-type semantics (no parameter null-check) that
        // Mockito `any()` stubbing of this method relies on. The JVM descriptor is unchanged.
        open fun createWorkflowSpan(workflowInstance: WorkflowInstance?): Span {
            val workflowClass = workflowInstance!!.workflowClass.simpleName
            val workflowMethod = workflowInstance.workflowMethod
            val workflowStatus = workflowInstance.status.toString()
            val extraRequestData = workflowInstance.extraRequestData

            return extraRequestData.startNewSpan(
                tracer,
                String.format("workflow.%s.%s", workflowClass, workflowMethod),
                RESOURCE_TEMPO_WORKFLOW,
                ImmutableMap.of(
                    TAG_COMPONENT,
                    RESOURCE_TEMPO_WORKFLOW,
                    TAG_WORKFLOW_CLASS,
                    workflowClass,
                    TAG_WORKFLOW_METHOD,
                    workflowMethod,
                    TAG_WORKFLOW_STATUS,
                    workflowStatus
                ),
                false,
                spanTagger
            )
        }

        companion object {
            // Span tag constants
            const val TAG_COMPONENT: String = "component"
            const val TAG_ACTION_CLASS: String = "actionClass"
            const val TAG_ACTION_METHOD: String = "actionMethod"
            const val TAG_WORKFLOW_CLASS: String = "workflowClass"
            const val TAG_WORKFLOW_METHOD: String = "workflowMethod"
            const val TAG_WORKFLOW_STATUS: String = "workflowStatus"
            const val TAG_ACTION_RESULT: String = "actionResult"
            const val TAG_ERROR: String = "error"
            const val TAG_ERROR_OBJECT: String = "errorObject"

            // Resource tag values
            const val RESOURCE_TEMPO_ACTION: String = "tempo-action"
            const val RESOURCE_TEMPO_WORKFLOW: String = "tempo-workflow"
        }
    }
