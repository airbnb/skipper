package com.airbnb.skipper

import java.time.Duration

/**
 * Configuration class for specifying operational parameters when invoking a workflow. This class provides
 * settings related to timeouts that control how a workflow is executed.
 *
 * @property executionTimeout Specifies the maximum duration allowed for the completion of a workflow. This
 * duration includes both the time the workflow may spend queued for execution and the time taken to actually
 * execute the workflow. If null, no specific timeout is enforced, which may lead to indefinitely long execution
 * times depending on the system's default behavior or lack thereof.
 * @property refreshRequestContextForLongRunningWorkflows When `true`, Skipper asks the host's
 * [RequestContextMiddleware] to refresh (re-issue) the request-context credential before a
 * long-running workflow resumes, so the workflow's authentication does not expire partway through a
 * run that outlives the credential captured when it was scheduled. This is a no-op unless a host
 * middleware implements the refresh behaviour; deployments without such a middleware (including OSS)
 * carry the flag through untouched. The default is `false`, which leaves the request context exactly
 * as the caller supplied it.
 * @property allowQueryOnNonExistentWorkflow Indicates whether @Query methods can be made on
 * workflows that do not exist (workflows that have never run) If false, calling a @Query method on
 * such a workflow will throw an IllegalStateException. If true, the @Query method will run using
 * the workflow's initial state.
 * @property createExistingWorkflowIsNoop Indicates whether invoking a workflow method for an
 * existing workflow should return its current result without scheduling another execution.
 */
data class WorkflowOptions(
    val executionTimeout: Duration? = null,
    val refreshRequestContextForLongRunningWorkflows: Boolean = false,
    val allowQueryOnNonExistentWorkflow: Boolean = false,
    val createExistingWorkflowIsNoop: Boolean = false
) {
    constructor(executionTimeout: Duration?, refreshRequestContextForLongRunningWorkflows: Boolean) :
        this(executionTimeout, refreshRequestContextForLongRunningWorkflows, false, false)

    constructor(
        executionTimeout: Duration?,
        refreshRequestContextForLongRunningWorkflows: Boolean,
        allowQueryOnNonExistentWorkflow: Boolean,
    ) : this(
        executionTimeout,
        refreshRequestContextForLongRunningWorkflows,
        allowQueryOnNonExistentWorkflow,
        false,
    )

    /**
     * Merges this WorkflowOptions with another, with the other options taking precedence.
     * Non-null values from the override options will replace corresponding values in this options.
     *
     * @param override The WorkflowOptions to merge with this one, taking precedence
     * @return A new WorkflowOptions instance with merged values
     */
    fun mergeWith(override: WorkflowOptions?): WorkflowOptions {
        if (override == null) return this
        return WorkflowOptions(
            executionTimeout = override.executionTimeout ?: this.executionTimeout,
            refreshRequestContextForLongRunningWorkflows = override.refreshRequestContextForLongRunningWorkflows,
            allowQueryOnNonExistentWorkflow = override.allowQueryOnNonExistentWorkflow,
            createExistingWorkflowIsNoop = override.createExistingWorkflowIsNoop
        )
    }
}
