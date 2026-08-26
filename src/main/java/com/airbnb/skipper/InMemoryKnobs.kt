package com.airbnb.skipper

import java.util.Optional

/**
 * An in-memory implementation of [Knobs] that always returns empty values.
 *
 * This is useful for testing or for environments where dynamic configuration is not needed.
 * Callers should use the default values provided via `orElse()` when using this implementation.
 */
class InMemoryKnobs : Knobs {
    /**
     * Always returns an empty Optional, indicating no knob value is configured.
     *
     * @param key The key to get the value for
     * @return An empty Optional
     */
    override fun getInteger(key: Knobs.Keys): Optional<Int> = Optional.empty()
}
