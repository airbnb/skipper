package com.airbnb.skipper

/**
 * An in-memory implementation of [FeatureGate] where all features are enabled by default.
 *
 * This is useful for testing or for environments where feature gating is not needed.
 */
class InMemoryFeatureGate : FeatureGate {
    /**
     * Always returns true, indicating all features are enabled.
     *
     * @param featureKey The key of the feature to check
     * @return true always
     */
    override fun isEnabled(featureKey: FeatureGate.Keys): Boolean = true
}
