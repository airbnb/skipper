package com.airbnb.skipper

/**
 * Defines an interface for creating runtime proxy instances that execute [Workflow] objects within
 * the Skipper workflow system. This interface facilitates the dynamic creation of workflow
 * instances, which are then used to execute defined workflow methods transparently, handling the
 * invocation logic and state management internally.
 *
 * Implementations of this interface are responsible for providing the functionality to instantiate
 * specific [Workflow] subclasses as proxies. These proxies intercept method calls to the workflow
 * methods, allowing the system to manage workflow execution flow, perform logging, or handle
 * transaction mechanism, as required.
 *
 * ### Usage:
 * Typically, this interface is used in the context of a service or controller that orchestrates
 * workflow execution. An implementation of `IWorkflowFactory` might be injected into these
 * higher-level components. Workflows are then instantiated and executed dynamically based on
 * application logic or user actions.
 *
 * ```kotlin
 * // Example of obtaining a workflow instance from a factory
 * val workflow: IWorkflowFactory = getWorkflowFactory()
 * val myWorkflow = workflow<MyWorkflow>("workflowId", requestContext)
 * myWorkflow.executeWorkflowMethod()
 * ```
 *
 * The `requestContext` parameter is an opaque payload Skipper persists with the workflow and
 * passes through to the host's [RawRequestContextMiddleware] at each lifecycle hook. Skipper
 * itself reads nothing from it. Pass `null` if your deployment doesn't carry per-request
 * identity.
 */
interface IWorkflowFactory {
    /**
     * Invokes the workflow factory to create an instance of the specified [Workflow] subclass
     * with custom workflow options that override the global defaults.
     *
     * This operator allows for the dynamic creation of workflow instances based on the
     * class type passed as a parameter, with per-invocation workflow options.
     *
     * @param T The type of the workflow to be created. This type must extend [Workflow].
     * @param workflowClass The Java [Class] object representing the workflow class to be instantiated.
     * @param workflowId The unique identifier of the workflow to operate on. It acts as an idempotency
     * key, e.g. prevents starting a new workflow that has the same id as another workflow, regardless
     * of the state of the existing workflow as well as request params of the new workflow. If a workflow
     * with the given id is or has been run, attempting to start a new one with the same id will result in
     * awaiting the completion of the running workflow or immediately returning the recorded execution result.
     * @param requestContext The request context explicitly provided at call site if current request
     * context stored in ThreadLocal needs to be overridden.
     * @param callbackHandler The callback handler to be used for handling workflow execution callbacks.
     * @param workflowOptions The workflow options to use for this specific invocation, overriding global defaults.
     * @param runAsync If true, the workflow will be scheduled for execution and not executed in the same process.
     * @param parentWorkflowId The id of the parent workflow (in case this is a child workflow)
     * @param detached If true, the invocation returns as soon as the workflow instance is persisted and
     * scheduled, without waiting for the result. See [InvocationBuilder.detached].
     * @return An instance of the specified class [T], fully constructed and initialized.
     */
    operator fun <T : Workflow> invoke(
        workflowClass: Class<T>,
        workflowId: String,
        requestContext: Any?,
        callbackHandler: Class<out WorkflowCallbackHandler>?,
        workflowOptions: WorkflowOptions?,
        runAsync: Boolean = false,
        parentWorkflowId: String? = null
    ): T

    /**
     * The same invocation, with the option to detach it from the workflow result. See
     * [InvocationBuilder.detached].
     *
     * Kept as a separate overload rather than a parameter on the primary [invoke] so that adding
     * `detached` stays source-compatible: Java callers do not see Kotlin default arguments, so widening
     * the primary signature would break every seven-argument call site and every Java implementor.
     *
     * The default implementation ignores [detached] and delegates, which is the correct behavior for
     * test fakes — detachment is a property of the real proxy's invocation path, not of a canned
     * handle. Implementations that dispatch real workflows must override this.
     *
     * Declares no default values, deliberately: giving both overloads defaults would make five- to
     * seven-argument Kotlin calls ambiguous between them.
     */
    operator fun <T : Workflow> invoke(
        workflowClass: Class<T>,
        workflowId: String,
        requestContext: Any?,
        callbackHandler: Class<out WorkflowCallbackHandler>?,
        workflowOptions: WorkflowOptions?,
        runAsync: Boolean,
        parentWorkflowId: String?,
        detached: Boolean
    ): T {
        return invoke(workflowClass, workflowId, requestContext, callbackHandler, workflowOptions, runAsync, parentWorkflowId)
    }

    operator fun <T : Workflow> invoke(
        workflowClass: Class<T>,
        workflowId: String,
        requestContext: Any?,
        callbackHandler: Class<out WorkflowCallbackHandler>?,
        workflowOptions: WorkflowOptions?,
        runAsync: Boolean = false
    ): T {
        return invoke(workflowClass, workflowId, requestContext, callbackHandler, workflowOptions, runAsync, null)
    }

    operator fun <T : Workflow> invoke(
        workflowClass: Class<T>,
        workflowId: String,
        requestContext: Any?,
        callbackHandler: Class<out WorkflowCallbackHandler>?,
        workflowOptions: WorkflowOptions?
    ): T {
        return invoke(workflowClass, workflowId, requestContext, callbackHandler, workflowOptions, false)
    }

    /**
     * Invokes the workflow factory to create an instance of the specified [Workflow] subclass.
     *
     * This operator allows for the dynamic creation of workflow instances based on the
     * class type passed as a parameter.
     *
     * @param T The type of the workflow to be created. This type must extend [Workflow].
     * @param workflowClass The Java [Class] object representing the workflow class to be instantiated.
     * @param workflowId The unique identifier of the workflow to operate on. It acts as an idempotency
     * key, e.g. prevents starting a new workflow that has the same id as another workflow, regardless
     * of the state of the existing workflow as well as request params of the new workflow. If a workflow
     * with the given id is or has been run, attempting to start a new one with the same id will result in
     * awaiting the completion of the running workflow or immediately returning the recorded execution result.
     * @param requestContext The request context explicitly provided at call site if current request
     * context stored in ThreadLocal needs to be overridden.
     * @param callbackHandler The callback handler to be used for handling workflow execution callbacks.
     * @return An instance of the specified class [T], fully constructed and initialized.
     */
    operator fun <T : Workflow> invoke(
        workflowClass: Class<T>,
        workflowId: String,
        requestContext: Any?,
        callbackHandler: Class<out WorkflowCallbackHandler>?
    ): T {
        return invoke(workflowClass, workflowId, requestContext, callbackHandler, null)
    }

    /**
     * Invokes the workflow factory to create an instance of the specified [Workflow] subclass.
     *
     * This operator allows for the dynamic creation of workflow instances based on the
     * class type passed as a parameter.
     *
     * @param T The type of the workflow to be created. This type must extend [Workflow].
     * @param workflowClass The Java [Class] object representing the workflow class to be instantiated.
     * @param workflowId The unique identifier of the workflow to operate on. It acts as an idempotency
     * key, e.g. prevents starting a new workflow that has the same id as another workflow, regardless
     * of the state of the existing workflow as well as request params of the new workflow. If a workflow
     * with the given id is or has been run, attempting to start a new one with the same id will result in
     * awaiting the completion of the running workflow or immediately returning the recorded execution result.
     * @param requestContext The request context explicitly provided at call site if current request
     * context stored in ThreadLocal needs to be overridden.
     * @return An instance of the specified class [T], fully constructed and initialized.
     */
    operator fun <T : Workflow> invoke(
        workflowClass: Class<T>,
        workflowId: String,
        requestContext: Any?
    ): T {
        return invoke(workflowClass, workflowId, requestContext, null)
    }

    /**
     * Run a workflow without a per-invocation request context.
     *
     * Convenience for Java callers that want to fire-and-forget without supplying a payload.
     * Equivalent to [invoke] with a null `requestContext`; the configured
     * [RawRequestContextMiddleware] still runs and may inject one via `onCreate` if the host wires
     * that path.
     *
     * @param T The type of the workflow to be created. This type must extend [Workflow].
     * @param workflowClass The Java [Class] object representing the workflow class to be instantiated.
     * @param workflowId The unique identifier of the workflow to operate on. See the full
     *   [invoke] overload for idempotency-key semantics.
     * @return An instance of the specified class [T], fully constructed and initialized.
     */
    operator fun <T : Workflow> invoke(
        workflowClass: Class<T>,
        workflowId: String
    ): T {
        return invoke(workflowClass, workflowId, null)
    }

    /**
     * Create a workflow invocation builder.
     *
     * This is the recommended way for Java clients to construct workflow instances using the workflow factory.
     *
     * ### Usage:
     *
     * ```java
     * MyWorkflow myWorkflow = workflowFactory.builder(MyWorkflow.class, "workflowId")
     *   .requestContext(requestContext)
     *   .callbackHandler(MyWorkflowCallbackHandler.class)
     *   .build();
     * ```
     *
     * @param T The type of the workflow to be created. This type must extend [Workflow].
     * @param workflowClass The Java [Class] object representing the workflow class to be instantiated.
     * @param workflowId The unique identifier of the workflow to operate on. It acts as an idempotency
     * key, e.g. prevents starting a new workflow that has the same id as another workflow, regardless
     * of the state of the existing workflow as well as request params of the new workflow. If a workflow
     * with the given id is or has been run, attempting to start a new one with the same id will result in
     * awaiting the completion of the running workflow or immediately returning the recorded execution result.
     * @return An instance of [InvocationBuilder] that can be used to populate additional creation parameters
     * and ultimately build the workflow.
     * @see InvocationBuilder
     */
    fun <T : Workflow> builder(
        workflowClass: Class<T>,
        workflowId: String
    ): InvocationBuilder<T> {
        return InvocationBuilder(workflowClass, workflowId, this)
    }

    /**
     * Clones a workflow instance with the given workflowIdToClone and newWorkflowId.
     *
     * The new workflow instance will have the same input as the original workflow, but its execution will start from the beginning, meaning that
     * the workflow state of the original workflow will not be copied over. It's also important to note that the new workflow instance will have
     * no relation or dependency with the original workflow instance and its execution will be independent.
     *
     * This method won't wait for the workflow execution of the new workflow to complete, and it will return as soon as the workflow is created
     * and scheduled for execution.
     *
     * @param workflowIdToClone the workflowId of the workflow instance to clone
     * @param newWorkflowId the workflowId of the new workflow instance
     * @param requestContext the request context
     * @throws IllegalArgumentException if the workflowIdToClone or newWorkflowId is invalid
     */
    fun cloneAsNew(
        workflowIdToClone: String,
        newWorkflowId: String,
        requestContext: Any?
    )
}

/**
 * Helper class for Java clients to construct workflow builder.
 *
 * Using invocation builder is the recommended way for Java clients to construct workflow instances using
 * workflow factory.
 */
class InvocationBuilder<T : Workflow>(
    private val workflowClass: Class<T>,
    private val workflowId: String,
    private val workflowFactory: IWorkflowFactory
) {
    private var requestContext: Any? = null
    private var callbackHandler: Class<out WorkflowCallbackHandler>? = null
    private var workflowOptions: WorkflowOptions? = null
    private var runAsync: Boolean = false
    private var parentWorkflowId: String? = null
    private var detached: Boolean = false

    fun requestContext(requestContext: Any?): InvocationBuilder<T> {
        this.requestContext = requestContext
        return this
    }

    fun callbackHandler(callbackHandler: Class<out WorkflowCallbackHandler>): InvocationBuilder<T> {
        this.callbackHandler = callbackHandler
        return this
    }

    fun workflowOptions(workflowOptions: WorkflowOptions): InvocationBuilder<T> {
        this.workflowOptions = workflowOptions
        return this
    }

    fun runAsync(): InvocationBuilder<T> {
        this.runAsync = true
        return this
    }

    fun parentWorkflowId(parentWorkflowId: String): InvocationBuilder<T> {
        this.parentWorkflowId = parentWorkflowId
        return this
    }

    /**
     * Detach the invocation from the workflow result: the call returns as soon as the instance is
     * persisted and scheduled, rather than when execution finishes.
     *
     * This is the fire-and-forget mode. It makes `suspend` workflow methods usable without a
     * [java.util.concurrent.CompletableFuture] return type — a detached suspend call resumes the caller
     * immediately instead of when the workflow completes — and it skips the result polling a normal
     * invocation performs, so no thread is held waiting on a result nobody reads.
     *
     * Durability is unaffected: the instance is written to storage and handed to the scheduler before the
     * call returns, so the workflow is still guaranteed to run at least once.
     *
     * The workflow method must produce no result (`Unit`/`void`, or `CompletableFuture<Void>`), since a
     * detached call has nothing to hand back; invoking a result-bearing method detached fails validation.
     * A detached caller sees neither the result nor any failure — register a [WorkflowCallbackHandler] if
     * you need to observe the outcome.
     *
     * Has no effect on signal and query methods, which always execute synchronously.
     */
    fun detached(): InvocationBuilder<T> {
        this.detached = true
        return this
    }

    /**
     * Dispatches through the seven-argument [IWorkflowFactory.invoke] unless [detached] was requested.
     *
     * This is deliberate rather than always calling the eight-argument overload: consumers commonly stub
     * or fake `invoke` on a mocked factory, and those stubs are keyed to the exact overload the builder
     * calls. Routing every invocation through the new overload would silently bypass them, so a
     * non-detached build must issue exactly the call it always has.
     */
    fun build(): T =
        if (detached) {
            workflowFactory.invoke(workflowClass, workflowId, requestContext, callbackHandler, workflowOptions, runAsync, parentWorkflowId, true)
        } else {
            workflowFactory.invoke(workflowClass, workflowId, requestContext, callbackHandler, workflowOptions, runAsync, parentWorkflowId)
        }
}

/**
 * Reified-type convenience that omits the request context entirely. Use this in tests or in
 * deployments that don't carry per-request identity.
 */
inline operator fun <reified T : Workflow> IWorkflowFactory.invoke(workflowId: String): T {
    return invoke(T::class.java, workflowId, null)
}

/**
 * Invokes the workflow factory to create an instance of the specified [Workflow] subclass using
 * reified type parameters.
 *
 * The function internally delegates to the [invoke] operator of [IWorkflowFactory] which
 * takes a [Class] as its parameter, leveraging the reified type to retrieve the corresponding
 * Java [Class] object.
 *
 * @param T The type of the workflow to be created. This type must extend [Workflow] and is
 * inferred automatically from the usage context.
 * @param workflowId The unique identifier of the workflow to operate on.
 * @param requestContext The request context explicitly provided at call site.
 */
inline operator fun <reified T : Workflow> IWorkflowFactory.invoke(
    workflowId: String,
    requestContext: Any?
): T {
    return invoke(T::class.java, workflowId, requestContext)
}

/**
 * Invokes the workflow factory to create an instance of the specified [Workflow] subclass using
 * reified type parameters with a callback handler.
 */
inline operator fun <reified T : Workflow> IWorkflowFactory.invoke(
    workflowId: String,
    requestContext: Any?,
    callbackHandler: Class<out WorkflowCallbackHandler>
): T {
    return invoke(T::class.java, workflowId, requestContext, callbackHandler)
}

/**
 * Invokes the workflow factory to create an instance of the specified [Workflow] subclass using
 * reified type parameters with custom workflow options.
 */
inline operator fun <reified T : Workflow> IWorkflowFactory.invoke(
    workflowId: String,
    requestContext: Any?,
    workflowOptions: WorkflowOptions
): T {
    return invoke(T::class.java, workflowId, requestContext, null, workflowOptions)
}
