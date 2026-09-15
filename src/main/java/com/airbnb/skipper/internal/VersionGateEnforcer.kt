package com.airbnb.skipper.internal

import com.airbnb.skipper.FeatureGate
import com.airbnb.skipper.Metrics
import com.airbnb.skipper.NonRetryableError
import com.google.common.collect.ImmutableMap
import javax.inject.Inject

/**
 * Guards the value that [com.airbnb.skipper.Workflow.version] hands back against the version range
 * the current code declares it supports.
 *
 * `version(changeId, minVersion, maxVersion)` is a named checkpoint: on first execution it records
 * and returns `maxVersion`; on replay the engine returns the *persisted* value without re-invoking
 * the backing action (see [ActionExecutor.executeAction] — a defined checkpoint short-circuits the
 * method call). So the stored version is only observable at the call site, in `version()`, on the
 * value that comes back. This enforcer runs there.
 *
 * The stored version falls outside `minVersion..maxVersion` in two real situations, both of which
 * used to execute silently against whatever the remaining code did with the stale value:
 * - `stored < minVersion`: a version's branch was pruned (and `minVersion` raised) while instances
 *   that recorded the pruned version were still in flight.
 * - `stored > maxVersion`: a deploy was rolled back to code with a lower `maxVersion` while
 *   instances had already recorded the higher one — the more common case in practice.
 *
 * Both bounds are enforced. The condition is permanent for a given instance (retrying replays the
 * same persisted value), so when enforcement is enabled it is surfaced as a [NonRetryableError],
 * which the workflow executor maps to a terminal `WorkflowInstance.Status.ERROR` without consuming
 * the retry budget.
 *
 * Enforcement is opt-in ([FeatureGate.Keys.ENFORCE_VERSION_GATE_MIN_VERSION]): turning a
 * previously-silent degradation into a hard failure can surface latent breakage in a service that
 * raised `minVersion` prematurely, so the throw is off until an operator enables it per app. The
 * ERROR log and the metric fire regardless of the gate, so the problem is discoverable before the
 * throw is switched on.
 */
open class VersionGateEnforcer
    @Inject
    constructor(
        private val featureGate: FeatureGate,
        private val metrics: Metrics,
    ) {
        /**
         * Returns [storedVersion] when it is within `minVersion..maxVersion`. Otherwise always logs
         * at ERROR and increments the out-of-range counter; then, only if enforcement is enabled for
         * this app, throws [NonRetryableError]. When enforcement is disabled the stale value is
         * returned unchanged, preserving the pre-enforcement behavior (detect-only).
         *
         * @param workflowId the instance whose stored version is being checked (for the log/error;
         *   not a metric tag — it is unbounded cardinality)
         * @param changeId the version gate's stable identifier
         * @param storedVersion the version this instance actually recorded
         * @param minVersion the lowest version the current code supports
         * @param maxVersion the latest version the current code knows about
         */
        open fun enforce(
            workflowId: String,
            changeId: String,
            storedVersion: Int,
            minVersion: Int,
            maxVersion: Int,
        ): Int {
            if (storedVersion in minVersion..maxVersion) {
                return storedVersion
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
            return storedVersion
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

            /**
             * A single log/exception line that is enough to diagnose the failure without any other
             * context: the change id, the stored version, the declared range, the workflow id, and a
             * plain statement of what happened and how to unblock.
             */
            fun buildMessage(
                workflowId: String,
                changeId: String,
                storedVersion: Int,
                minVersion: Int,
                maxVersion: Int,
            ): String =
                "version gate '$changeId' for workflow '$workflowId' recorded version $storedVersion," +
                    " but the current code only supports versions $minVersion..$maxVersion. This" +
                    " instance persisted a version the code no longer handles: a version branch was" +
                    " removed (minVersion raised) or the deploy was rolled back (maxVersion lowered)" +
                    " while instances holding this version were still in flight. Drain or migrate" +
                    " these instances before narrowing the supported range."
        }
    }
