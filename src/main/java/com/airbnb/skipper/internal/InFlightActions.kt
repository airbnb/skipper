package com.airbnb.skipper.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import javax.inject.Singleton

/**
 * Per workflow id, the threads running actions and the futures those actions returned, so a cancel in
 * this process can stop them. Best effort: an action that never blocks, or swallows the interrupt, runs
 * to completion. A workflow can run several actions at once (a signal handler runs beside the workflow
 * method), so each thread or future gets its own [Registration]. [exit] and [interrupt] update an entry
 * one at a time, so an interrupt never lands on a thread that has already left its action.
 */
@Singleton
class InFlightActions {
    class Registration internal constructor(
        internal val workflowId: String,
        internal val thread: Thread?,
        internal val future: Future<*>?,
    ) {
        @Volatile internal var interruptRequested = false
    }

    private val entries = ConcurrentHashMap<String, MutableList<Registration>>()

    /** Records the calling thread as running an action for [workflowId]. */
    fun enter(workflowId: String): Registration = add(Registration(workflowId, Thread.currentThread(), null))

    /**
     * Records [future] as the pending result of an action for [workflowId], so a cancel cancels it. Best
     * effort: the workflow stops waiting, but the work behind the future keeps running unless it checks for the cancel.
     */
    fun enter(
        workflowId: String,
        future: Future<*>,
    ): Registration = add(Registration(workflowId, null, future))

    private fun add(registration: Registration): Registration {
        entries.compute(registration.workflowId) { _, list -> (list ?: mutableListOf()).also { it.add(registration) } }
        return registration
    }

    /**
     * Forgets the registration and returns whether a cancel reached it. For a thread, it also
     * clears the interrupt flag so the interrupt does not leak into what the thread runs next.
     */
    fun exit(registration: Registration): Boolean {
        entries.computeIfPresent(registration.workflowId) { _, list ->
            list.remove(registration)
            if (list.isEmpty()) null else list
        }
        if (registration.interruptRequested && registration.thread != null) Thread.interrupted() // reads and clears the flag
        return registration.interruptRequested
    }

    /** The workflows with an action running here right now. */
    fun workflowIds(): Set<String> = entries.keys.toSet()

    /**
     * Interrupts each thread and cancels each future registered for [workflowId] in this process, at most
     * once per registration. Returns whether this call interrupted or cancelled any.
     */
    fun interrupt(workflowId: String): Boolean {
        var delivered = false
        val futures = mutableListOf<Future<*>>()
        entries.computeIfPresent(workflowId) { _, list ->
            for (registration in list) {
                if (!registration.interruptRequested) {
                    registration.interruptRequested = true
                    registration.thread?.interrupt()
                    registration.future?.let { futures.add(it) }
                    delivered = true
                }
            }
            list
        }
        // Cancel after the update: cancelling may run each future's callbacks on this thread, and their
        // exit() would change the list this loop is walking.
        futures.forEach { it.cancel(true) }
        return delivered
    }
}
