package com.airbnb.skipper

import com.airbnb.skipper.util.ExtraRequestData

/**
 * Typed counterpart to [RawActionInvocation]. Carries the same fields, but the
 * `requestContext` slot is narrowed from `Any?` to `C?`. Constructed by
 * [RequestContextMiddleware] when it casts the engine's opaque payload to the concrete type
 * the host's impl declared.
 */
data class ActionInvocation<C : Any>(
    /** The workflow this invocation belongs to. */
    val workflowId: String,
    /** The narrowed request-context payload, or `null` when the caller supplied none. */
    val requestContext: C?,
    /** Per-invocation metadata (e.g. trace span context). */
    val extraRequestData: ExtraRequestData,
)

/**
 * Primary API for host integrations that want to participate in the workflow request-context
 * lifecycle. Subclasses declare the payload type `C` once (via the constructor) and override
 * three lifecycle hooks that work in their own type system (`C?`) instead of `Any?`.
 *
 * Skipper itself only sees the engine-facing [RawRequestContextMiddleware] surface — this
 * class is the typed-adapter layer. It implements the raw interface, performs a single
 * narrowing cast at each hook boundary (`Any?` → `C?`), and dispatches to the clean typed
 * methods below. If a foreign payload arrives, the cast throws [IllegalArgumentException]
 * with a curated message naming both the expected and actual class — better than a deep JVM
 * `ClassCastException` from a generated bridge method.
 *
 * ### Lifecycle and ordering
 *
 * For each workflow created:
 *   1. [onCreate] runs once, on the API request thread. The returned value is persisted on
 *      the [WorkflowInstance] and used for every subsequent action invocation.
 *
 * For each action / signal / compensation invocation:
 *   1. [beforeExecution] runs synchronously on the executor thread, immediately before the
 *      workflow method's bytecode is invoked. Hosts typically install thread-local state here
 *      and may transform the effective context for the invocation.
 *   2. Skipper invokes the workflow method on the same thread.
 *   3. [afterExecution] runs on the same thread in a `finally` block — guaranteed to be called
 *      whether the invocation succeeded or threw. Hosts clean up the state they installed in
 *      [beforeExecution] here.
 *
 * For async workflow / suspend methods, the synchronous slot covered by [beforeExecution] /
 * [afterExecution] is just the moment the method body returns the future — not the future's
 * eventual completion. Hosts that need state to be visible inside async continuations should
 * layer a [ContextPropagator] on top.
 *
 * ### Example
 *
 * ```kotlin
 * class MyMiddleware : RequestContextMiddleware<MyCtx>(MyCtx::class.java) {
 *     override fun onCreate(workflowId: String, ctx: MyCtx?): MyCtx? = ...
 * }
 * ```
 *
 * @param contextClass the concrete payload type this middleware handles. Captured to give
 *   curated error messages when a foreign payload reaches a hook.
 */
abstract class RequestContextMiddleware<C : Any>(
    private val contextClass: Class<C>,
) : RawRequestContextMiddleware {
    /**
     * Bridges the engine's `Any?` surface to the typed [onCreate] override. Subclasses should
     * override [onCreate] rather than this method — overriding the bridge directly bypasses
     * the [narrow] cast and gives up the curated error message. The bridge methods are
     * intentionally `open` (not `final`) so Mockito can spy/mock them in tests; please don't
     * override them in production code.
     */
    override fun rawOnCreate(
        workflowId: String,
        ctx: Any?,
    ): Any? = onCreate(workflowId, narrow(ctx))

    override fun rawBeforeExecution(invocation: RawActionInvocation): Any? = beforeExecution(toTyped(invocation))

    override fun rawAfterExecution(invocation: RawActionInvocation) {
        afterExecution(toTyped(invocation))
    }

    override fun rawStoreWorkflowCreationMetadata(
        options: WorkflowOptions,
        extraRequestData: ExtraRequestData,
    ) = storeWorkflowCreationMetadata(options, extraRequestData)

    /** See [RawRequestContextMiddleware.rawOnCreate]. Default: pass-through. */
    open fun onCreate(
        workflowId: String,
        ctx: C?,
    ): C? = ctx

    /** See [RawRequestContextMiddleware.rawBeforeExecution]. Default: pass-through. */
    open fun beforeExecution(invocation: ActionInvocation<C>): C? = invocation.requestContext

    /** See [RawRequestContextMiddleware.rawAfterExecution]. Default: no-op. */
    open fun afterExecution(invocation: ActionInvocation<C>) {}

    /** See [RawRequestContextMiddleware.rawStoreWorkflowCreationMetadata]. Default: no-op. */
    open fun storeWorkflowCreationMetadata(
        options: WorkflowOptions,
        extraRequestData: ExtraRequestData,
    ) {}

    /**
     * Narrow an opaque payload to `C?`. `null` passes through untouched; any other type that
     * isn't an instance of [contextClass] throws with a curated message naming both classes.
     */
    private fun narrow(ctx: Any?): C? {
        if (ctx == null) return null
        if (!contextClass.isInstance(ctx)) {
            throw IllegalArgumentException(
                "${this::class.java.simpleName} expected ${contextClass.name}" +
                    " but got ${ctx::class.java.name}",
            )
        }
        @Suppress("UNCHECKED_CAST")
        return ctx as C
    }

    private fun toTyped(invocation: RawActionInvocation): ActionInvocation<C> =
        ActionInvocation(
            workflowId = invocation.workflowId,
            requestContext = narrow(invocation.requestContext),
            extraRequestData = invocation.extraRequestData,
        )
}
