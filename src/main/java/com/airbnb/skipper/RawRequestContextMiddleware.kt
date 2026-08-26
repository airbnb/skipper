package com.airbnb.skipper

import com.airbnb.skipper.util.ExtraRequestData

/**
 * Per-action handle passed to [RawRequestContextMiddleware] hooks.
 *
 * Skipper bundles everything a middleware impl might want to consult into this object so the
 * hook signatures stay stable as new per-invocation metadata is added. The opaque
 * `requestContext` is `Any?` here — for typed access, host integrations should extend
 * [RequestContextMiddleware] and receive an [ActionInvocation] with a narrowed payload.
 */
data class RawActionInvocation(
    /** The workflow this invocation belongs to. */
    val workflowId: String,
    /**
     * The opaque request-context payload the caller supplied when the workflow was created
     * (after [RawRequestContextMiddleware.rawOnCreate] has transformed it). Skipper itself
     * reads nothing from this value — host integrations downcast (or use the typed
     * [RequestContextMiddleware] subclass) to whatever concrete type they own.
     */
    val requestContext: Any?,
    /** Per-invocation metadata (e.g. trace span context, the refreshRequestContextForLongRunningWorkflows flag). */
    val extraRequestData: ExtraRequestData,
)

/**
 * Low-level engine-facing lifecycle hooks. The engine calls these three methods directly with
 * the opaque `Any?` payload it persists. Most hosts should NOT implement this interface — use
 * the typed [RequestContextMiddleware] subclass instead, which narrows the payload to a
 * concrete type and dispatches to clean `onCreate` / `beforeExecution` / `afterExecution`
 * overrides.
 *
 * This interface is here for engine internals and for the rare host that wants to bypass the
 * typed-adapter convenience.
 *
 * All methods default to no-ops, so deployments that don't carry per-request identity (most
 * OSS) need wire nothing.
 *
 * ### Lifecycle and ordering
 *
 * For each workflow created:
 *   1. [rawOnCreate] runs once, on the API request thread. The returned value is persisted on
 *      the [WorkflowInstance] and used for every subsequent action invocation.
 *   2. [rawStoreWorkflowCreationMetadata] runs once, on the same thread, right after
 *      [rawOnCreate]. Hosts persist per-workflow metadata derived from [WorkflowOptions] into the
 *      durable [ExtraRequestData] here so the execution-time hooks can read it after the workflow
 *      later resumes.
 *
 * For each action / signal / compensation invocation, and for each [WorkflowCallbackHandler]
 * notification the engine fires from a scheduler thread:
 *   1. [rawBeforeExecution] runs synchronously on the executor thread, immediately before the
 *      invoked code runs. Host integrations typically install thread-local state here (e.g. an
 *      Airbnb-style request-context thread-local) and may transform the effective context for
 *      the invocation.
 *   2. Skipper invokes the workflow method — or the callback method — on the same thread.
 *   3. [rawAfterExecution] runs on the same thread in a `finally` block — guaranteed to be
 *      called whether the invocation succeeded or threw. Host integrations clean up the state
 *      they installed in [rawBeforeExecution] here.
 *
 * The cancellation callback is the one exception: the engine fires it synchronously on the
 * caller's own API request thread, where the ambient context is already the live caller's, so it
 * is not bracketed.
 *
 * Important: for workflow methods that return `CompletableFuture` or are Kotlin suspend
 * functions, the synchronous slot covered by [rawBeforeExecution] / [rawAfterExecution] is
 * just the moment the method body returns the future — not the future's eventual completion.
 * Hosts that need state to be visible inside async continuations should layer a
 * [ContextPropagator] on top.
 */
interface RawRequestContextMiddleware {
    /**
     * Called once at workflow creation, on the thread that's creating the workflow. The
     * returned value is persisted on the [WorkflowInstance] as the request-context payload
     * and used for every subsequent action invocation on this workflow.
     *
     * Hosts use this to derive fields from the caller-supplied context (for example,
     * resolving a missing user ID from an auth token). Default: pass-through.
     */
    fun rawOnCreate(
        workflowId: String,
        ctx: Any?,
    ): Any? = ctx

    /**
     * Called synchronously before every action invocation, on the executor thread that will
     * run the action. Hosts use this to install thread-local state and / or compute an
     * effective context for the invocation.
     *
     * The returned value is the "effective" context for this invocation. Skipper does not
     * inspect it; it is the value the host's own code (running inside the action) will see
     * via whatever propagation mechanism the host installed.
     *
     * Skipper guarantees [rawAfterExecution] is called in a `finally` block, so any state
     * installed here will be cleaned up even if the action throws.
     *
     * Default: pass-through; no state installed.
     */
    fun rawBeforeExecution(invocation: RawActionInvocation): Any? = invocation.requestContext

    /**
     * Called synchronously after every action invocation, on the same thread that ran
     * [rawBeforeExecution]. Skipper invokes this in a `finally` block so it runs whether the
     * action succeeded or threw.
     *
     * Hosts use this to clean up thread-local state installed by [rawBeforeExecution].
     * Default: no-op.
     */
    fun rawAfterExecution(invocation: RawActionInvocation) {}

    /**
     * Called once at workflow creation, on the thread that's creating the workflow (right after
     * [rawOnCreate]). Hosts use this to derive per-workflow metadata from [options] and stash it
     * in the durable [extraRequestData] so it survives serialization and is available to the
     * execution-time hooks ([rawBeforeExecution] / [rawAfterExecution]) when the workflow later
     * resumes. Skipper itself writes nothing here.
     *
     * Default: no-op.
     */
    fun rawStoreWorkflowCreationMetadata(
        options: WorkflowOptions,
        extraRequestData: ExtraRequestData,
    ) {}

    companion object {
        /** No-op middleware. Used as the default when the host doesn't supply one. */
        @JvmField
        val NOOP: RawRequestContextMiddleware = object : RawRequestContextMiddleware {}
    }
}
