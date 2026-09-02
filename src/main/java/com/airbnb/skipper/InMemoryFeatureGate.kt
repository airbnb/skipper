package com.airbnb.skipper

/**
 * An in-memory [FeatureGate] with no external configuration.
 *
 * Each feature resolves to its declared [FeatureGate.Keys.enabledByDefault] unless overridden here,
 * so an opt-in feature stays off until an app enables it. This is the default gate in
 * [SkipperConfig] and is also convenient for tests.
 *
 * @param overrides per-feature values that take precedence over the declared defaults
 */
class InMemoryFeatureGate(
    private val overrides: Map<FeatureGate.Keys, Boolean> = emptyMap(),
) : FeatureGate {
    override fun isEnabled(featureKey: FeatureGate.Keys): Boolean =
        overrides[featureKey] ?: featureKey.enabledByDefault
}
