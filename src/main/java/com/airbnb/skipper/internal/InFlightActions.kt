package com.airbnb.skipper.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Singleton

/**
 * The threads currently running actions, per workflow id, so cancelling a workflow can interrupt them
 * in this process. Same process only, best effort: an action that never blocks, or swallows the
 * interrupt, runs to completion.
 *
 * A workflow can have several actions in flight at once (a signal handler runs concurrently with the
 * workflow method), so each registration is its own [Registration]; an interrupt reaches all of them,
 * and each thread removes only its own. The per-registration lock keeps an interrupt from landing on
 * a thread that has already left its action, so a thread never carries an unreported interrupt out.
 */
@Singleton
class InFlightActions {
    class Registration internal constructor(internal val workflowId: String) {
        internal val thread: Thread = Thread.currentThread()
        internal var interruptRequested = false
        internal var exited = false
    }

    private val entries = ConcurrentHashMap<String, CopyOnWriteArrayList<Registration>>()

    /** Records the calling thread as running an action for [workflowId]. */
    fun enter(workflowId: String): Registration {
        val registration = Registration(workflowId)
        entries.compute(workflowId) { _, list -> (list ?: CopyOnWriteArrayList()).also { it.add(registration) } }
        return registration
    }

    /**
     * Forgets the registration. Returns whether a cancel interrupted it; in that case the thread's
     * interrupt flag, set by [interrupt], is cleared so it does not leak into what runs next.
     */
    fun exit(registration: Registration): Boolean {
        entries.computeIfPresent(registration.workflowId) { _, list ->
            list.remove(registration)
            if (list.isEmpty()) null else list
        }
        synchronized(registration) {
            registration.exited = true
            if (registration.interruptRequested) Thread.interrupted()
            return registration.interruptRequested
        }
    }

    /** Interrupts every thread running an action for [workflowId] here. Returns whether there was one. */
    fun interrupt(workflowId: String): Boolean {
        var delivered = false
        for (registration in entries[workflowId] ?: return false) {
            synchronized(registration) {
                if (!registration.exited) {
                    registration.interruptRequested = true
                    registration.thread.interrupt()
                    delivered = true
                }
            }
        }
        return delivered
    }
}
