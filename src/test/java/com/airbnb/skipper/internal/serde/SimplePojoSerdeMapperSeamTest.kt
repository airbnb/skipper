package com.airbnb.skipper.internal.serde

import com.airbnb.skipper.ComponentFactory
import com.airbnb.skipper.SkipperConfig
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the injectable-ObjectMapper seam that lets a host on an ABI-incompatible
 * `jackson-module-kotlin` coexist with Skipper's default serde (see
 * [SimplePojoSerde.buildObjectMapper] and `SkipperConfig.simplePojoSerde`).
 *
 * The load-bearing invariant is **byte identity**: for a given Kotlin module, a serde built through
 * the public seam must produce exactly the same persisted bytes as Skipper's default serde. If that
 * holds, a host can build the module in its own pinned versions without changing what lands in
 * storage, so old and new consumers interoperate on the same stored data.
 *
 * This suite runs under Skipper's own pinned versions, so both mappers here use the same
 * `jackson-module-kotlin` — it proves the seam is a faithful refactor. The genuine *cross-version*
 * proof (2.9-written state read by a 2.14 runtime and vice-versa) must run where both versions
 * exist; a host on a different Jackson version runs these same samples under its own pinned versions as the companion
 * test. The absolute wire format is pinned by the golden-string assertions in
 * [SimplePojoSerdeTest]; because the seam is byte-identical to the default, it inherits that lock.
 */
class SimplePojoSerdeMapperSeamTest {
    data class Nested(val label: String, val score: Int)

    data class Sample(
        val name: String,
        val count: Int,
        val createdAt: Instant,
        val tags: List<String>,
        val nested: Nested,
    )

    private val samples: List<Any> =
        listOf(
            "hello world",
            42,
            Sample(
                name = "workflow-a",
                count = 3,
                createdAt = Instant.ofEpochSecond(1_700_000_000L, 123_456_789L),
                tags = listOf("x", "y", "z"),
                nested = Nested(label = "inner", score = 7),
            ),
        )

    /**
     * The default serde and a serde built through the public seam with a caller-supplied
     * [KotlinModule] must serialize byte-for-byte identically. This is the property every
     * coexisting consumer relies on.
     */
    @Test
    fun seamBuiltMapperIsByteIdenticalToDefault() {
        val default = SimplePojoSerde()
        // Simulates a host wiring its own module. On a newer Jackson the host would instead pass
        // `KotlinModule.Builder().build()`; here we use the constructor available in Skipper's set.
        val hostSupplied = SimplePojoSerde(SimplePojoSerde.buildObjectMapper(KotlinModule()))

        for (sample in samples) {
            assertThat(hostSupplied.serialize(sample))
                .`as`("serialized form for %s", sample)
                .isEqualTo(default.serialize(sample))
        }
    }

    /** A seam-built serde must round-trip every sample back to an equal object. */
    @Test
    fun seamBuiltMapperRoundTrips() {
        val hostSupplied = SimplePojoSerde(SimplePojoSerde.buildObjectMapper(KotlinModule()))

        for (sample in samples) {
            assertThat(hostSupplied.deserialize(hostSupplied.serialize(sample))).isEqualTo(sample)
        }
    }

    /** Cross-reads must work in both directions: bytes written by either serde read back on the other. */
    @Test
    fun defaultAndSeamBuiltMappersCrossRead() {
        val default = SimplePojoSerde()
        val hostSupplied = SimplePojoSerde(SimplePojoSerde.buildObjectMapper(KotlinModule()))

        for (sample in samples) {
            assertThat(default.deserialize(hostSupplied.serialize(sample))).isEqualTo(sample)
            assertThat(hostSupplied.deserialize(default.serialize(sample))).isEqualTo(sample)
        }
    }

    /** By default `SkipperConfig` yields a serde equivalent to the built-in default. */
    @Test
    fun skipperConfigDefaultsToBuiltInSerde() {
        val config = SkipperConfig.forService("seam-test")
        val configured = config.simplePojoSerde.create(config)

        for (sample in samples) {
            assertThat(configured.serialize(sample)).isEqualTo(SimplePojoSerde().serialize(sample))
        }
    }

    /** A host-plugged factory is honored — this is the escape hatch for the diamond-dependency case. */
    @Test
    fun skipperConfigHonorsHostPluggedSerde() {
        val config = SkipperConfig.forService("seam-test")
        config.simplePojoSerde =
            ComponentFactory { SimplePojoSerde(SimplePojoSerde.buildObjectMapper(KotlinModule())) }

        val configured = config.simplePojoSerde.create(config)
        for (sample in samples) {
            assertThat(configured.serialize(sample)).isEqualTo(SimplePojoSerde().serialize(sample))
        }
    }
}
