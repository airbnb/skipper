package com.airbnb.skipper.internal

import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.InMemoryFeatureGate
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.storage.WorkflowStore
import com.airbnb.skipper.internal.testutils.RecordingMetrics
import io.vavr.control.Option
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class InFlightCancelWatcherTest {
    private val registry = InFlightActions()
    private val store: WorkflowStore = mock()
    private val metrics = RecordingMetrics()
    private val bothOn =
        InMemoryFeatureGate(
            mapOf(
                FeatureGate.Keys.INFLIGHT_CANCELLATION_INTERRUPT to true,
                FeatureGate.Keys.INFLIGHT_CANCELLATION_CHECKPOINTS to true,
            ),
        )

    private fun watcher(gate: FeatureGate) = InFlightCancelWatcher(registry, store, gate, metrics, Duration.ofSeconds(5))

    private fun stored(status: WorkflowInstance.Status) = Option.of(TestUtils.getWorkflowInstance().toBuilder().status(status).build())

    /** A thread registered as running an action for [workflowId], blocked until interrupted. */
    private fun blockedAction(workflowId: String): Pair<Thread, AtomicBoolean> {
        val entered = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val thread =
            Thread {
                val registration = registry.enter(workflowId)
                entered.countDown()
                try {
                    Thread.sleep(20_000)
                } catch (e: InterruptedException) {
                    interrupted.set(true)
                } finally {
                    registry.exit(registration)
                }
            }
        thread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        return thread to interrupted
    }

    @Test
    fun pollInterruptsAnActionWhoseWorkflowIsCancelledInTheStore() {
        whenever(store.getWorkflow("wf")).thenReturn(stored(WorkflowInstance.Status.CANCELLED))
        val (thread, interrupted) = blockedAction("wf")

        watcher(bothOn).pollOnce()

        thread.join(5_000)
        assertTrue(interrupted.get())
        assertTrue(metrics.incremented.contains("inflightCancellation.watcherInterrupts"))
    }

    @Test
    fun pollLeavesARunningWorkflowAlone() {
        whenever(store.getWorkflow("wf")).thenReturn(stored(WorkflowInstance.Status.RUNNING))
        val (thread, interrupted) = blockedAction("wf")

        watcher(bothOn).pollOnce()

        thread.join(500)
        assertTrue(thread.isAlive)
        assertFalse(interrupted.get())
        registry.interrupt("wf")
        thread.join(5_000)
    }

    @Test
    fun pollReadsNothingWhenTheInterruptFlagIsOff() {
        val (thread, _) = blockedAction("wf")

        watcher(InMemoryFeatureGate(mapOf(FeatureGate.Keys.INFLIGHT_CANCELLATION_CHECKPOINTS to true))).pollOnce()

        verify(store, never()).getWorkflow(any())
        registry.interrupt("wf")
        thread.join(5_000)
    }

    @Test
    fun pollReadsNothingWhenTheCheckpointsFlagIsOff() {
        val (thread, _) = blockedAction("wf")

        watcher(InMemoryFeatureGate(mapOf(FeatureGate.Keys.INFLIGHT_CANCELLATION_INTERRUPT to true))).pollOnce()

        verify(store, never()).getWorkflow(any())
        registry.interrupt("wf")
        thread.join(5_000)
    }

    @Test
    fun anActionIsInterruptedAtMostOnceAcrossALocalCancelAndLaterPolls() {
        whenever(store.getWorkflow("wf")).thenReturn(stored(WorkflowInstance.Status.CANCELLED))
        val entered = CountDownLatch(1)
        val done = AtomicBoolean(false)
        val interrupts = AtomicInteger()
        val thread =
            Thread {
                val registration = registry.enter("wf")
                entered.countDown()
                while (!done.get()) {
                    try {
                        Thread.sleep(50)
                    } catch (e: InterruptedException) {
                        interrupts.incrementAndGet() // an action that swallows the interrupt and carries on
                    }
                }
                registry.exit(registration)
            }
        thread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        assertTrue(registry.interrupt("wf")) // the local cancel
        val watcher = watcher(bothOn)
        watcher.pollOnce()
        watcher.pollOnce()
        Thread.sleep(200)
        done.set(true)
        thread.join(5_000)

        assertEquals(1, interrupts.get())
        assertFalse(metrics.incremented.contains("inflightCancellation.watcherInterrupts"))
    }

    @Test
    fun aNonPositivePollIntervalIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            InFlightCancelWatcher(registry, store, bothOn, metrics, Duration.ZERO)
        }
    }

    @Test
    fun oneUnreadableWorkflowDoesNotStopTheOthersBeingChecked() {
        whenever(store.getWorkflow("broken")).thenThrow(RuntimeException("boom"))
        whenever(store.getWorkflow("wf")).thenReturn(stored(WorkflowInstance.Status.CANCELLED))
        val (broken, brokenInterrupted) = blockedAction("broken")
        val (thread, interrupted) = blockedAction("wf")

        watcher(bothOn).pollOnce()

        thread.join(5_000)
        assertTrue(interrupted.get())
        assertFalse(brokenInterrupted.get())
        assertTrue(metrics.incremented.contains("inflightCancellation.watcherErrors"))
        registry.interrupt("broken")
        broken.join(5_000)
    }
}
