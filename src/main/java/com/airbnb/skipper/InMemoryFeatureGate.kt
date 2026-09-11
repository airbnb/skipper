package com.airbnb.skipper

/**
 * An in-memory implementation of [FeatureGate] where every feature is enabled by default, except
 * for kill switches, which are off: [FeatureGate.Keys.DISABLE_SIGNAL_PERSISTENCE] answers `false`
 * so that `@SignalMethod(persist = true)` persists signals out of the box, as its documentation says.
 *
 * This is the default gate a [SkipperConfig] starts with, and is useful for testing or for
 * environments where feature gating is not needed.
 */
class InMemoryFeatureGate : FeatureGate {
    /**
     * Returns true for every feature except the signal-persistence kill switch.
     *
     * @param featureKey The key of the feature to check
     * @return false for [FeatureGate.Keys.DISABLE_SIGNAL_PERSISTENCE], true otherwise
     */
    override fun isEnabled(featureKey: FeatureGate.Keys): Boolean = featureKey != FeatureGate.Keys.DISABLE_SIGNAL_PERSISTENCE
}
