package com.airbnb.skipper

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement
import org.slf4j.LoggerFactory

/**
 * Coroutine context element that propagates a [ContextSnapshot] across coroutine
 * dispatcher switches (e.g., `withContext(Dispatchers.IO)`).
 *
 * When the coroutine is dispatched to a new thread, [updateThreadContext] activates
 * the snapshot (restoring ThreadLocal state such as a host request context, tracing
 * spans, and MDC). When the coroutine leaves the thread, [restoreThreadContext]
 * closes the activation, restoring the thread's previous state.
 *
 * Safe to use with [ContextSnapshot.NOOP] — activation and close are no-ops.
 * Defensive: failures in activate/close are logged and do not propagate.
 */
class ScopeSnapshotContextElement(
    private val snapshot: ContextSnapshot
) : ThreadContextElement<AutoCloseable>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ScopeSnapshotContextElement> {
        private val log = LoggerFactory.getLogger(ScopeSnapshotContextElement::class.java)
    }

    override fun updateThreadContext(context: CoroutineContext): AutoCloseable =
        try {
            snapshot.activate()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable
        ) {
            log.debug("ContextSnapshot.activate() failed in updateThreadContext, context propagation disabled", e)
            ContextSnapshot.NOOP.activate()
        }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: AutoCloseable
    ) = try {
        oldState.close()
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Throwable
    ) {
        log.debug("AutoCloseable.close() failed in restoreThreadContext, thread state may be inconsistent", e)
    }
}
