package com.airbnb.skipper.statemachine

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InvalidTransitionPolicyTest {
    @Test
    fun `enum has exactly two values`() {
        assertThat(InvalidTransitionPolicy.values()).hasSize(2)
    }

    @Test
    fun `LOG_AND_IGNORE is the first value`() {
        assertThat(InvalidTransitionPolicy.values()[0]).isEqualTo(InvalidTransitionPolicy.LOG_AND_IGNORE)
    }

    @Test
    fun `valueOf round-trips correctly`() {
        assertThat(InvalidTransitionPolicy.valueOf("LOG_AND_IGNORE"))
            .isEqualTo(InvalidTransitionPolicy.LOG_AND_IGNORE)
        assertThat(InvalidTransitionPolicy.valueOf("THROW"))
            .isEqualTo(InvalidTransitionPolicy.THROW)
    }
}
