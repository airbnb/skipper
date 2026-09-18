package com.airbnb.skipper.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InFlightActionsTest {
    private val registry = InFlightActions()

    @Test
    fun interruptReachesTheRegisteredThreadAndExitClearsTheFlag() {
        val entered = CountDownLatch(1)
        val sawInterrupt = AtomicBoolean(false)
        val exitReported = AtomicReference<Boolean>()
        val flagAfterExit = AtomicReference<Boolean>()
        val thread =
            Thread {
                val registration = registry.enter("wf")
                entered.countDown()
                try {
                    Thread.sleep(20_000)
                } catch (e: InterruptedException) {
                    sawInterrupt.set(true)
                }
                exitReported.set(registry.exit(registration))
                flagAfterExit.set(Thread.currentThread().isInterrupted)
            }
        thread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        assertTrue(registry.interrupt("wf"))

        thread.join(5_000)
        assertTrue(sawInterrupt.get())
        assertTrue(exitReported.get())
        assertFalse(flagAfterExit.get())
        assertFalse(registry.interrupt("wf"))
    }

    @Test
    fun interruptReachesEveryThreadRunningTheWorkflowsActions() {
        // A signal handler's action can run concurrently with the workflow method's action.
        val entered = CountDownLatch(2)
        val interruptedCount = AtomicInteger()
        val threads =
            List(2) {
                Thread {
                    val registration = registry.enter("wf")
                    entered.countDown()
                    try {
                        Thread.sleep(20_000)
                    } catch (e: InterruptedException) {
                        interruptedCount.incrementAndGet()
                    }
                    registry.exit(registration)
                }.also { it.start() }
            }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        assertTrue(registry.interrupt("wf"))

        threads.forEach { it.join(5_000) }
        assertEquals(2, interruptedCount.get())
        assertFalse(registry.interrupt("wf"))
    }

    @Test
    fun anInterruptRacingTheExitNeverLeaksOntoTheThread() {
        // Whichever wins, the thread leaves the bracket with a clear flag, and interrupt() reports
        // delivery exactly when that thread's exit() does.
        val leaked = AtomicInteger()
        val disagreed = AtomicInteger()
        repeat(2_000) {
            val entered = CountDownLatch(1)
            val reportedByExit = AtomicReference<Boolean>()
            val thread =
                Thread {
                    val registration = registry.enter("wf")
                    entered.countDown()
                    reportedByExit.set(registry.exit(registration))
                    if (Thread.interrupted()) leaked.incrementAndGet()
                }
            thread.start()
            entered.await(5, TimeUnit.SECONDS)
            val delivered = registry.interrupt("wf")
            thread.join(5_000)
            if (delivered != reportedByExit.get()) disagreed.incrementAndGet()
        }
        assertEquals(0, leaked.get())
        assertEquals(0, disagreed.get())
        assertFalse(registry.interrupt("wf"))
    }

    @Test
    fun interruptIsConfinedToTheCancelledWorkflow() {
        val entered = CountDownLatch(2)
        val interruptedOf = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        val threads =
            listOf("cancelled", "healthy").map { id ->
                Thread {
                    val registration = registry.enter(id)
                    entered.countDown()
                    try {
                        Thread.sleep(20_000)
                    } catch (e: InterruptedException) {
                        interruptedOf[id] = true
                    }
                    registry.exit(registration)
                }.also { it.start() }
            }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        assertTrue(registry.interrupt("cancelled"))

        threads[0].join(5_000)
        assertTrue(interruptedOf["cancelled"] == true)
        assertTrue(threads[1].isAlive)
        assertFalse(interruptedOf.containsKey("healthy"))
        assertTrue(registry.interrupt("healthy"))
        threads[1].join(5_000)
    }

    @Test
    fun exitWithoutAnInterruptReportsFalse() {
        val registration = registry.enter("wf")
        assertFalse(registry.exit(registration))
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test
    fun interruptingAnUnknownWorkflowDoesNothing() {
        assertFalse(registry.interrupt("nobody"))
    }
}
