package com.airbnb.skipper

import java.util.Optional

/**
 * A simple component for dynamic configuration values.
 *
 * This is useful for values that need to be changed at runtime, without needing to redeploy the
 * app.
 *
 * The way you would typically use this is by injecting `Knobs` into the component that needs to
 * get the value of a knob, and then calling `get<type>` with the knob key. For example:
 *
 * ```
 * @Inject
 * private lateinit var knobs: Knobs
 *
 * fun myMethod() {
 *   val testKnob = knobs.getInteger(Knobs.Keys.TEST_KNOB).orElse(0)
 * }
 * ```
 */
interface Knobs {
    /**
     * Get the knob value for the given key as an integer.
     *
     * @param key The key to get the value for
     * @return The integer value of the knob, if it exists, otherwise empty Optional
     */
    fun getInteger(key: Keys): Optional<Int>

    class Knob {
        @JvmField var overrides: MutableMap<String, Any> = mutableMapOf()
        @JvmField var defaultValue: Any = 0
    }

    /**
     * A list of all the available knobs. See the knob definitions below.
     */
    enum class Keys(val key: String) {
        TEST_KNOB("test_knob"),

        /**
         * The number of times the scheduler will fetch tasks in a single run. If this value is
         * set to -1, then a dynamic value will be computed at runtime.
         */
        SCHEDULER_FETCH_COUNT("scheduler_fetch_count"),
    }
}
