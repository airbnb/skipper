package com.airbnb.skipper

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.reflect.jvm.kotlinFunction
import org.slf4j.LoggerFactory

/**
 * Utility object that enables Skipper to execute Kotlin `suspend` functions via Java reflection.
 *
 * When Kotlin compiles a `suspend fun foo(arg: T): R`, the bytecode signature becomes:
 * `Object foo(T arg, Continuation<? super R> continuation)`.
 *
 * Skipper's execution engine invokes workflow/action methods via `Method.invoke()` without
 * awareness of this transformation. This object provides the bridge between Skipper's
 * Java execution engine and Kotlin's coroutine machinery, using non-blocking
 * [CompletableFuture]-based invocation.
 *
 * All methods are `@JvmStatic` so they can be called directly from Java code
 * (e.g. `WorkflowExecutor.java`, `ActionExecutor.java`).
 */
object SuspendSupport {
    private val log = LoggerFactory.getLogger(SuspendSupport::class.java)

    /**
     * Returns true if the given [Method] is a Kotlin suspend function.
     *
     * Detection works by checking whether the last parameter type is a [Continuation],
     * which the Kotlin compiler appends to every suspend function's bytecode signature.
     */
    @JvmStatic
    fun isSuspendFunction(method: Method): Boolean {
        val params = method.parameterTypes
        return params.isNotEmpty() && Continuation::class.java.isAssignableFrom(params.last())
    }

    /**
     * Returns the number of user-declared parameters on [method], excluding the
     * compiler-generated [Continuation] parameter for suspend functions.
     *
     * This is the count that Skipper's validation logic should use (e.g. "at most 1 argument"
     * rules) so that suspend functions are not rejected due to the hidden Continuation.
     */
    @JvmStatic
    fun getUserParameterCount(method: Method): Int = if (isSuspendFunction(method)) method.parameterCount - 1 else method.parameterCount

    /**
     * Returns the user-declared parameter types on [method], excluding the
     * compiler-generated [Continuation] type for suspend functions.
     */
    @JvmStatic
    fun getUserParameterTypes(method: Method): Array<Class<*>> {
        val types = method.parameterTypes
        return if (isSuspendFunction(method)) types.copyOfRange(0, types.size - 1) else types
    }

    /**
     * Removes a trailing [Continuation] from a proxy handler's `args` array.
     *
     * Javassist proxy handlers receive all arguments including the Continuation when the
     * intercepted method is a suspend function. Before storing args in [ExecuteActionRequest]
     * or passing them to other logic, the Continuation must be stripped so that only
     * user-visible arguments remain.
     *
     * Returns the array unchanged if no Continuation is present (i.e. non-suspend methods).
     */
    @JvmStatic
    fun filterContinuationArgs(args: Array<Any?>): Array<Any?> {
        if (args.isEmpty()) return args
        return if (args.last() is Continuation<*>) {
            args.copyOfRange(0, args.size - 1)
        } else {
            args
        }
    }

    /**
     * Returns the effective return type of [method] for validation purposes.
     *
     * For regular (non-suspend) methods this is simply [Method.returnType]. For Kotlin suspend
     * functions the JVM bytecode return type is always `Object`, so the real declared return type
     * is extracted from the Kotlin metadata via kotlin-reflect.
     *
     * Returns `null` when Kotlin metadata is unavailable (e.g. a version mismatch between the
     * compiled class and the kotlin-reflect library). Callers should skip validation checks rather
     * than produce false-positive errors when `null` is returned.
     */
    @JvmStatic
    fun getEffectiveReturnType(method: Method): Class<*>? {
        if (!isSuspendFunction(method)) return method.returnType
        val kFunction = method.kotlinFunction ?: return null
        val classifier = kFunction.returnType.classifier
        return if (classifier is kotlin.reflect.KClass<*>) classifier.java else null
    }

    /**
     * Invokes a suspend function via `Method.invoke()` with a manually-constructed
     * [Continuation] and returns a [CompletableFuture] that completes when the coroutine
     * finishes. **This method does not block the calling thread.**
     *
     * If the suspend function completes synchronously (i.e. does not actually suspend),
     * the returned future is already completed when this method returns. If the function
     * suspends, the future remains incomplete until the coroutine resumes and finishes.
     *
     * ### Context propagation
     * When a [ContextSnapshot] is provided, its captured context is restored on the
     * completing thread before the [CompletableFuture] is completed. This ensures that
     * ThreadLocal-based context (request context, tracing spans, MDC) is available to
     * downstream `CompletableFuture` callbacks (e.g. `whenComplete`, `thenApply`).
     *
     * ### Why `Method.invoke()` instead of `kotlin-reflect`'s `callSuspend()`
     * We deliberately use `Method.invoke()` with a manually-created [Continuation] rather
     * than `KFunction.callSuspend()`. This is critical for action method invocation:
     * [com.airbnb.skipper.internal.ActionExecutor] receives a Javassist `originalMethod`
     * accessor which bypasses the proxy handler (i.e. calls the real implementation directly).
     * Using `callSuspend()` would look up a fresh `KFunction` from Kotlin metadata, which
     * resolves back to the declared method and would re-enter the proxy handler, causing
     * infinite recursion for action methods.
     *
     * ### Exception propagation
     * Exceptions thrown by the suspend function (including
     * [com.airbnb.skipper.internal.api.WaitSignal]) cause the returned [CompletableFuture] to
     * complete exceptionally. Callers obtain the exception via the future's error path
     * (e.g. `exceptionally()`, `whenComplete()`, or `join()` which throws
     * `CompletionException`).
     *
     * @param method The suspend [Method] to invoke. Must satisfy [isSuspendFunction].
     * @param target The object on which to invoke the method.
     * @param contextSnapshot Captured context to restore before completing the future.
     *                        Defaults to [ContextSnapshot.NOOP] (no context propagation).
     * @param userArgs The user-visible arguments (NOT including a [Continuation]).
     *                 The [Continuation] is created internally.
     * @return A [CompletableFuture] that completes with the result of the suspend function,
     *         or completes exceptionally if the function throws.
     */
    @JvmStatic
    @JvmOverloads
    fun invokeSuspendFunctionAsync(
        method: Method,
        target: Any,
        contextSnapshot: ContextSnapshot = ContextSnapshot.NOOP,
        vararg userArgs: Any?
    ): CompletableFuture<Any?> {
        val future = CompletableFuture<Any?>()
        val cont = object : Continuation<Any?> {
            // No CoroutineDispatcher: the non-blocking execution model relies on continuations
            // resuming on whatever thread completes the work — the action proxy's
            // whenCompleteAsync in Workflow.actions() controls the final dispatch back to
            // Skipper's executor. Adding a dispatcher would introduce an unnecessary thread hop.
            //
            // We DO include a ScopeSnapshotContextElement (a ThreadContextElement, NOT a
            // dispatcher) so that withContext() dispatcher switches inside the action body
            // automatically propagate ThreadLocal-based context (a host request context, tracing
            // spans, MDC). Safe with NOOP snapshots — activation and close are no-ops.
            override val context = ScopeSnapshotContextElement(contextSnapshot)

            override fun resumeWith(result: Result<Any?>) {
                // Restore captured context on the completing thread before completing the future.
                // This ensures ThreadLocal-based context is available in downstream CF callbacks.
                contextSnapshot.runWithContext { completeFuture(future, result) }
            }
        }
        val allArgs: Array<Any?> = arrayOf(*userArgs, cont)
        try {
            method.isAccessible = true
            val returnValue = method.invoke(target, *allArgs)
            // COROUTINE_SUSPENDED means the coroutine suspended — the Continuation will
            // complete the future when the coroutine eventually resumes. Do NOT call
            // future.complete() here or we'd store the sentinel as the result.
            //
            // If returnValue is anything else the method completed synchronously, so complete
            // the future now. future.complete() is idempotent: if resumeWith was already
            // called (e.g. by Kotlin's state machine before returning COROUTINE_SUSPENDED in
            // the exception path), the second complete() call is silently ignored.
            //
            // Context restoration is NOT needed here: the method ran synchronously on the
            // calling thread, which already has the ThreadLocal context set (by
            // WorkflowExecutor.setContext or the proxy handler's snapshot.activate). The
            // resumeWith path needs restoration because it runs on a DIFFERENT thread.
            if (returnValue !== COROUTINE_SUSPENDED) {
                @Suppress("UNCHECKED_CAST")
                future.complete(if (returnValue === Unit) null else returnValue)
            }
        } catch (e: InvocationTargetException) {
            future.completeExceptionally(e.cause ?: e)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable
        ) {
            // Intentionally broad: method.isAccessible and method.invoke can throw
            // IllegalAccessException, IllegalArgumentException, SecurityException, or
            // Error subclasses (e.g. ExceptionInInitializerError) that are not covered
            // by InvocationTargetException. Complete the future in all cases so that
            // callers never hang indefinitely waiting on an incomplete future.
            future.completeExceptionally(e)
        }
        return future
    }

    /**
     * Completes [future] based on the coroutine [result].
     * Normalizes `kotlin.Unit` to `null` for void suspend functions.
     * `complete`/`completeExceptionally` are idempotent — first call wins.
     */
    private fun completeFuture(
        future: CompletableFuture<Any?>,
        result: Result<Any?>
    ) {
        val cause = result.exceptionOrNull()
        if (cause != null) {
            future.completeExceptionally(cause)
        } else {
            val value = result.getOrNull()
            future.complete(if (value === Unit) null else value)
        }
    }
}
