package com.airbnb.skipper

/**
 * A simple component that allows the gating of new features at a per-app level.
 *
 * This is useful to prevent new features from being enabled for all apps at once, and instead
 * allow for a controlled rollout.
 *
 * The way you would typically use this is by injecting `FeatureGate` into the component that
 * needs to check if a feature is enabled, and then calling `isEnabled` with the feature key.
 *
 * ```
 * @Inject
 * private lateinit var featureGate: FeatureGate
 *
 * fun myMethod() {
 *   if (featureGate.isEnabled(FeatureGate.Keys.TEST_FEATURE)) {
 *     // Feature is enabled
 *   } else {
 *     // Feature is not enabled
 *   }
 * }
 * ```
 */
interface FeatureGate {
    /**
     * Check if a feature is enabled for the current app.
     *
     * @param featureKey The key of the feature to check
     * @return true if the feature is enabled, false otherwise
     */
    fun isEnabled(featureKey: Keys): Boolean

    /**
     * The feature object that is stored in the config as a JSON object.
     */
    class Feature {
        /**
         * The list of apps for which the feature is enabled. If the list contains either the name of
         * the app or "all", the feature is enabled for the app. The name of the app is the value for
         * [SkipperConfig.serviceName].
         */
        @JvmField var enabledApps: MutableList<String> = mutableListOf()
    }

    /**
     * The keys of the features that can be enabled.
     *
     * The name of the key must match the name of the feature in the feature-gate configuration
     * (e.g. `feature_gates.<feature_key>`).
     */
    enum class Keys(val key: String) {
        TEST_FEATURE("test_feature"),
        AUTOMATIC_LEASE_RENEWAL("automatic_lease_renewal"),
        FORCE_SIGNAL_WORKFLOW_EXEC_IN_SCHEDULER("force_signal_workflow_exec_in_scheduler"),
        CREATE_EXISTING_WORKFLOW_IS_NOOP("create_existing_workflow_is_noop"),
        TASK_PARTITIONING("task_partitioning"),

        /**
         * Kill switch for signal persistence. When enabled for an app, signals annotated with
         * `@SignalMethod(persist = true)` are executed without being persisted, reverting to the
         * legacy behavior. Persistence is on by default for opted-in signals.
         */
        DISABLE_SIGNAL_PERSISTENCE("disable_signal_persistence"),

        /**
         * When the engine reschedules a workflow task honouring an active lease (the path a signal
         * takes to wake a workflow), ask the scheduler to also bump the task row's version instead of
         * returning the row untouched (`ScheduleRequest.isBumpVersionWhenHonoringLease`). The lease
         * holder's final versioned `remove` then fails, the row survives, and the task is rescheduled
         * to run right away (or at lease expiry as a fallback). Without this, a signal that lands between the handler
         * persisting WAITING and removing its task is silently lost.
         */
        BUMP_TASK_VERSION_ON_HONORED_LEASE("bump_task_version_on_honored_lease"),
    }
}
