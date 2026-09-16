package com.airbnb.skipper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class InMemoryFeatureGateTest {
    private val gate = InMemoryFeatureGate()

    @Test
    fun featuresResolveToTheirDeclaredDefault() {
        FeatureGate.Keys.values()
            .forEach { assertEquals(it.enabledByDefault, gate.isEnabled(it), it.name) }
    }

    @Test
    fun signalPersistenceKillSwitchIsOffByDefault() {
        // The default gate must not disable a feature the SignalMethod docs promise is on by default.
        assertFalse(gate.isEnabled(FeatureGate.Keys.DISABLE_SIGNAL_PERSISTENCE))
    }

    @Test
    fun defaultConfigUsesThisGate() {
        val gate = SkipperConfig.forService("gate-test").featureGate.create(SkipperConfig.forService("gate-test"))
        assertFalse(gate.isEnabled(FeatureGate.Keys.DISABLE_SIGNAL_PERSISTENCE))
    }
}
