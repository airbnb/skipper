package com.airbnb.skipper.internal

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton

/**
 * The threads running actions, per workflow id, so cancelling a workflow can interrupt them in this
 * process. Best effort: an action that never blocks, or swallows the interrupt, runs to completion.
 * A workflow can run several actions at once (a signal handler runs beside the workflow method), so
 * each thread gets its own [Registration]. [exit] and [interrupt] update a workflow's entry one at a
 * time, so an interrupt never lands on a thread that has already left its action.
 */
@Singleton
class InFlightActions {
    class Registration internal constructor(internal val workflowId: String) {
        internal val thread: Thread = Thread.currentThread()
        @Volatile internal var interruptRequested = false
    }

    private val entries = ConcurrentHashMap<String, MutableList<Registration>>()

    /** Records the calling thread as running an action for [workflowId]. */
    fun enter(workflowId: String): Registration {
        val registration = Registration(workflowId)
        entries.compute(workflowId) { _, list -> (list ?: mutableListOf()).also { it.add(registration) } }
        return registration
    }

    /**
     * Forgets the registration and returns whether a cancel interrupted it. If so, it clears the
     * thread's interrupt flag so the interrupt does not leak into what the thread runs next.
     */
    fun exit(registration: Registration): Boolean {
        entries.computeIfPresent(registration.workflowId) { _, list ->
            list.remove(registration)
            if (list.isEmpty()) null else list
        }
        if (registration.interruptRequested) Thread.interrupted() // reads and clears the flag
        return registration.interruptRequested
    }

    /** Interrupts every thread running an action for [workflowId] here. Returns whether there was one. */
    fun interrupt(workflowId: String): Boolean =
        entries.computeIfPresent(workflowId) { _, list ->
            for (registration in list) {
                registration.interruptRequested = true
                registration.thread.interrupt()
            }
            list
        } != null
}
