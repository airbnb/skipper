package com.airbnb.skipper

import org.slf4j.LoggerFactory

/**
 * An opaque snapshot of thread-local context captured by [ContextPropagator.capture].
 *
 * Call [activate] to restore the captured context on the current thread.
 * The returned [AutoCloseable] **must** be closed to restore the previous thread
 * state and prevent context leaking into pooled threads.
 *
 * ### Typical usage
 *
 * ```kotlin
 * val snapshot = contextPropagator.capture()
 * // ... dispatch to another thread ...
 * snapshot.activate().use {
 *     // ThreadLocal context is restored here
 *     doWork()
 * }
 * // Previous thread state is restored after use {} block
 * ```
 */
interface ContextSnapshot {
    /**
     * Activates this snapshot on the current thread, restoring all captured context.
     *
     * @return An [AutoCloseable] that, when closed, restores the thread's previous state.
     *         Must be closed to prevent context leaking.
     */
    fun activate(): AutoCloseable

    companion object {
        /** No-op snapshot that does nothing on activate/close. */
        @JvmField
        val NOOP: ContextSnapshot = object : ContextSnapshot {
            override fun activate() = AutoCloseable { }
        }
    }
}

/**
 * Activates this [ContextSnapshot] and runs [block], restoring the previous thread state
 * afterward. If [ContextSnapshot.activate] throws, falls back to [ContextSnapshot.NOOP]
 * so that [block] still executes exactly once.
 */
fun <T> ContextSnapshot.runWithContext(block: () -> T): T {
    val scope = try {
        activate()
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Throwable
    ) {
        RunWithContextLog.log.debug("ContextSnapshot.activate() failed, running without context", e)
        ContextSnapshot.NOOP.activate()
    }
    return scope.use { block() }
}

private object RunWithContextLog {
    val log = LoggerFactory.getLogger(ContextSnapshot::class.java)
}
