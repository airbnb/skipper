package com.airbnb.skipper

/** A [FeatureGate] that answers each key's declared default unless overridden. */
class InMemoryFeatureGate(
    private val overrides: Map<FeatureGate.Keys, Boolean> = emptyMap(),
) : FeatureGate {
    override fun isEnabled(featureKey: FeatureGate.Keys): Boolean = overrides[featureKey] ?: featureKey.enabledByDefault
}
