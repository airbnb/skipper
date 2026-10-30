package com.airbnb.skipper.internal

import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.RetryableError
import com.google.common.collect.ImmutableMap
import java.time.Duration

/**
 * Checks the version [com.airbnb.skipper.Workflow.version] returns against the range the current
 * code supports. An out-of-range version is always logged at WARN and counted. When
 * [FeatureGate.Keys.ENFORCE_VERSION_GATE_RANGE] is enabled, it also throws.
 */
class VersionGateEnforcer(
    private val featureGate: FeatureGate,
    private val metrics: Metrics,
) {
    /**
     * Logs at WARN and counts a [storedVersion] outside `minVersion..maxVersion`. With enforcement
     * enabled, a version below the range throws [NonRetryableError], since no host will handle it
     * again. A version above the range throws [RetryableError], so the instance is retried until a
     * host running the newer code resumes it, or the scheduler's retry limit is reached.
     *
     * @param workflowId the instance being checked; used in the message only, never as a metric tag
     * @param changeId the version gate's stable identifier
     * @param storedVersion the version this instance actually recorded
     * @param minVersion the lowest version the current code supports
     * @param maxVersion the latest version the current code knows about
     */
    fun enforce(
        workflowId: String,
        changeId: String,
        storedVersion: Int,
        minVersion: Int,
        maxVersion: Int,
    ) {
        if (storedVersion in minVersion..maxVersion) {
            return
        }

        val enforced = featureGate.isEnabled(FeatureGate.Keys.ENFORCE_VERSION_GATE_RANGE)
        val belowMin = storedVersion < minVersion
        val message = buildMessage(workflowId, changeId, storedVersion, minVersion, maxVersion)

        metrics
            .counter(
                ImmutableMap.of(
                    CHANGE_ID_TAG,
                    changeId,
                    DIRECTION_TAG,
                    if (belowMin) DIRECTION_BELOW_MIN else DIRECTION_ABOVE_MAX,
                    ENFORCED_TAG,
                    enforced.toString(),
                ),
                METRIC_COMPONENT_NAME,
                STORED_VERSION_OUT_OF_RANGE,
            )
            .inc()
        log.warn(message)

        if (!enforced) {
            return
        }
        if (belowMin) {
            throw NonRetryableError(message)
        }
        throw RetryableError(message, null, ABOVE_MAX_RETRY_DELAY)
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(VersionGateEnforcer::class.java)

        /** How long an instance with a too-new version waits before it is tried again. */
        private val ABOVE_MAX_RETRY_DELAY: Duration = Duration.ofMinutes(5)

        private const val METRIC_COMPONENT_NAME = "versionGate"
        private const val STORED_VERSION_OUT_OF_RANGE = "storedVersionOutOfRange"
        private const val CHANGE_ID_TAG = "changeId"
        private const val DIRECTION_TAG = "direction"
        private const val ENFORCED_TAG = "enforced"
        private const val DIRECTION_BELOW_MIN = "below_min"
        private const val DIRECTION_ABOVE_MAX = "above_max"

        /** The log and exception message, with advice that fits the direction. */
        private fun buildMessage(
            workflowId: String,
            changeId: String,
            storedVersion: Int,
            minVersion: Int,
            maxVersion: Int,
        ): String {
            val prefix = "version gate '$changeId' for workflow '$workflowId' recorded version $storedVersion,"
            val range = "$minVersion..$maxVersion"
            return if (storedVersion < minVersion) {
                "$prefix below the supported range $range. minVersion was raised past it while this" +
                    " instance was in flight. Let such instances finish before raising minVersion."
            } else {
                "$prefix above the supported range $range. Newer code recorded this version. Either a" +
                    " rolling deploy is still in progress, or a deploy was rolled back. Finish the" +
                    " deploy, or roll forward, so a host running the newer code resumes this instance."
            }
        }
    }
}
