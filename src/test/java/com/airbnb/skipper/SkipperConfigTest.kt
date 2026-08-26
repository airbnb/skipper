package com.airbnb.skipper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SkipperConfigTest {
    @Test
    fun testSkipperConfigDefaultCheckpointMode() {
        val config = SkipperConfig(serviceName = "default")
        val error = assertThrows<IllegalArgumentException> {
            config.defaultCheckpointMode = CheckpointMode.DEFAULT
        }
        assert(error.message == "Cannot set defaultCheckpointMode to DEFAULT")
    }

    @Test
    fun testSkipperConfigDefaultCheckpointModeSet() {
        val config = SkipperConfig(serviceName = "default")
        config.defaultCheckpointMode = CheckpointMode.EVENTUAL_CHECKPOINT
        assert(config.defaultCheckpointMode == CheckpointMode.EVENTUAL_CHECKPOINT)
    }

    @Test
    fun testTenantIsNotIsolatedByDefault() {
        // The core never appends an isolation suffix unless a policy opts in. uniqueTenantInDevMode
        // alone does nothing here — only integration layers act on it.
        val config = SkipperConfig(serviceName = "default")
        config.uniqueTenantInDevMode = true
        config.tenant = "my-service-dev"
        assertEquals("my-service-dev", config.tenant)
    }

    @Test
    fun testShouldIsolateTenantTrueAppendsStableSuffix() {
        val config = SkipperConfig(serviceName = "default")
        config.tenant = "my-service-dev"
        config.shouldIsolateTenant = { true }
        assertTrue(config.tenant.startsWith("my-service-dev-"))
        // The suffix is generated once and reused, so repeated reads are stable.
        assertEquals(config.tenant, config.tenant)
    }

    @Test
    fun testShouldIsolateTenantFalseNeverIsolates() {
        val config = SkipperConfig(serviceName = "default")
        config.tenant = "my-service-dev"
        config.shouldIsolateTenant = { false }
        assertEquals("my-service-dev", config.tenant)
    }

    @Test
    fun testShouldIsolateTenantReceivesRawUnsuffixedTenant() {
        // The policy is handed the raw (unsuffixed) tenant value, so it can match on it.
        val config = SkipperConfig(serviceName = "default")
        config.tenant = "myapp-staging"
        config.shouldIsolateTenant = { raw -> raw.endsWith("staging") }
        assertTrue(config.tenant.startsWith("myapp-staging-"))
    }

    @Test
    fun testShouldIsolateTenantIsReevaluatedOnEveryRead() {
        // The decision is not cached: toggling the policy after a read changes the next read.
        val config = SkipperConfig(serviceName = "default")
        config.tenant = "my-service-dev"
        config.shouldIsolateTenant = { true }
        assertTrue(config.tenant.startsWith("my-service-dev-"))
        config.shouldIsolateTenant = { false }
        assertEquals("my-service-dev", config.tenant)
    }

    @Test
    fun testTablePrefixDefaultsToSkipperPrefix() {
        val config = SkipperConfig(serviceName = "default")
        assertEquals("skipper_", config.tablePrefix)
    }

    @Test
    fun testTablePrefixRoundTripsForValidValue() {
        // An existing "tempo" deployment sets the prefix back to "tempo_".
        val config = SkipperConfig(serviceName = "default")
        config.tablePrefix = "tempo_"
        assertEquals("tempo_", config.tablePrefix)
    }

    @Test
    fun testTablePrefixRejectsInvalidCharacters() {
        // The prefix is interpolated into SQL, so the setter rejects anything outside [A-Za-z0-9_].
        val config = SkipperConfig(serviceName = "default")
        assertThrows<IllegalArgumentException> {
            config.tablePrefix = "bad-prefix!"
        }
    }
}
