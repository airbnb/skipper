package com.airbnb.skipper.internal

import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.NonRetryableError
import com.google.common.collect.ImmutableMap

/**
 * Checks the version [com.airbnb.skipper.Workflow.version] returns against the range the current
 * code supports. An out-of-range version is always logged at ERROR and counted. When
 * [FeatureGate.Keys.ENFORCE_VERSION_GATE_MIN_VERSION] is enabled, it also throws
 * [NonRetryableError], because a retry would replay the same stored version.
 */
class VersionGateEnforcer(
    private val featureGate: FeatureGate,
    private val metrics: Metrics,
) {
    /**
     * Logs at ERROR and counts a [storedVersion] outside `minVersion..maxVersion`. With enforcement
     * enabled, it then throws [NonRetryableError].
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

        val enforced = featureGate.isEnabled(FeatureGate.Keys.ENFORCE_VERSION_GATE_MIN_VERSION)
        val direction = if (storedVersion < minVersion) DIRECTION_BELOW_MIN else DIRECTION_ABOVE_MAX
        val message = buildMessage(workflowId, changeId, storedVersion, minVersion, maxVersion)

        metrics
            .counter(
                ImmutableMap.of(
                    CHANGE_ID_TAG,
                    changeId,
                    DIRECTION_TAG,
                    direction,
                    ENFORCED_TAG,
                    enforced.toString(),
                ),
                METRIC_COMPONENT_NAME,
                STORED_VERSION_OUT_OF_RANGE,
            )
            .inc()
        log.error(message)

        if (enforced) {
            throw NonRetryableError(message)
        }
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(VersionGateEnforcer::class.java)

        private const val METRIC_COMPONENT_NAME = "versionGate"
        private const val STORED_VERSION_OUT_OF_RANGE = "storedVersionOutOfRange"
        private const val CHANGE_ID_TAG = "changeId"
        private const val DIRECTION_TAG = "direction"
        private const val ENFORCED_TAG = "enforced"
        private const val DIRECTION_BELOW_MIN = "below_min"
        private const val DIRECTION_ABOVE_MAX = "above_max"

        /** The log and exception message for an out-of-range stored version. */
        private fun buildMessage(
            workflowId: String,
            changeId: String,
            storedVersion: Int,
            minVersion: Int,
            maxVersion: Int,
        ): String =
            "version gate '$changeId' for workflow '$workflowId' recorded version $storedVersion," +
                " but the current code only supports versions $minVersion..$maxVersion. Either" +
                " minVersion was raised or the deploy was rolled back while this instance was in" +
                " flight. Let such instances finish before narrowing the supported range."
    }
}
