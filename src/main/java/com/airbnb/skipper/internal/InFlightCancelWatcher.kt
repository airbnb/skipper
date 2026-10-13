package com.airbnb.skipper.internal

import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.WorkflowInstance
import com.airbnb.skipper.internal.storage.WorkflowStore
import java.time.Duration
import org.slf4j.LoggerFactory

/**
 * Interrupts the actions running here whose workflow another process cancelled. Every [pollInterval]
 * the scheduler manager calls [pollOnce], which reads the stored workflow of each in-flight action and
 * interrupts on CANCELLED. It reads nothing unless both in-flight cancellation flags are on.
 */
class InFlightCancelWatcher(
    private val inFlightActions: InFlightActions,
    private val workflowStore: WorkflowStore,
    private val featureGate: FeatureGate,
    private val metrics: Metrics,
    val pollInterval: Duration,
) {
    init {
        require(pollInterval.toMillis() > 0) { "inflightCancellationPollInterval must be at least 1 ms" }
    }

    fun pollOnce() {
        if (!featureGate.isEnabled(FeatureGate.Keys.INFLIGHT_CANCELLATION_INTERRUPT) ||
            !featureGate.isEnabled(FeatureGate.Keys.INFLIGHT_CANCELLATION_CHECKPOINTS)
        ) {
            return
        }
        for (workflowId in inFlightActions.workflowIds()) {
            try {
                val stored = workflowStore.getWorkflow(workflowId)
                if (stored.isDefined && stored.get().status == WorkflowInstance.Status.CANCELLED &&
                    inFlightActions.interrupt(workflowId)
                ) {
                    metrics.counter(METRICS_COMPONENT, "watcherInterrupts").inc()
                    log.info("workflowId={} was cancelled elsewhere; interrupted its running action", workflowId)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception
            ) {
                // One unreadable workflow must not stop the others from being checked.
                log.warn("could not check whether workflowId={} was cancelled", workflowId, e)
                metrics.counter(METRICS_COMPONENT, "watcherErrors").inc()
            }
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(InFlightCancelWatcher::class.java)
        private const val METRICS_COMPONENT = "inflightCancellation"
    }
}
