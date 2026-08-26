package com.airbnb.skipper

/**
 * Pluggable interface for propagating thread-local context across async boundaries
 * in Skipper workflow and action execution.
 *
 * Implementations capture the current thread's context state and restore it on
 * a different thread before coroutine resumption. This is essential for
 * ThreadLocal-based context (request context, tracing spans, MDC, etc.) that
 * would otherwise be lost when Kotlin coroutines resume on a different thread.
 *
 * ### Why this is needed
 *
 * Skipper's suspend function support invokes coroutines via [SuspendSupport] with
 * `EmptyCoroutineContext`. After a suspend action completes, the workflow coroutine
 * resumes on whichever thread the executor pool assigns — which may not be the
 * thread where `WorkflowExecutor.setContext()` set the ThreadLocal. Without
 * explicit capture/restore, all ThreadLocal-based context is lost after the first
 * action suspension.
 *
 * ### Usage
 *
 * Register an implementation via Guice. The default [NOOP] propagator does nothing
 * (safe for testing or when context propagation is not needed).
 *
 * ```kotlin
 * // In your Guice module:
 * bind(ContextPropagator::class.java).to(MyContextPropagator::class.java)
 * ```
 */
interface ContextPropagator {
    /**
     * Captures the current thread's context state.
     * Called before dispatching to an async boundary (e.g., executor pool).
     *
     * @return A [ContextSnapshot] that can restore the captured state on another thread.
     */
    fun capture(): ContextSnapshot

    companion object {
        /** No-op propagator for testing or when context propagation is not needed. */
        @JvmField
        val NOOP: ContextPropagator = object : ContextPropagator {
            override fun capture() = ContextSnapshot.NOOP
        }
    }
}
